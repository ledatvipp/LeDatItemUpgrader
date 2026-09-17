package vn.ledat.itemupgrader.profile;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.Objects;
import vn.ledat.itemupgrader.cost.CostEntry;
import vn.ledat.itemupgrader.quote.RuleValidation;

/** Outcome terms only. DAMAGE/DOWNGRADE require an explicit OutputRules policy. */
public record RiskProfile(String id, boolean enabled, String formulaId, BigDecimal chanceMultiplier,
                          BigDecimal feeMultiplier, FailureMode failure, List<CostEntry> costs,
                          String permission, Set<String> requiredConditions) {
    public enum FailureMode { DESTROY, KEEP, DAMAGE, DOWNGRADE }
    public RiskProfile {
        RuleValidation.id(id, "profile.id"); RuleValidation.id(formulaId, "profile.formula");
        chanceMultiplier = RuleValidation.multiplier(chanceMultiplier, "profile.chance-multiplier");
        feeMultiplier = RuleValidation.multiplier(feeMultiplier, "profile.fee-multiplier");
        Objects.requireNonNull(failure); costs = List.copyOf(costs);
        if (costs.size() > 32) throw new IllegalArgumentException("profile costs exceed 32 entries");
        RuleValidation.permission(permission); requiredConditions = RuleValidation.ids(requiredConditions, 32, "profile.conditions");
    }
}
