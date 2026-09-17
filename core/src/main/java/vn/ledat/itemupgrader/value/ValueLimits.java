package vn.ledat.itemupgrader.value;

import java.math.BigDecimal;
import vn.ledat.itemupgrader.util.Decimals;

public record ValueLimits(int scale, int maxAmount, int maxSnapshotBytes, int maxEnchantLevel,
                          BigDecimal maxUnitValue, BigDecimal maxTotalValue,
                          int recipeMaxDepth, int recipeMaxNodes) {
    public ValueLimits {
        if (scale < 0 || scale > 8 || maxAmount < 1 || maxAmount > 4096
                || maxSnapshotBytes < 256 || maxSnapshotBytes > 1_048_576
                || maxEnchantLevel < 1 || maxEnchantLevel > 255
                || recipeMaxDepth < 1 || recipeMaxDepth > 64 || recipeMaxNodes < 1 || recipeMaxNodes > 100_000)
            throw new IllegalArgumentException("invalid value limits");
        Decimals.positive(maxUnitValue, "max unit value");
        Decimals.positive(maxTotalValue, "max total value");
        if (maxTotalValue.compareTo(maxUnitValue) < 0) throw new IllegalArgumentException("max total below max unit");
    }
    public static ValueLimits defaults() {
        return new ValueLimits(6, 64, 65536, 10, new BigDecimal("1000000000000"),
                new BigDecimal("64000000000000"), 24, 2048);
    }
}
