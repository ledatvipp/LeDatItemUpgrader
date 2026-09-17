package vn.ledat.itemupgrader.cost;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** Reservation requirements only. No inventory/currency reservation or debit has happened. */
public record CostPlan(List<Line> lines) {
    public record Line(CostResource resource, BigDecimal onAttempt, BigDecimal onSuccess, BigDecimal onFailure) {
        public Line {
            Objects.requireNonNull(resource);
            onAttempt = validate(onAttempt, resource); onSuccess = validate(onSuccess, resource); onFailure = validate(onFailure, resource);
            BigDecimal reserve = onAttempt.add(onSuccess.max(onFailure));
            if (reserve.signum() == 0 || reserve.compareTo(resource.maximum()) > 0) throw new IllegalArgumentException("invalid reservation requirement");
        }
        public BigDecimal reserve() { return onAttempt.add(onSuccess.max(onFailure)).stripTrailingZeros(); }
        public BigDecimal consumed(boolean success) { return onAttempt.add(success ? onSuccess : onFailure).stripTrailingZeros(); }
        private static BigDecimal validate(BigDecimal value, CostResource resource) {
            vn.ledat.itemupgrader.quote.RuleValidation.bounded(value, resource.maximum(), "cost.plan");
            try { return value.setScale(resource.scale(), java.math.RoundingMode.UNNECESSARY).stripTrailingZeros(); }
            catch (ArithmeticException error) { throw new IllegalArgumentException("plan resource precision invalid", error); }
        }
    }
    public CostPlan {
        if (lines.size() > 32) throw new IllegalArgumentException("too many cost resources");
        lines = lines.stream().sorted(java.util.Comparator.comparing(Line::resource)).toList();
        if (lines.stream().map(Line::resource).distinct().count() != lines.size()) throw new IllegalArgumentException("duplicate cost resource");
    }
    public boolean hasFailureLoss() { return lines.stream().anyMatch(line -> line.consumed(false).signum() > 0); }
}
