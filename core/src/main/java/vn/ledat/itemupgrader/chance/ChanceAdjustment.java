package vn.ledat.itemupgrader.chance;

import java.math.BigDecimal;
import vn.ledat.itemupgrader.quote.RuleValidation;

public record ChanceAdjustment(String id, BigDecimal multiplier, BigDecimal bonusPercentagePoints) {
    public ChanceAdjustment {
        RuleValidation.id(id, "adjustment.id");
        multiplier = RuleValidation.multiplier(multiplier, "adjustment.multiplier");
        bonusPercentagePoints = RuleValidation.percent(bonusPercentagePoints, "adjustment.percentage-points");
    }
}
