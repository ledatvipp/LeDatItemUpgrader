package vn.ledat.itemupgrader.paper.service;

import java.math.RoundingMode;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicInteger;
import vn.ledat.itemupgrader.runtime.PreviewRequestGate;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import vn.ledat.itemupgrader.catalog.CatalogAccess;
import vn.ledat.itemupgrader.catalog.CatalogIndex;
import vn.ledat.itemupgrader.catalog.CatalogQuery;
import vn.ledat.itemupgrader.catalog.CatalogResult;
import vn.ledat.itemupgrader.catalog.CatalogService;
import vn.ledat.itemupgrader.item.ItemSnapshot;
import vn.ledat.itemupgrader.paper.item.PaperItemSnapshotFactory;
import vn.ledat.itemupgrader.paper.item.PlatformIdentityIndex;
import vn.ledat.itemupgrader.paper.message.Messages;
import vn.ledat.itemupgrader.paper.platform.PlatformAccess;
import vn.ledat.itemupgrader.runtime.RuntimeStore;
import vn.ledat.itemupgrader.runtime.UpgraderRuntime;
import vn.ledat.itemupgrader.util.Decimals;

/** Read-only command presentation; every query result is rebound to UUID/job/revision/hand/access. */
public final class CatalogPreviewService {
    public enum Mode { CATALOG, RECOMMEND, PATHS }
    private final JavaPlugin owner;
    private final PlatformAccess platform;
    private final RuntimeStore<UpgraderRuntime> runtime;
    private final Messages messages;
    private final PlatformIdentityIndex identities;
    private final CatalogCacheService cache;
    private final AccessSnapshotService accessSnapshots;
    private final PaperItemSnapshotFactory snapshots = new PaperItemSnapshotFactory();
    private final CatalogService catalogs = new CatalogService();
    private final PreviewRequestGate jobs = new PreviewRequestGate(16, Duration.ofSeconds(30), System::nanoTime);
    private final AtomicInteger computations = new AtomicInteger();
    public CatalogPreviewService(JavaPlugin owner, PlatformAccess platform, RuntimeStore<UpgraderRuntime> runtime,
                                 Messages messages, PlatformIdentityIndex identities, CatalogCacheService cache, AccessSnapshotService accessSnapshots) {
        this.owner = owner; this.platform = platform; this.runtime = runtime; this.messages = messages; this.identities = identities; this.cache = cache; this.accessSnapshots = accessSnapshots;
    }
    public void preview(Player player, Mode mode, CatalogQuery query) {
        var current = runtime.snapshot();
        if (current.isEmpty()) { messages.send(player, "not-ready"); return; }
        UUID id = player.getUniqueId(); var definition = current.orElseThrow();
        if (jobs.contains(id)) { messages.send(player, "catalog-busy"); return; }
        if (jobs.size() >= 16) { messages.send(player, "backend-busy"); return; }
        if (!platform.acquire(id, "catalog", Duration.ofMillis(definition.value().commandCooldownMillis()))) {
            messages.send(player, "cooldown"); return;
        }
        if (player.getInventory().getItemInMainHand().getType().isAir()) { messages.send(player, "empty-hand"); return; }
        var acquired = jobs.begin(id);
        if (acquired.isEmpty()) { messages.send(player, "backend-busy"); return; }
        var job = acquired.orElseThrow();
        messages.send(player, "catalog-loading");
        try {
            cache.prepare(player, definition,
                    index -> routeReady(id, job, definition, index, mode, query),
                    reason -> fail(id, job, "catalog-build-" + reason.name().toLowerCase(Locale.ROOT)));
        } catch (RuntimeException error) {
            jobs.finish(job); owner.getLogger().log(Level.WARNING, "Cannot schedule catalog preview", error);
            messages.send(player, "catalog-failed");
        }
    }
    private void routeReady(UUID id, PreviewRequestGate.Ticket job, RuntimeStore.Snapshot<UpgraderRuntime> definition,
                            CatalogIndex index, Mode mode, CatalogQuery query) {
        try {
            platform.player(id, player -> {
                if (!owns(id, job)) return;
                if (!runtime.isCurrent(definition.revision()) || !cache.current(index)) { fail(id, job, "catalog-stale"); return; }
                if (!player.hasPermission("ledatitemupgrader.use") || !player.hasPermission("ledatitemupgrader.admin.catalog")) { fail(id, job, "no-permission"); return; }
                try {
                    ItemSnapshot source = capture(player, definition);
                    CatalogAccess access = accessSnapshots.capture(player, definition.value());
                    if (computations.get() >= 16) { fail(id, job, "backend-busy"); return; }
                    computations.incrementAndGet();
                    try { platform.async("itemupgrader-catalog-query", () -> evaluate(id, job, definition, index, source, access, mode, query)); }
                    catch (RuntimeException rejected) { computations.decrementAndGet(); throw rejected; }
                } catch (IllegalArgumentException error) { fail(id, job, "catalog-source-invalid"); }
                catch (RuntimeException error) {
                    owner.getLogger().log(Level.WARNING, "Catalog source capture/scheduling failed for " + id, error);
                    fail(id, job, "catalog-failed");
                }
            });
        } catch (RuntimeException error) { fail(id, job, "catalog-failed"); }
    }
    private void evaluate(UUID id, PreviewRequestGate.Ticket job, RuntimeStore.Snapshot<UpgraderRuntime> definition, CatalogIndex index,
                          ItemSnapshot source, CatalogAccess access, Mode mode, CatalogQuery query) {
        try {
            if (!runtime.isCurrent(definition.revision()) || !jobs.isCurrent(job)) {
                platform.player(id, online -> fail(id, job, "catalog-stale")); return;
            }
            CatalogResult result = mode == Mode.CATALOG ? catalogs.browse(source, index, access, query) : catalogs.recommend(source, index, access);
            platform.player(id, online -> {
                if (!owns(id, job)) return;
                if (!cache.current(index) || !runtime.isCurrent(definition.revision())) { fail(id, job, "catalog-stale"); return; }
                if (!online.hasPermission("ledatitemupgrader.use") || !online.hasPermission("ledatitemupgrader.admin.catalog")) { fail(id, job, "no-permission"); return; }
                try {
                    ItemSnapshot current = capture(online, definition);
                    if (!current.fingerprint().equals(source.fingerprint()) || !current.facts().equals(source.facts())
                            || !accessSnapshots.capture(online, definition.value()).equals(access)) { fail(id, job, "catalog-stale"); return; }
                    if (!jobs.finish(job)) return;
                } catch (RuntimeException error) {
                    owner.getLogger().log(Level.FINE, "Catalog source changed or became invalid before rendering", error);
                    fail(id, job, "catalog-source-invalid"); return;
                }
                try { render(online, source, result, mode); }
                catch (RuntimeException error) {
                    owner.getLogger().log(Level.WARNING, "Cannot render catalog preview for " + id, error);
                    messages.send(online, "catalog-failed");
                }
            });
        } catch (RuntimeException error) {
            owner.getLogger().log(Level.WARNING, "Catalog query failed for " + id, error);
            try { platform.player(id, online -> fail(id, job, "catalog-failed")); }
            catch (RuntimeException rejected) { owner.getLogger().log(Level.FINE, "Catalog callback rejected", rejected); }
        } finally { computations.decrementAndGet(); }
    }
    private ItemSnapshot capture(Player player, RuntimeStore.Snapshot<UpgraderRuntime> definition) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir() || held.getAmount() < 1) throw new IllegalArgumentException("empty source");
        var identity = identities.identify(held, definition);
        if (identity.key().isEmpty()) throw new IllegalArgumentException("unverified source identity: " + identity.status());
        return snapshots.capture(held, identity.key().orElseThrow());
    }
    private void render(Player player, ItemSnapshot source, CatalogResult result, Mode mode) {
        messages.send(player, "catalog-header", Map.of("item", source.facts().key().value(), "amount", String.valueOf(source.facts().amount())));
        result.path().ifPresentOrElse(path -> messages.send(player, "catalog-path", Map.of("path", path.id(), "mode", path.mode().name())),
                () -> messages.send(player, "catalog-no-path"));
        if (result.status() != CatalogResult.Status.OK) {
            if (result.status() == CatalogResult.Status.SOURCE_REJECTED)
                messages.send(player, "value-" + result.sourceValue().status().name().toLowerCase(Locale.ROOT).replace('_', '-'));
            else if (result.status() == CatalogResult.Status.NO_PATH) messages.send(player, "catalog-path-required");
            else messages.send(player, "catalog-" + result.status().name().toLowerCase(Locale.ROOT).replace('_', '-'),
                    Map.of("page", String.valueOf(result.page()), "pages", String.valueOf(result.totalPages())));
            return;
        }
        if (mode == Mode.PATHS) messages.send(player, "catalog-path-filtered");
        for (CatalogIndex.PricedTarget row : result.rows()) {
            String ratio = Decimals.display(row.value().totalValue().divide(result.sourceValue().quote().orElseThrow().totalValue(), 6, RoundingMode.HALF_UP));
            messages.send(player, "catalog-row", Map.of("id", row.definition().id(), "item", row.definition().item().value(),
                    "amount", String.valueOf(row.definition().amount()), "value", Decimals.display(row.value().totalValue()),
                    "ratio", ratio, "category", row.definition().category()));
        }
        messages.send(player, "catalog-page", Map.of("page", String.valueOf(result.page()), "pages", String.valueOf(result.totalPages()),
                "total", String.valueOf(result.totalMatches())));
        messages.send(player, "catalog-readonly");
    }
    private boolean owns(UUID id, PreviewRequestGate.Ticket job) { return id.equals(job.viewer()) && jobs.isCurrent(job); }
    private void fail(UUID id, PreviewRequestGate.Ticket job, String message) {
        if (!jobs.finish(job)) return;
        if (runtime.state() != RuntimeStore.State.STOPPED) {
            try { platform.player(id, online -> messages.send(online, message)); }
            catch (RuntimeException rejected) { owner.getLogger().log(Level.FINE, "Catalog notice rejected by scheduler", rejected); }
        }
    }
    public void forget(UUID id) { jobs.forget(id); cache.forget(id); }
    public void invalidate() { cache.invalidate(); }
    public void close() { jobs.close(); cache.close(); }
}
