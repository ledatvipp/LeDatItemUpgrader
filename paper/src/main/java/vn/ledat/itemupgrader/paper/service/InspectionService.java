package vn.ledat.itemupgrader.paper.service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import vn.ledat.itemupgrader.item.ItemSnapshot;
import vn.ledat.itemupgrader.paper.item.PaperItemSnapshotFactory;
import vn.ledat.itemupgrader.paper.item.PlatformIdentityIndex;
import vn.ledat.itemupgrader.paper.message.Messages;
import vn.ledat.itemupgrader.paper.platform.PlatformAccess;
import vn.ledat.itemupgrader.runtime.RuntimeStore;
import vn.ledat.itemupgrader.runtime.UpgraderRuntime;
import vn.ledat.itemupgrader.util.Decimals;
import vn.ledat.itemupgrader.value.ItemValueService;
import vn.ledat.itemupgrader.value.ValueResult;

/** Read-only service. This phase never removes an item, charges currency, or grants a reward. */
public final class InspectionService {
    private final JavaPlugin owner;
    private final PlatformAccess platform;
    private final RuntimeStore<UpgraderRuntime> runtime;
    private final PlatformIdentityIndex identities;
    private final Messages messages;
    private final PaperItemSnapshotFactory snapshots = new PaperItemSnapshotFactory();
    private final ItemValueService values = new ItemValueService();
    private final Map<UUID, Long> jobs = new HashMap<>(); // Paper main thread only.
    private final AtomicInteger inFlight = new AtomicInteger();
    private long sequence;
    public InspectionService(JavaPlugin owner, PlatformAccess platform, RuntimeStore<UpgraderRuntime> runtime,
                             PlatformIdentityIndex identities, Messages messages) {
        this.owner = owner; this.platform = platform; this.runtime = runtime; this.identities = identities; this.messages = messages;
    }
    public void inspect(Player player, boolean detailed) {
        var active = runtime.snapshot();
        if (active.isEmpty()) { messages.send(player, "not-ready"); return; }
        var definition = active.get();
        UUID id = player.getUniqueId();
        if (jobs.containsKey(id)) { messages.send(player, "inspection-busy"); return; }
        if (!platform.acquire(id, "inspect", Duration.ofMillis(definition.value().commandCooldownMillis()))) {
            messages.send(player, "cooldown"); return;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir() || held.getAmount() < 1) { messages.send(player, "empty-hand"); return; }
        if (inFlight.get() >= 16) { messages.send(player, "backend-busy"); return; }
        final ItemSnapshot snapshot;
        try {
            var identity = identities.identify(held, definition);
            if (identity.status() != PlatformIdentityIndex.Status.IDENTIFIED) {
                messages.send(player, "identity-" + identity.status().name().toLowerCase(Locale.ROOT).replace('_', '-')); return;
            }
            snapshot = snapshots.capture(held, identity.key().orElseThrow());
        } catch (RuntimeException error) {
            owner.getLogger().log(Level.WARNING, "Cannot capture/identify source for " + id, error);
            messages.send(player, "inspection-failed"); return;
        }
        long job = ++sequence;
        jobs.put(id, job); inFlight.incrementAndGet();
        try {
            platform.async("itemupgrader-value", () -> {
                try {
                    if (!runtime.isCurrent(definition.revision())) return;
                    ValueResult result = values.evaluate(snapshot, definition.value().values(), definition.revision());
                    platform.player(id, online -> {
                        if (!Long.valueOf(job).equals(jobs.get(id))) return;
                        jobs.remove(id);
                        if (!runtime.isCurrent(definition.revision())) { messages.send(online, "quote-stale"); return; }
                        render(online, snapshot, result, detailed);
                    });
                } catch (RuntimeException error) {
                    owner.getLogger().log(Level.WARNING, "Value inspection failed for " + id, error);
                    platform.player(id, online -> {
                        if (Long.valueOf(job).equals(jobs.get(id))) { jobs.remove(id); messages.send(online, "inspection-failed"); }
                    });
                } finally {
                    inFlight.decrementAndGet();
                    // Also release a job that became stale before calculation. Ownership gate avoids late callbacks.
                    if (runtime.state() != RuntimeStore.State.STOPPED) platform.player(id, online -> {
                        if (!runtime.isCurrent(definition.revision()) && Long.valueOf(job).equals(jobs.get(id))) {
                            jobs.remove(id); messages.send(online, "quote-stale");
                        }
                    });
                }
            });
        } catch (RuntimeException rejected) {
            jobs.remove(id); inFlight.decrementAndGet();
            owner.getLogger().log(Level.WARNING, "Platform rejected value task", rejected);
            messages.send(player, "backend-busy");
        }
    }
    private void render(Player player, ItemSnapshot snapshot, ValueResult result, boolean detailed) {
        Map<String, String> context = new HashMap<>();
        context.put("item", snapshot.facts().key().value()); context.put("amount", String.valueOf(snapshot.facts().amount()));
        context.put("fingerprint", snapshot.fingerprint()); context.put("bytes", String.valueOf(snapshot.byteSize()));
        messages.send(player, "value-header", context);
        if (result.quote().isEmpty()) {
            messages.send(player, "value-" + result.status().name().toLowerCase(Locale.ROOT).replace('_', '-'), context);
            if (detailed) messages.send(player, "value-diagnostic", Map.of("detail", result.detail()));
        } else {
            var quote = result.quote().orElseThrow();
            context.put("base", Decimals.display(quote.baseValue())); context.put("unit", Decimals.display(quote.unitValue()));
            context.put("total", Decimals.display(quote.totalValue())); context.put("source", quote.source().name());
            context.put("revision", String.valueOf(quote.revision()));
            messages.send(player, "value-summary", context);
            if (detailed) {
                for (var adjustment : quote.adjustments()) messages.send(player, "value-adjustment", Map.of("modifier", adjustment.id(),
                        "before", Decimals.display(adjustment.before()), "after", Decimals.display(adjustment.after())));
                for (String diagnostic : quote.diagnostics()) messages.send(player, "value-diagnostic", Map.of("detail", diagnostic));
            }
        }
        if (detailed) messages.send(player, "inspect-payload", context);
        messages.send(player, "value-readonly");
    }
    public void forget(UUID id) { jobs.remove(id); }
    public void close() { jobs.clear(); identities.clear(); }
}
