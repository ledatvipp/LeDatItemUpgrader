package vn.ledat.itemupgrader.output;

import java.util.*;
import vn.ledat.itemupgrader.catalog.CatalogDefinitions;
import vn.ledat.itemupgrader.catalog.UpgradePath;
import vn.ledat.itemupgrader.profile.RiskProfile;
import vn.ledat.itemupgrader.failure.FailurePolicy;
import vn.ledat.itemupgrader.transfer.TransferPolicy;

/** Compiled config. Profile fixes the loss; source-matched path selects success transfer policy. */
public final class OutputRules {
    private final TransferPolicy defaultTransfer;
    private final Map<String,TransferPolicy> pathTransfers;
    private final Map<String,FailurePolicy> profileFailures;
    public OutputRules(TransferPolicy defaultTransfer, Map<String,TransferPolicy> pathTransfers, Map<String,FailurePolicy> profileFailures) {
        this.defaultTransfer = Objects.requireNonNull(defaultTransfer); this.pathTransfers = Map.copyOf(pathTransfers); this.profileFailures = Map.copyOf(profileFailures);
        if (pathTransfers.size() > 2000 || profileFailures.size() > 64) throw new IllegalArgumentException("output rules bound exceeded");
        pathTransfers.keySet().forEach(vn.ledat.itemupgrader.transaction.model.AttemptPlan::id);
        profileFailures.keySet().forEach(vn.ledat.itemupgrader.transaction.model.AttemptPlan::id);
    }
    public void validate(CatalogDefinitions catalog, Collection<RiskProfile> profiles) {
        var paths = catalog.paths().stream().map(UpgradePath::id).collect(java.util.stream.Collectors.toSet());
        if (!paths.containsAll(pathTransfers.keySet())) throw new IllegalArgumentException("output rules reference unknown path");
        Set<String> ids = new HashSet<>();
        for (var profile : profiles) {
            ids.add(profile.id());
            var policy = failure(profile.id(), profile.failure());
            if (policy.mode() != profile.failure()) throw new IllegalArgumentException("output failure does not match profile: " + profile.id());
        }
        if (!ids.containsAll(profileFailures.keySet())) throw new IllegalArgumentException("output rules reference unknown profile");
    }
    private FailurePolicy failure(String id, RiskProfile.FailureMode mode) {
        FailurePolicy configured = profileFailures.get(id);
        if (configured != null) return configured;
        return switch (mode) {
            case DESTROY -> new FailurePolicy.Destroy();
            case KEEP -> new FailurePolicy.Keep();
            default -> throw new IllegalArgumentException("DAMAGE/DOWNGRADE require explicit pinned output policy: " + id);
        };
    }
    public OutputSpec spec(Optional<UpgradePath> path, String profileId, RiskProfile.FailureMode effectiveMode) {
        // A selected protection replaces the configured failure entirely, not just its displayed label.
        FailurePolicy loss = effectiveMode == RiskProfile.FailureMode.KEEP ? new FailurePolicy.Keep() : failure(profileId, effectiveMode);
        if (loss.mode() != effectiveMode) throw new IllegalArgumentException("effective failure mismatch");
        return new OutputSpec(path.map(p -> pathTransfers.getOrDefault(p.id(), defaultTransfer)).orElse(defaultTransfer), loss);
    }
    public static OutputRules defaults() { return new OutputRules(TransferPolicy.clean(), Map.of(), Map.of()); }
}
