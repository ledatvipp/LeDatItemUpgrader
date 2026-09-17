package vn.ledat.itemupgrader.chance;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import vn.ledat.itemupgrader.quote.RuleValidation;
import vn.ledat.itemupgrader.util.Decimals;

/** Stateless exact math; no outcome is generated during preview. */
public final class ChanceCalculator {
    private static final ExactFraction HUNDRED = ExactFraction.of(new BigDecimal("100"));
    public ChanceQuote calculate(BigDecimal sourceTotal, BigDecimal targetTotal, ChanceFormula formula,
                                 BigDecimal profileMultiplier, List<ChanceAdjustment> permissionBonuses,
                                 List<ChanceAdjustment> boosts, BigDecimal minimumPercent, BigDecimal maximumPercent) {
        return calculate(sourceTotal,targetTotal,formula,profileMultiplier,permissionBonuses,boosts,minimumPercent,maximumPercent,BigDecimal.ZERO);
    }
    /** Pity percentage points are added after boosters but BEFORE the final clamp/ticket quantization. */
    public ChanceQuote calculate(BigDecimal sourceTotal, BigDecimal targetTotal, ChanceFormula formula,
            BigDecimal profileMultiplier,List<ChanceAdjustment> permissionBonuses,List<ChanceAdjustment> boosts,
            BigDecimal minimumPercent,BigDecimal maximumPercent,BigDecimal pityPoints) {
        RuleValidation.percent(pityPoints,"pity.points");
        Decimals.positive(sourceTotal, "chance.source-total"); Decimals.positive(targetTotal, "chance.target-total");
        if (sourceTotal.compareTo(targetTotal) >= 0) throw new IllegalArgumentException("target must be worth more than source total");
        Objects.requireNonNull(formula, "formula");
        RuleValidation.multiplier(profileMultiplier, "profile.chance-multiplier");
        RuleValidation.percent(minimumPercent, "minimum-percent"); RuleValidation.percent(maximumPercent, "maximum-percent");
        ExactFraction minimum = ExactFraction.of(minimumPercent).divide(HUNDRED);
        ExactFraction maximum = ExactFraction.of(maximumPercent).divide(HUNDRED);
        if (minimum.compareTo(maximum) > 0 || minimum.ceilTickets() > maximum.floorTickets())
            throw new IllegalArgumentException("chance bounds contain no representable ticket");
        validateAdjustments(permissionBonuses, 16); validateAdjustments(boosts, 8);
        ExactFraction ratio = ExactFraction.of(sourceTotal).divide(ExactFraction.of(targetTotal));
        ExactFraction value = base(formula, ratio);
        List<ChanceQuote.Step> steps = new ArrayList<>();
        steps.add(step("formula", ratio, value));
        ExactFraction before = value; value = value.multiply(ExactFraction.of(profileMultiplier));
        steps.add(step("profile", before, value));
        value = adjustments("permissions", value, permissionBonuses, steps);
        value = adjustments("boosts", value, boosts, steps);
        if(pityPoints.signum()!=0) { before=value;value=value.add(ExactFraction.of(pityPoints).divide(HUNDRED));steps.add(step("pity-points",before,value)); }
        before = value;
        if (value.compareTo(minimum) < 0) value = minimum;
        if (value.compareTo(maximum) > 0) value = maximum;
        boolean clamped = before.compareTo(value) != 0;
        steps.add(step("clamp", before, value));
        long tickets = Math.max(minimum.ceilTickets(), Math.min(maximum.floorTickets(), value.floorTickets()));
        Probability probability = new Probability(tickets);
        ExactFraction actual = ExactFraction.of(probability.fraction());
        steps.add(step("ticket-grid", value, actual));
        return new ChanceQuote(probability, ratio.decimal(18), steps, clamped, actual.compareTo(value) != 0);
    }
    private static ExactFraction adjustments(String name, ExactFraction value, List<ChanceAdjustment> adjustments, List<ChanceQuote.Step> steps) {
        ExactFraction before = value;
        // Exact products + sums make selection/list order immaterial. Permission points are applied before boost multipliers.
        ExactFraction product = ExactFraction.ONE, points = ExactFraction.ZERO;
        for (ChanceAdjustment adjustment : adjustments) {
            product = product.multiply(ExactFraction.of(adjustment.multiplier()));
            points = points.add(ExactFraction.of(adjustment.bonusPercentagePoints()).divide(HUNDRED));
        }
        value = value.multiply(product); steps.add(step(name + "-multiply", before, value));
        before = value; value = value.add(points); steps.add(step(name + "-points", before, value));
        return value;
    }
    private static void validateAdjustments(List<ChanceAdjustment> adjustments, int maximum) {
        if (adjustments.size() > maximum) throw new IllegalArgumentException("too many chance adjustments");
        var ids = new HashSet<String>();
        for (var adjustment : adjustments) if (!ids.add(adjustment.id())) throw new IllegalArgumentException("duplicate adjustment id");
    }
    private static ChanceQuote.Step step(String stage, ExactFraction before, ExactFraction after) {
        return new ChanceQuote.Step(stage, before.multiply(HUNDRED).decimal(12), after.multiply(HUNDRED).decimal(12));
    }
    private static ExactFraction base(ChanceFormula formula, ExactFraction ratio) {
        return switch (formula) {
            case ChanceFormula.Ratio rule -> ratio.multiply(ExactFraction.of(rule.multiplier()));
            case ChanceFormula.Power rule -> ratio.power(rule.exponent()).multiply(ExactFraction.of(rule.multiplier()));
            case ChanceFormula.Table rule -> {
                ChanceFormula.Point selected = rule.points().getFirst();
                for (var point : rule.points()) {
                    if (ratio.compareTo(ExactFraction.of(point.ratio())) < 0) break;
                    selected = point;
                }
                yield ExactFraction.of(selected.percent()).divide(HUNDRED);
            }
            case ChanceFormula.Curve rule -> {
                ExactFraction result = ExactFraction.ZERO;
                for (int i = 1; i < rule.points().size(); i++) {
                    var left = rule.points().get(i - 1); var right = rule.points().get(i);
                    ExactFraction x1 = ExactFraction.of(left.ratio()), x2 = ExactFraction.of(right.ratio());
                    if (ratio.compareTo(x2) > 0) continue;
                    ExactFraction y1 = ExactFraction.of(left.percent()), y2 = ExactFraction.of(right.percent());
                    result = y1.add(ratio.subtract(x1).divide(x2.subtract(x1)).multiply(y2.subtract(y1))).divide(HUNDRED);
                    break;
                }
                yield result;
            }
        };
    }
}
