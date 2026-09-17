package vn.ledat.itemupgrader.boost;

import java.math.BigDecimal;
import java.util.Set;
import vn.ledat.itemupgrader.chance.ChanceAdjustment;
import vn.ledat.itemupgrader.quote.RuleValidation;

/** Highest granted priority wins within each group. Distinct groups stack in the declared math stages. */
public record PermissionBonus(String id, boolean enabled, String group, int priority, String permission,
                              Set<String> requiredConditions, BigDecimal chanceMultiplier, BigDecimal bonusPercentagePoints) {
    public PermissionBonus {
        RuleValidation.id(id, "permission-bonus.id"); RuleValidation.id(group, "permission-bonus.group");
        RuleValidation.permission(permission);
        if (permission.isEmpty() || priority < -100000 || priority > 100000) throw new IllegalArgumentException("invalid permission bonus gate/priority");
        requiredConditions = RuleValidation.ids(requiredConditions, 32, "permission-bonus.conditions");
        chanceMultiplier = RuleValidation.multiplier(chanceMultiplier, "permission-bonus.chance-multiplier");
        bonusPercentagePoints = RuleValidation.percent(bonusPercentagePoints, "permission-bonus.percentage-points");
    }
    public ChanceAdjustment adjustment() { return new ChanceAdjustment(id, chanceMultiplier, bonusPercentagePoints); }
}
