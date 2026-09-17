package vn.ledat.itemupgrader.value;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ValueResult(Status status, Optional<Quote> quote, String detail) {
    public enum Status { AVAILABLE, UNKNOWN_VALUE, BLOCKED_ITEM, UNSAFE_METADATA, UNSAFE_ENCHANTMENT,
        AMOUNT_LIMIT, SNAPSHOT_LIMIT, VALUE_LIMIT, ZERO_AFTER_ROUNDING, RECIPE_LIMIT, AMBIGUOUS_RULE }
    public enum Source { MANUAL, ITEM_RULE, PROVIDER, RECIPE, RARITY }
    public record Adjustment(String id, BigDecimal before, BigDecimal after) {}
    public record Quote(long revision, String fingerprint, Source source, String baseRule,
                        BigDecimal baseValue, BigDecimal unitValue, BigDecimal totalValue,
                        List<Adjustment> adjustments, List<String> diagnostics) {
        public Quote { adjustments = List.copyOf(adjustments); diagnostics = List.copyOf(diagnostics); }
    }
    public ValueResult {
        Objects.requireNonNull(status, "status"); Objects.requireNonNull(quote, "quote");
        Objects.requireNonNull(detail, "detail");
        if ((status == Status.AVAILABLE) != quote.isPresent()) throw new IllegalArgumentException("inconsistent value result");
    }
    public static ValueResult fail(Status status, String detail) { return new ValueResult(status, Optional.empty(), detail); }
    public static ValueResult success(Quote quote) { return new ValueResult(Status.AVAILABLE, Optional.of(quote), ""); }
}
