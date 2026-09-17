package vn.ledat.itemupgrader.cost;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import vn.ledat.itemupgrader.quote.RuleValidation;

/** Merge before rounding; outcome-exclusive costs require attempt + MAX(success,failure), not their sum. */
public final class CostPlanner {
    public CostPlan plan(List<CostEntry> profileCosts, BigDecimal profileFeeMultiplier, List<CostEntry> boostCosts) {
        RuleValidation.multiplier(profileFeeMultiplier, "profile.fee-multiplier");
        if (profileCosts.size() > 32 || boostCosts.size() > 128) throw new IllegalArgumentException("too many cost entries");
        var sums = new TreeMap<CostResource, BigDecimal[]>();
        for (CostEntry cost : profileCosts) merge(sums, cost, profileFeeMultiplier);
        for (CostEntry cost : boostCosts) merge(sums, cost, BigDecimal.ONE);
        var result = new ArrayList<CostPlan.Line>();
        for (var entry : sums.entrySet()) {
            BigDecimal[] amounts = entry.getValue(); int scale = entry.getKey().scale();
            result.add(new CostPlan.Line(entry.getKey(), amounts[0].setScale(scale, RoundingMode.CEILING),
                    amounts[1].setScale(scale, RoundingMode.CEILING), amounts[2].setScale(scale, RoundingMode.CEILING)));
        }
        return new CostPlan(result);
    }
    private static void merge(TreeMap<CostResource, BigDecimal[]> sums, CostEntry cost, BigDecimal multiplier) {
        BigDecimal[] values = sums.computeIfAbsent(cost.resource(), unused -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
        int index = cost.when().ordinal();
        values[index] = values[index].add(cost.amount().multiply(multiplier));
    }
}
