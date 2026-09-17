package vn.ledat.itemupgrader.quote;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.ledat.itemupgrader.boost.BoostDefinition;
import vn.ledat.itemupgrader.boost.PermissionBonus;
import vn.ledat.itemupgrader.catalog.CatalogAccess;
import vn.ledat.itemupgrader.catalog.CatalogIndex;
import vn.ledat.itemupgrader.catalog.TargetSelectionService;
import vn.ledat.itemupgrader.chance.ChanceCalculator;
import vn.ledat.itemupgrader.cost.CostEntry;
import vn.ledat.itemupgrader.cost.CostPlan;
import vn.ledat.itemupgrader.cost.CostPlanner;
import vn.ledat.itemupgrader.cost.ResourceAssessor;
import vn.ledat.itemupgrader.cost.ResourceSnapshot;
import vn.ledat.itemupgrader.item.ItemSnapshot;
import vn.ledat.itemupgrader.profile.RiskProfile;
import vn.ledat.itemupgrader.value.ItemValueService;

/** Read-only orchestrator. Creating/revalidating quotes does NOT draw an upgrade outcome or touch external state. */
public final class UpgradeQuoteService {
    private final TargetSelectionService selections = new TargetSelectionService();
    public QuoteResult quote(QuoteRequest request, ItemSnapshot source, CatalogIndex index, CatalogAccess access,
                             UpgradeRules rules, ResourceSnapshot resources, Instant now) {
        if (index.definitions() != rules.catalog()) return QuoteResult.rejected(QuoteResult.Status.STALE_RULES);
        if (!access.playerId().equals(resources.playerId())) return QuoteResult.rejected(QuoteResult.Status.WRONG_RESOURCE_OWNER);
        if (!resources.excludedSlots().contains(request.sourceSlot())) return QuoteResult.rejected(QuoteResult.Status.SOURCE_SLOT_NOT_EXCLUDED);
        final TargetSelectionService.Selection selection;
        try { selection = selections.select(request.sessionId(), source, index, access, request.targetId(), now, rules.settings().lifetime()); }
        catch (IllegalArgumentException invalid) { return QuoteResult.rejected(QuoteResult.Status.TARGET_DENIED); }
        var path = index.definitions().pathFor(source.facts().key());
        String profileId = rules.profileFor(path, request.profileId());
        RiskProfile profile = rules.profiles().get(profileId);
        if (profile == null || !profile.enabled() || !rules.profileAllowed(path, profileId) || !access.allows(profile.permission(), profile.requiredConditions()))
            return QuoteResult.rejected(QuoteResult.Status.PROFILE_DENIED);
        if (request.boostIds().size() > rules.settings().maximumSelectedBoosts()) return QuoteResult.rejected(QuoteResult.Status.BOOST_DENIED);
        List<BoostDefinition> boosts = new ArrayList<>(); var groups = new HashSet<String>(); int protections = 0;
        for (String id : request.boostIds()) {
            BoostDefinition boost = rules.boosts().get(id);
            if (boost == null || !boost.enabled() || (!boost.allowedProfiles().isEmpty() && !boost.allowedProfiles().contains(profileId))
                    || !access.allows(boost.permission(), boost.requiredConditions())) return QuoteResult.rejected(QuoteResult.Status.BOOST_DENIED);
            if (!boost.exclusiveGroup().isEmpty() && !groups.add(boost.exclusiveGroup())) return QuoteResult.rejected(QuoteResult.Status.BOOST_CONFLICT);
            if (boost.protection() == BoostDefinition.Protection.KEEP_SOURCE_ON_FAILURE) protections++;
            boosts.add(boost);
        }
        if (protections > 1 || (protections == 1 && profile.failure() == RiskProfile.FailureMode.KEEP))
            return QuoteResult.rejected(QuoteResult.Status.BOOST_CONFLICT);
        List<PermissionBonus> bonuses = rules.permissionBonuses(access);
        List<CostEntry> extraCosts = boosts.stream().flatMap(boost -> boost.costs().stream()).toList();
        final CostPlan costs;
        try { costs = new CostPlanner().plan(profile.costs(), profile.feeMultiplier(), extraCosts); }
        catch (IllegalArgumentException invalid) { return QuoteResult.rejected(QuoteResult.Status.COST_LIMIT_EXCEEDED); }
        RiskProfile.FailureMode failure = protections == 1 ? RiskProfile.FailureMode.KEEP : profile.failure();
        if (failure == RiskProfile.FailureMode.KEEP && !rules.settings().allowFreeProtection() && !costs.hasFailureLoss())
            return QuoteResult.rejected(QuoteResult.Status.FREE_PROTECTION_DENIED);
        var sourcePrice = new ItemValueService().evaluate(source, index.values(), index.revision()).quote().orElseThrow().totalValue();
        var targetPrice = index.ready().get(request.targetId()).value().totalValue();
        var chance = new ChanceCalculator().calculate(sourcePrice, targetPrice, rules.formulas().get(profile.formulaId()), profile.chanceMultiplier(),
                bonuses.stream().map(PermissionBonus::adjustment).toList(), boosts.stream().map(BoostDefinition::adjustment).toList(),
                rules.settings().minimumPercent(), rules.settings().maximumPercent());
        var terms = new UpgradeQuote.Terms(profileId, request.boostIds(), bonuses.stream().map(PermissionBonus::id).toList(), chance.probability(), failure, costs, rules.outputSpec(path, profileId, failure));
        var assessment = new ResourceAssessor().assess(costs, resources);
        return new QuoteResult(QuoteResult.Status.QUOTED, Optional.of(new UpgradeQuote(UUID.randomUUID(), request, selection,
                sourcePrice, targetPrice, chance, terms, assessment)));
    }
    public enum ValidationStatus { VALID_PREVIEW, WRONG_VIEWER, WRONG_SESSION, EXPIRED, STALE_SELECTION, NO_LONGER_ELIGIBLE,
        RECONFIRM_REQUIRED, INSUFFICIENT_RESOURCES, RESOURCES_UNAVAILABLE }
    public record Validation(ValidationStatus status, Optional<UpgradeQuote> replacement) {
        public Validation { java.util.Objects.requireNonNull(status); java.util.Objects.requireNonNull(replacement); }
    }
    /** A changed quote is returned to be confirmed, never silently substituted for the accepted price/odds. */
    public Validation revalidate(UpgradeQuote previous, UUID currentSession, ItemSnapshot currentSource, CatalogIndex index,
                                 CatalogAccess access, UpgradeRules rules, ResourceSnapshot resources, Instant now) {
        if (!previous.selection().viewerId().equals(access.playerId())) return failed(ValidationStatus.WRONG_VIEWER);
        if (!previous.request().sessionId().equals(currentSession)) return failed(ValidationStatus.WRONG_SESSION);
        if (!now.isBefore(previous.selection().expiresAt())) return failed(ValidationStatus.EXPIRED);
        var check = selections.validate(previous.selection(), currentSession, currentSource, index, access, now);
        if (check != TargetSelectionService.Check.VALID_PREVIEW) return failed(ValidationStatus.STALE_SELECTION);
        var recomputed = quote(previous.request(), currentSource, index, access, rules, resources, now);
        if (recomputed.quote().isEmpty()) return failed(ValidationStatus.NO_LONGER_ELIGIBLE);
        UpgradeQuote next = recomputed.quote().orElseThrow();
        if (!previous.terms().equals(next.terms())) return new Validation(ValidationStatus.RECONFIRM_REQUIRED, Optional.of(next));
        // Unchanged terms refresh evidence, not quote lifetime/identity. Only an explicit replacement needing
        // reconfirmation above gets a new expiry; repeated validation must not keep an old quote alive.
        next = new UpgradeQuote(previous.quoteId(), previous.request(), previous.selection(), next.sourceTotal(),
                next.targetTotal(), next.chance(), next.terms(), next.resources());
        if (!next.resources().available()) return new Validation(next.resources().status() == vn.ledat.itemupgrader.cost.ResourceAssessment.Status.UNAVAILABLE
                ? ValidationStatus.RESOURCES_UNAVAILABLE : ValidationStatus.INSUFFICIENT_RESOURCES, Optional.of(next));
        return new Validation(ValidationStatus.VALID_PREVIEW, Optional.of(next));
    }
    private static Validation failed(ValidationStatus status) { return new Validation(status, Optional.empty()); }
}
