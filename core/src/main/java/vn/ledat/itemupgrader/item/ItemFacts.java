package vn.ledat.itemupgrader.item;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Detached, immutable facts. Only trusted adapters may populate rarity and identity. */
public record ItemFacts(ItemKey key, int amount, int damage, int maximumDamage,
                        Map<String, Integer> enchantments, String rarity, Set<Risk> risks) {
    public enum Risk { NESTED_CONTENTS, UNKNOWN_CUSTOM_METADATA, ILLEGAL_STACK, IDENTITY_UNVERIFIED }
    public ItemFacts {
        Objects.requireNonNull(key, "key");
        if (amount < 1 || amount > 4096) throw new IllegalArgumentException("amount outside hard limit");
        if (maximumDamage < 0 || damage < 0 || damage > maximumDamage)
            throw new IllegalArgumentException("invalid durability");
        enchantments = Map.copyOf(enchantments);
        if (enchantments.size() > 128) throw new IllegalArgumentException("too many enchantments");
        enchantments.forEach((id, level) -> {
            if (id == null || id.length() > 128 || level == null || level < 1 || level > 32767)
                throw new IllegalArgumentException("invalid enchantment");
        });
        rarity = Objects.requireNonNull(rarity, "rarity");
        if (!rarity.matches("[A-Za-z0-9_-]{0,64}")) throw new IllegalArgumentException("invalid rarity");
        risks = Set.copyOf(risks);
    }
    public static ItemFacts clean(ItemKey key, int amount) {
        return new ItemFacts(key, amount, 0, 0, Map.of(), "", Set.of());
    }
}
