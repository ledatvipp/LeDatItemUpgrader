package vn.ledat.itemupgrader.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.regex.Pattern;

/** Finite, bounded, non-scientific decimals for administrator supplied values. */
public final class Decimals {
    private static final Pattern PLAIN = Pattern.compile("(?:0|[1-9][0-9]{0,17})(?:\\.[0-9]{1,8})?");
    private Decimals() {}
    public static BigDecimal parse(String raw, String path) {
        Objects.requireNonNull(raw, path);
        if (!PLAIN.matcher(raw).matches()) throw new IllegalArgumentException(path + ": invalid decimal");
        return new BigDecimal(raw);
    }
    public static BigDecimal nonNegative(BigDecimal value, String label) {
        Objects.requireNonNull(value, label);
        if (value.signum() < 0 || value.precision() > 30 || value.scale() > 12 || value.scale() < -18)
            throw new IllegalArgumentException(label + ": out of range");
        return value;
    }
    public static BigDecimal positive(BigDecimal value, String label) {
        nonNegative(value, label);
        if (value.signum() == 0) throw new IllegalArgumentException(label + ": must be positive");
        return value;
    }
    public static BigDecimal floor(BigDecimal value, int scale) {
        return value.setScale(scale, RoundingMode.DOWN);
    }
    public static String display(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
}
