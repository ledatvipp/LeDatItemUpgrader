package vn.ledat.itemupgrader.catalog;

import java.math.BigDecimal;
import vn.ledat.itemupgrader.util.Decimals;

/** Ratios compare TOTAL source and TOTAL target value. They are not success chances. */
public record CatalogSettings(BigDecimal minimumRatio, BigDecimal maximumRatio,
                              BigDecimal preferredRatio, int pageSize, int recommendationCount,
                              int maximumTargets, int maximumPaths, boolean allowUnpathed) {
    public CatalogSettings {
        Decimals.positive(minimumRatio, "catalog.minimum-ratio");
        Decimals.positive(maximumRatio, "catalog.maximum-ratio");
        Decimals.positive(preferredRatio, "catalog.preferred-ratio");
        if (minimumRatio.compareTo(BigDecimal.ONE) < 0 || maximumRatio.compareTo(minimumRatio) < 0
                || maximumRatio.compareTo(new BigDecimal("1000000")) > 0
                || preferredRatio.compareTo(minimumRatio) < 0 || preferredRatio.compareTo(maximumRatio) > 0)
            throw new IllegalArgumentException("catalog: require 1 <= minimum <= preferred <= maximum <= 1000000");
        if (pageSize < 1 || pageSize > 45 || recommendationCount < 1 || recommendationCount > 10
                || maximumTargets < 1 || maximumTargets > 10000 || maximumPaths < 1 || maximumPaths > 2000)
            throw new IllegalArgumentException("catalog: invalid size/count limit");
    }
    public static CatalogSettings defaults() {
        return new CatalogSettings(new BigDecimal("1.25"), new BigDecimal("12"),
                new BigDecimal("2"), 12, 3, 2048, 512, true);
    }
}
