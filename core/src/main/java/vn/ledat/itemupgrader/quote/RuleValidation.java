package vn.ledat.itemupgrader.quote;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import vn.ledat.itemupgrader.util.Decimals;

/** Shared bounded config primitives, not a player-supplied expression language. */
public final class RuleValidation {
    private RuleValidation() {}
    public static String id(String value, String label) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9_.-]{0,63}"))
            throw new IllegalArgumentException(label + ": expected lowercase identifier, 1..64 characters");
        return value;
    }
    public static String permission(String value) {
        Objects.requireNonNull(value, "permission");
        if (!value.isEmpty() && !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
            throw new IllegalArgumentException("invalid permission node");
        return value;
    }
    public static Set<String> ids(Collection<String> values, int maximum, String label) {
        if (values.size() > maximum) throw new IllegalArgumentException(label + ": too many entries");
        Set<String> result = new HashSet<>();
        for (String value : values) if (!result.add(id(value, label)))
            throw new IllegalArgumentException(label + ": duplicate id " + value);
        return Set.copyOf(result);
    }
    public static BigDecimal bounded(BigDecimal value, BigDecimal maximum, String label) {
        Decimals.nonNegative(value, label);
        if (value.compareTo(maximum) > 0) throw new IllegalArgumentException(label + ": exceeds maximum " + maximum);
        return value.stripTrailingZeros();
    }
    public static BigDecimal percent(BigDecimal value, String label) { return bounded(value, new BigDecimal("100"), label); }
    public static BigDecimal multiplier(BigDecimal value, String label) {
        Decimals.positive(value, label);
        return bounded(value, BigDecimal.TEN, label);
    }
}
