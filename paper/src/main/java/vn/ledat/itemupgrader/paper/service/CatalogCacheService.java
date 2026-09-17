package vn.ledat.itemupgrader.paper.service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import vn.ledat.itemupgrader.catalog.CatalogIndex;
import vn.ledat.itemupgrader.catalog.TargetDefinition;
import vn.ledat.itemupgrader.catalog.TargetProbe;
import vn.ledat.itemupgrader.paper.item.PaperItemSnapshotFactory;
import vn.ledat.itemupgrader.paper.item.PlatformIdentityIndex;
import vn.ledat.itemupgrader.paper.platform.PlatformAccess;
import vn.ledat.itemupgrader.runtime.RuntimeStore;
import vn.ledat.itemupgrader.runtime.UpgraderRuntime;

/**
 * Paper-main-owned, single-flight lazy template cache. Platform dispatches bounded capture callbacks;
 * detached pricing/index compilation runs on a worker. No raw timer, provider scan, or Player cache.
 * A batch is bounded per callback, NOT a claimed wall-clock/TPS or per-tick guarantee.
 */
public final class CatalogCacheService {
    public enum Failure { BUSY, STALE, TIMEOUT, FAILED }
    private static final long BUILD_TIMEOUT_NANOS = 30_000_000_000L;
    private final JavaPlugin owner;
    private final PlatformAccess platform;
    private final RuntimeStore<UpgraderRuntime> runtime;
    private final PlatformIdentityIndex identities;
    private final PaperItemSnapshotFactory snapshots = new PaperItemSnapshotFactory();
    private CatalogIndex cache;
    private Build active;
    private boolean stopped;
    private static final class Build {
        final UUID viewer;
        final RuntimeStore.Snapshot<UpgraderRuntime> runtime;
        final List<TargetDefinition> targets;
        final Map<String, TargetProbe> probes = new HashMap<>();
        final Consumer<CatalogIndex> ready;
        final Consumer<Failure> failed;
        final long started = System.nanoTime();
        int next;
        long bytes;
        Build(UUID viewer, RuntimeStore.Snapshot<UpgraderRuntime> runtime, Consumer<CatalogIndex> ready, Consumer<Failure> failed) {
            this.viewer = viewer; this.runtime = runtime; this.ready = ready; this.failed = failed;
            this.targets = runtime.value().catalog().targets().values().stream()
                    .filter(TargetDefinition::enabled).sorted(Comparator.comparing(TargetDefinition::id)).toList();
        }
    }
    public CatalogCacheService(JavaPlugin owner, PlatformAccess platform, RuntimeStore<UpgraderRuntime> runtime,
                               PlatformIdentityIndex identities) {
        this.owner = owner; this.platform = platform; this.runtime = runtime; this.identities = identities;
    }
    /** Callbacks run only on the owner thread. Callers must capture UUID/context, not Player. */
    public void prepare(Player player, RuntimeStore.Snapshot<UpgraderRuntime> definition,
                        Consumer<CatalogIndex> ready, Consumer<Failure> failed) {
        if (stopped || !runtime.isCurrent(definition.revision())) { failed.accept(Failure.STALE); return; }
        if (active != null && (System.nanoTime() - active.started > BUILD_TIMEOUT_NANOS
                || !runtime.isCurrent(active.runtime.revision()))) abandon(active, Failure.STALE);
        if (cache != null && cache.revision() == definition.revision()) { ready.accept(cache); return; }
        cache = null;
        if (active != null) { failed.accept(Failure.BUSY); return; }
        Build job = new Build(player.getUniqueId(), definition, ready, failed); active = job;
        step(job);
    }
    private boolean valid(Build job) {
        return !stopped && active == job && runtime.isCurrent(job.runtime.revision());
    }
    private void step(Build job) {
        if (!valid(job)) { abandon(job, Failure.STALE); return; }
        if (System.nanoTime() - job.started > BUILD_TIMEOUT_NANOS) { abandon(job, Failure.TIMEOUT); return; }
        try {
            if (!identities.warm(job.runtime)) { next(job); return; }
            int budget = job.runtime.value().identityProbeBatch();
            while (job.next < job.targets.size() && budget-- > 0) {
                TargetDefinition target = job.targets.get(job.next++);
                TargetProbe probe = capture(target, job.runtime);
                job.bytes += probe.snapshot().map(snapshot -> (long) snapshot.byteSize()).orElse(0L);
                if (job.bytes > 16L * 1024 * 1024) throw new IllegalArgumentException("catalog capture payload budget exceeded");
                job.probes.put(target.id(), probe);
            }
            if (job.next < job.targets.size()) { next(job); return; }
            Map<String, TargetProbe> detached = Map.copyOf(job.probes);
            platform.async("itemupgrader-catalog-index", () -> {
                try {
                    CatalogIndex built = CatalogIndex.build(job.runtime.revision(), job.runtime.value().catalog(),
                            job.runtime.value().values(), detached);
                    platform.player(job.viewer, online -> {
                        if (!valid(job)) { abandon(job, Failure.STALE); return; }
                        cache = built; active = null;
                        owner.getLogger().info("Catalog ready: revision=" + built.revision() + "; targets=" + built.ready().size()
                                + "; rejected=" + built.rejected().size() + "; payload-bytes=" + built.snapshotBytes());
                        job.ready.accept(built);
                    });
                } catch (RuntimeException error) { workerFailure(job, error); }
            });
        } catch (RuntimeException error) {
            owner.getLogger().log(Level.WARNING, "Catalog preparation rejected", error);
            abandon(job, Failure.FAILED);
        }
    }
    private TargetProbe capture(TargetDefinition target, RuntimeStore.Snapshot<UpgraderRuntime> definition) {
        var created = platform.create(target.item().value());
        if (created.isEmpty()) return TargetProbe.failed(TargetProbe.Failure.PROVIDER_UNAVAILABLE);
        ItemStack item = created.get().clone();
        if (item.getType().isAir() || target.amount() > item.getMaxStackSize()) return TargetProbe.failed(TargetProbe.Failure.INVALID_TEMPLATE);
        item.setAmount(target.amount());
        var identity = identities.identify(item, definition);
        if (identity.key().isEmpty() || !identity.key().orElseThrow().equals(target.item()))
            return TargetProbe.failed(TargetProbe.Failure.IDENTITY_UNVERIFIED);
        return TargetProbe.verified(snapshots.capture(item, target.item()));
    }
    private void next(Build job) {
        // Explicit async hop avoids recursive inline dispatch if Platform optimizes owner-thread calls.
        platform.async("itemupgrader-catalog-dispatch", () -> {
            try { platform.player(job.viewer, online -> step(job)); }
            catch (RuntimeException error) { workerFailure(job, error); }
        });
    }
    private void workerFailure(Build job, RuntimeException error) {
        owner.getLogger().log(Level.WARNING, "Catalog worker failed", error);
        try { platform.player(job.viewer, online -> abandon(job, Failure.FAILED)); }
        catch (RuntimeException rejected) {
            // On shutdown callbacks can be rejected; quit/disable cleanup or next-request timeout owns release.
            owner.getLogger().log(Level.FINE, "Catalog failure callback rejected by scheduler", rejected);
        }
    }
    private void abandon(Build job, Failure failure) {
        if (active != job) return;
        active = null; job.probes.clear(); job.failed.accept(failure);
    }
    public boolean current(CatalogIndex expected) { return !stopped && cache == expected && runtime.isCurrent(expected.revision()); }
    public void forget(UUID viewer) { if (active != null && active.viewer.equals(viewer)) { active.probes.clear(); active = null; } }
    public void invalidate() {
        cache = null;
        if (active != null) abandon(active, Failure.STALE);
        identities.clear();
    }
    public void close() { stopped = true; cache = null; if (active != null) active.probes.clear(); active = null; }
}
