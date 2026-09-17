package vn.ledat.itemupgrader.paper.item;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bukkit.inventory.ItemStack;
import vn.ledat.itemupgrader.item.ItemKey;
import vn.ledat.itemupgrader.paper.platform.PlatformAccess;
import vn.ledat.itemupgrader.runtime.RuntimeStore;
import vn.ledat.itemupgrader.runtime.UpgraderRuntime;

/**
 * Paper-thread-only, bounded probe index for configured custom keys.
 * The guide does not specify CustomItemKey accessors. We deliberately do NOT parse toString()
 * or invent getId()/asString(). Opaque key equality is self-checked against cloned templates.
 * Replace this with the real canonical accessor once the actual API artifact is reviewed.
 */
public final class PlatformIdentityIndex {
    public enum Status { IDENTIFIED, WARMING, UNKNOWN, PROVIDER_UNAVAILABLE, AMBIGUOUS }
    public record Result(Status status, Optional<ItemKey> key) {}
    private final PlatformAccess platform;
    private final Map<Object, ItemKey> custom = new HashMap<>();
    private final Map<ItemKey, Object> vanilla = new HashMap<>();
    private final Set<Object> ambiguous = new HashSet<>();
    private long revision = -1;
    private int next;
    public PlatformIdentityIndex(PlatformAccess platform) { this.platform = platform; }
    public Result identify(ItemStack stack, RuntimeStore.Snapshot<UpgraderRuntime> snapshot) {
        if (snapshot.revision() != revision) { clear(); revision = snapshot.revision(); }
        Optional<Object> actual = platform.identity(stack);
        if (actual.isEmpty()) return result(Status.PROVIDER_UNAVAILABLE, null);
        ItemKey vanillaKey = ItemKey.of(stack.getType().getKey().toString());
        Object vanillaIdentity = vanilla.get(vanillaKey);
        if (vanillaIdentity == null) {
            vanillaIdentity = verifiedTemplate(vanillaKey).orElse(null);
            if (vanillaIdentity != null) vanilla.put(vanillaKey, vanillaIdentity);
        }
        // Native material is accepted ONLY if Platform recognizes the same identity as its clean template.
        if (actual.get().equals(vanillaIdentity)) return result(Status.IDENTIFIED, vanillaKey);
        warm(snapshot);
        var keys = snapshot.value().customIdentityKeys();
        if (ambiguous.contains(actual.get())) return result(Status.AMBIGUOUS, null);
        ItemKey key = custom.get(actual.get());
        // Wait until all candidates have been checked so a late alias cannot invalidate an earlier match.
        if (next < keys.size()) return result(Status.WARMING, null);
        return key == null ? result(Status.UNKNOWN, null) : result(Status.IDENTIFIED, key);
    }
    /** One bounded batch per invocation. Paper owner thread only; no guessed canonical-key accessor. */
    public boolean warm(RuntimeStore.Snapshot<UpgraderRuntime> snapshot) {
        if (snapshot.revision() != revision) { clear(); revision = snapshot.revision(); }
        int budget = snapshot.value().identityProbeBatch();
        var keys = snapshot.value().customIdentityKeys();
        while (next < keys.size() && budget-- > 0) {
            ItemKey key = keys.get(next++);
            Optional<Object> template = verifiedTemplate(key);
            if (template.isEmpty()) continue;
            ItemKey previous = custom.putIfAbsent(template.get(), key);
            if (previous != null && !previous.equals(key)) ambiguous.add(template.get());
        }
        return next >= keys.size();
    }
    private Optional<Object> verifiedTemplate(ItemKey key) {
        Optional<ItemStack> template = platform.create(key.value());
        if (template.isEmpty()) return Optional.empty();
        Optional<Object> a = platform.identity(template.get());
        Optional<Object> b = platform.identity(template.get().clone());
        return a.isPresent() && a.equals(b) ? a : Optional.empty();
    }
    private static Result result(Status status, ItemKey key) { return new Result(status, Optional.ofNullable(key)); }
    public void clear() { custom.clear(); vanilla.clear(); ambiguous.clear(); next = 0; revision = -1; }
}
