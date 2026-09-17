package vn.ledat.itemupgrader.quote;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.Objects;
import vn.ledat.itemupgrader.catalog.TargetSelectionService;
import vn.ledat.itemupgrader.chance.ChanceQuote;
import vn.ledat.itemupgrader.chance.Probability;
import vn.ledat.itemupgrader.cost.CostPlan;
import vn.ledat.itemupgrader.cost.ResourceAssessment;
import vn.ledat.itemupgrader.profile.RiskProfile;

/** Server-memory preview only. Neither quote ID nor selection is a client-usable authorization token. */
public record UpgradeQuote(UUID quoteId, QuoteRequest request, TargetSelectionService.Selection selection,
                           BigDecimal sourceTotal, BigDecimal targetTotal, ChanceQuote chance,
                           Terms terms, ResourceAssessment resources) {
    public record Terms(String profileId, List<String> boosts, List<String> permissionBonuses, Probability probability,
                        RiskProfile.FailureMode failure, CostPlan costs, java.util.Optional<vn.ledat.itemupgrader.output.OutputSpec> output, java.util.Optional<vn.ledat.itemupgrader.pity.PityStamp> pity) {
        public Terms(String profileId, List<String> boosts, List<String> permissionBonuses, Probability probability,
                     RiskProfile.FailureMode failure, CostPlan costs, java.util.Optional<vn.ledat.itemupgrader.output.OutputSpec> output) {
            this(profileId, boosts, permissionBonuses, probability, failure, costs, output, java.util.Optional.empty());
        }
        public Terms(String profileId, List<String> boosts, List<String> permissionBonuses, Probability probability,
                     RiskProfile.FailureMode failure, CostPlan costs) {
            this(profileId, boosts, permissionBonuses, probability, failure, costs, java.util.Optional.empty());
        }
        public Terms {
            boosts = List.copyOf(boosts); permissionBonuses = List.copyOf(permissionBonuses);
            Objects.requireNonNull(output); Objects.requireNonNull(failure); Objects.requireNonNull(pity);
            if (pity.isPresent() && (output.isEmpty() || failure != RiskProfile.FailureMode.DESTROY))
                throw new IllegalArgumentException("pity requires explicit output and DESTROY loss; protected/damaged attempts are excluded");
            if (output.isPresent() && output.orElseThrow().failure().mode() != failure)
                throw new IllegalArgumentException("output policy/failure terms mismatch");
            if (output.isEmpty() && failure != RiskProfile.FailureMode.DESTROY && failure != RiskProfile.FailureMode.KEEP)
                throw new IllegalArgumentException("legacy terms cannot use new failure modes");
        }
    }
    public UpgradeQuote {
        Objects.requireNonNull(quoteId); Objects.requireNonNull(request); Objects.requireNonNull(selection);
        Objects.requireNonNull(sourceTotal); Objects.requireNonNull(targetTotal); Objects.requireNonNull(chance);
        Objects.requireNonNull(terms); Objects.requireNonNull(resources);
        if (!request.sessionId().equals(selection.sessionId()) || !request.targetId().equals(selection.targetId())
                || !chance.probability().equals(terms.probability())) throw new IllegalArgumentException("quote binding mismatch");
    }
}
