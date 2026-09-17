package vn.ledat.itemupgrader.quote;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Objects;

public record QuoteSettings(String defaultProfile, BigDecimal minimumPercent, BigDecimal maximumPercent,
                            int maximumSelectedBoosts, Duration lifetime, boolean allowFreeProtection) {
    public QuoteSettings {
        RuleValidation.id(defaultProfile, "default-profile");
        minimumPercent = RuleValidation.percent(minimumPercent, "minimum-percent");
        maximumPercent = RuleValidation.percent(maximumPercent, "maximum-percent");
        // Percent * 10^7 == winning tickets. Reject bounds which leave no possible integer threshold.
        if (minimumPercent.compareTo(maximumPercent) > 0
                || minimumPercent.movePointRight(7).setScale(0, RoundingMode.CEILING).compareTo(maximumPercent.movePointRight(7).setScale(0, RoundingMode.FLOOR)) > 0)
            throw new IllegalArgumentException("chance bounds contain no representable probability");
        Objects.requireNonNull(lifetime);
        if (lifetime.compareTo(Duration.ofSeconds(1)) < 0 || lifetime.compareTo(Duration.ofMinutes(5)) > 0
                || maximumSelectedBoosts < 0 || maximumSelectedBoosts > 8) throw new IllegalArgumentException("invalid quote lifetime/boost limit");
    }
    public static QuoteSettings defaults() { return new QuoteSettings("standard", new BigDecimal("0.1"), new BigDecimal("90"), 4, Duration.ofSeconds(30), false); }
}
