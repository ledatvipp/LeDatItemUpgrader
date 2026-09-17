package vn.ledat.itemupgrader.cost;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Locale;
import vn.ledat.itemupgrader.item.ItemKey;

/** Currency precision is a pinned pricing policy, not inferred from a floating provider balance. */
public record CostResource(Kind kind, String key) implements Comparable<CostResource> {
    public enum Kind { CURRENCY, ITEM }
    public enum Currency { VAULT, PLAYERPOINTS }
    public CostResource {
        Objects.requireNonNull(kind); Objects.requireNonNull(key);
        if (kind == Kind.ITEM) ItemKey.of(key);
        else if (!key.equals("vault") && !key.equals("playerpoints")) throw new IllegalArgumentException("unsupported currency");
    }
    public static CostResource currency(Currency currency) { return new CostResource(Kind.CURRENCY, currency.name().toLowerCase(Locale.ROOT)); }
    public static CostResource item(ItemKey key) { return new CostResource(Kind.ITEM, key.value()); }
    public int scale() { return kind == Kind.CURRENCY && key.equals("vault") ? 2 : 0; }
    public BigDecimal maximum() {
        if (kind == Kind.ITEM) return new BigDecimal("65536");
        return key.equals("playerpoints") ? BigDecimal.valueOf(Integer.MAX_VALUE) : new BigDecimal("1000000000000");
    }
    public String canonical() { return kind.name() + ":" + key; }
    @Override public int compareTo(CostResource other) { return canonical().compareTo(other.canonical()); }
}
