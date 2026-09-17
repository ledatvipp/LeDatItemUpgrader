package vn.ledat.itemupgrader.chance;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** Breakdown decimals are explanatory; winningTickets is the sole sampling threshold. */
public record ChanceQuote(Probability probability, BigDecimal sourceTargetRatio, List<Step> steps,
                          boolean clamped, boolean quantized) {
    public record Step(String stage, BigDecimal beforePercent, BigDecimal afterPercent) {}
    public ChanceQuote { Objects.requireNonNull(probability); steps = List.copyOf(steps); }
}
