package vn.ledat.itemupgrader.transfer;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import vn.ledat.itemupgrader.transaction.model.AttemptPlan;

/** Exact allow-list. CLEAN means use the fresh target's defaults, NOT wipe the provider's identity. */
public record TransferPolicy(String id, boolean customName, boolean repairCost, Durability durability,
                             Map<String, Integer> enchantments, Map<String, PdcValue.Type> pdc) {
    public enum Durability { TARGET_DEFAULT, DAMAGE_RATIO_CEIL }
    private static final Set<String> RESERVED = Set.of("minecraft", "mmoitems", "mythiclib", "itemsadder", "oraxen", "nexo", "ledatitemupgrader");
    public TransferPolicy {
        AttemptPlan.id(id); Objects.requireNonNull(durability);
        enchantments = Map.copyOf(enchantments); pdc = Map.copyOf(pdc);
        if (enchantments.size() > 64 || pdc.size() > 32) throw new IllegalArgumentException("transfer allow-list too large");
        enchantments.forEach((key, max) -> { key(key); if (max < 1 || max > 255) throw new IllegalArgumentException("transfer enchant cap 1..255"); });
        pdc.forEach((key, type) -> {
            key(key); Objects.requireNonNull(type);
            if (RESERVED.contains(key.substring(0, key.indexOf(':')))) throw new IllegalArgumentException("provider/system PDC namespace cannot be copied: " + key);
        });
    }
    public static String key(String key) {
        if (key == null || key.length() > 128 || !key.matches("[a-z0-9._-]+:[a-z0-9/._-]+")) throw new IllegalArgumentException("invalid metadata key");
        return key;
    }
    public static TransferPolicy clean() { return new TransferPolicy("clean", false, false, Durability.TARGET_DEFAULT, Map.of(), Map.of()); }
    public boolean empty() { return !customName && !repairCost && durability == Durability.TARGET_DEFAULT && enchantments.isEmpty() && pdc.isEmpty(); }
}
