package vn.ledat.itemupgrader.cost;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import vn.ledat.itemupgrader.quote.RuleValidation;
import vn.ledat.itemupgrader.util.Decimals;

public record CostEntry(CostResource resource, BigDecimal amount, ConsumeWhen when) {
    public enum ConsumeWhen { ON_ATTEMPT, ON_SUCCESS, ON_FAILURE }
    public CostEntry {
        Objects.requireNonNull(resource); Objects.requireNonNull(when);
        Decimals.positive(amount, "cost.amount");
        amount = RuleValidation.bounded(amount, resource.maximum(), "cost.amount");
        try { amount.setScale(resource.scale(), RoundingMode.UNNECESSARY); }
        catch (ArithmeticException invalid) { throw new IllegalArgumentException("cost.amount: raw amount exceeds resource precision", invalid); }
    }
}
