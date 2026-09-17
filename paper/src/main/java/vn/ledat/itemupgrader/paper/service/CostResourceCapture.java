package vn.ledat.itemupgrader.paper.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import vn.ledat.itemupgrader.cost.CostResource;
import vn.ledat.itemupgrader.cost.ResourceSnapshot;
import vn.ledat.itemupgrader.item.ItemKey;
import vn.ledat.itemupgrader.paper.item.PaperItemSnapshotFactory;
import vn.ledat.itemupgrader.paper.item.PlatformIdentityIndex;
import vn.ledat.itemupgrader.paper.platform.PlatformAccess;
import vn.ledat.itemupgrader.runtime.RuntimeStore;
import vn.ledat.itemupgrader.runtime.UpgraderRuntime;

/** Exact-template-only cost matching. Does NOT equate every custom/enchanted item sharing a Material/ID. */
public final class CostResourceCapture {
    private final JavaPlugin owner;
    private final PlatformAccess platform;
    private final PlatformIdentityIndex identities;
    private final PaperItemSnapshotFactory snapshots = new PaperItemSnapshotFactory();
    private long lastWarning;
    private boolean warned;
    public CostResourceCapture(JavaPlugin owner, PlatformAccess platform, PlatformIdentityIndex identities) {
        this.owner = owner; this.platform = platform; this.identities = identities;
    }
    public ResourceSnapshot capture(Player player, RuntimeStore.Snapshot<UpgraderRuntime> runtime, Set<CostResource> requested, int sourceSlot) {
        if (requested.size() > 32) throw new IllegalArgumentException("too many requested resources");
        Map<CostResource, BigDecimal> balances = new HashMap<>(); Map<ItemKey, ItemStack> templates = new HashMap<>();
        long bytes = 0;
        for (CostResource resource : requested.stream().sorted().toList()) {
            try {
                if (resource.kind() == CostResource.Kind.CURRENCY) {
                    var balance = platform.balance(player, resource.key());
                    if (balance.isPresent()) {
                        // Validate one observation before publishing. Invalid/negative/overflow results become unavailable, never zero/free.
                        new ResourceSnapshot(player.getUniqueId(), Map.of(resource, balance.get()), Set.of(), List.of(), Set.of(sourceSlot));
                        balances.put(resource, balance.get().stripTrailingZeros());
                    }
                } else {
                    ItemKey key = ItemKey.of(resource.key()); var created = platform.create(key.value());
                    if (created.isEmpty() || created.get().getType().isAir()) continue;
                    ItemStack template = created.get().clone(); template.setAmount(1);
                    var identity = identities.identify(template, runtime);
                    if (identity.key().isEmpty() || !identity.key().orElseThrow().equals(key)) continue;
                    var snapshot = snapshots.capture(template, key);
                    if (!snapshot.facts().risks().isEmpty() || snapshot.byteSize() > runtime.value().values().limits().maxSnapshotBytes()) continue;
                    bytes += snapshot.byteSize();
                    if (bytes > 1_048_576) throw new IllegalArgumentException("cost template budget exceeded");
                    templates.put(key, template);
                }
            } catch (RuntimeException | LinkageError error) {
                warn(error); // Provider exception disables only that resource in this observation.
            }
        }
        var supplies = new ArrayList<ResourceSnapshot.ItemSupply>();
        ItemStack[] inventory = player.getInventory().getStorageContents();
        for (int slot = 0; slot < Math.min(36, inventory.length); slot++) {
            if (slot == sourceSlot) continue;
            ItemStack item = inventory[slot];
            if (item == null || item.getType().isAir() || item.getAmount() < 1) continue;
            for (var entry : templates.entrySet()) {
                if (!entry.getValue().isSimilar(item)) continue;
                var identity = identities.identify(item, runtime);
                if (identity.key().isEmpty() || !identity.key().orElseThrow().equals(entry.getKey())) continue;
                var snapshot = snapshots.capture(item, entry.getKey());
                if (!snapshot.facts().risks().isEmpty() || snapshot.byteSize() > runtime.value().values().limits().maxSnapshotBytes()) continue;
                bytes += snapshot.byteSize();
                if (bytes > 1_048_576) throw new IllegalArgumentException("cost snapshot budget exceeded");
                supplies.add(new ResourceSnapshot.ItemSupply(slot, entry.getKey(), item.getAmount(), snapshot.fingerprint()));
                break; // One physical slot can supply exactly one matcher. All usage is aggregated later.
            }
        }
        return new ResourceSnapshot(player.getUniqueId(), balances, Set.copyOf(templates.keySet()), supplies, Set.of(sourceSlot));
    }
    private void warn(Throwable error) {
        long now = System.nanoTime();
        if (warned && now - lastWarning < 30_000_000_000L) return;
        warned = true; lastWarning = now;
        owner.getLogger().warning("Cannot capture a fee/boost resource; marking unavailable, no debit performed; error-type="
                + error.getClass().getName()); // Provider exception text may contain credentials; keep it out of user-facing logs.
    }
}
