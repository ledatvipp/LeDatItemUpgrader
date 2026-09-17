package vn.ledat.itemupgrader.value;

import java.math.BigDecimal;
import java.util.Objects;
import vn.ledat.itemupgrader.item.ItemFacts;
import vn.ledat.itemupgrader.item.ItemKey;
import vn.ledat.itemupgrader.util.Decimals;

/** Empty match fields are wildcards. Highest priority wins; equal-priority ambiguity is rejected. */
public record ValueRule(String id, int priority, String provider, ItemKey exactKey, String rarity,
                        BigDecimal baseValue) {
    public ValueRule {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(rarity, "rarity");
        if (!id.matches("[a-z0-9_-]{1,64}") || Math.abs((long) priority) > 100_000)
            throw new IllegalArgumentException("invalid rule id/priority");
        if (!provider.isEmpty() && !java.util.Set.of("minecraft", "mmoitems", "itemsadder", "oraxen", "nexo").contains(provider))
            throw new IllegalArgumentException("invalid rule provider");
        if (!rarity.matches("[A-Za-z0-9_-]{0,64}")) throw new IllegalArgumentException("invalid rule rarity");
        if (exactKey != null && !provider.isEmpty() && !exactKey.provider().equals(provider))
            throw new IllegalArgumentException("conflicting rule provider");
        if (exactKey == null && provider.isEmpty() && rarity.isEmpty())
            throw new IllegalArgumentException("unbounded wildcard value rule is not permitted");
        Decimals.positive(baseValue, "rule base value");
    }
    public boolean matches(ItemFacts item) {
        return (exactKey == null || exactKey.equals(item.key()))
                && (provider.isEmpty() || provider.equals(item.key().provider()))
                && (rarity.isEmpty() || rarity.equals(item.rarity()));
    }
}
