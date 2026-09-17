package vn.ledat.itemupgrader.boost;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.Objects;
import vn.ledat.itemupgrader.chance.ChanceAdjustment;
import vn.ledat.itemupgrader.cost.CostEntry;
import vn.ledat.itemupgrader.quote.RuleValidation;

public record BoostDefinition(String id, boolean enabled, String exclusiveGroup, Set<String> allowedProfiles,
                              BigDecimal chanceMultiplier, BigDecimal bonusPercentagePoints, Protection protection,
                              List<CostEntry> costs, String permission, Set<String> requiredConditions) {
    public enum Protection { NONE, KEEP_SOURCE_ON_FAILURE }
    public BoostDefinition {
        RuleValidation.id(id, "boost.id");
        Objects.requireNonNull(exclusiveGroup); if (!exclusiveGroup.isEmpty()) RuleValidation.id(exclusiveGroup, "boost.exclusive-group");
        allowedProfiles = RuleValidation.ids(allowedProfiles, 64, "boost.allowed-profiles");
        chanceMultiplier = RuleValidation.multiplier(chanceMultiplier, "boost.chance-multiplier");
        bonusPercentagePoints = RuleValidation.percent(bonusPercentagePoints, "boost.percentage-points");
        Objects.requireNonNull(protection); costs = List.copyOf(costs);
        if (costs.size() > 16) throw new IllegalArgumentException("boost costs exceed 16 entries");
        if (chanceMultiplier.compareTo(BigDecimal.ONE) == 0 && bonusPercentagePoints.signum() == 0 && protection == Protection.NONE)
            throw new IllegalArgumentException("boost has no effect");
        RuleValidation.permission(permission); requiredConditions = RuleValidation.ids(requiredConditions, 32, "boost.conditions");
    }
    public ChanceAdjustment adjustment() { return new ChanceAdjustment(id, chanceMultiplier, bonusPercentagePoints); }
}
