package vn.ledat.itemupgrader.catalog;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import vn.ledat.itemupgrader.item.ItemFacts;
import vn.ledat.itemupgrader.item.ItemSnapshot;
import vn.ledat.itemupgrader.value.ItemValueService;

/** In-memory selection token, NOT a payment authorization, signature, or durable transaction. */
public final class TargetSelectionService {
    public record Selection(UUID viewerId, UUID sessionId, long revision, UUID catalogGeneration,
                            String sourceFingerprint, ItemFacts sourceFacts, String targetId,
                            String targetFingerprint, Instant expiresAt) {
        public Selection {
            Objects.requireNonNull(viewerId); Objects.requireNonNull(sessionId); Objects.requireNonNull(catalogGeneration);
            Objects.requireNonNull(sourceFacts); Objects.requireNonNull(expiresAt);
            CatalogValidation.id(targetId, "selection.target-id");
            if (revision < 1 || sourceFingerprint == null || !sourceFingerprint.matches("[0-9a-f]{64}")
                    || targetFingerprint == null || !targetFingerprint.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("invalid selection fingerprint/revision");
        }
    }
    public enum Check { VALID_PREVIEW, WRONG_VIEWER, WRONG_SESSION, STALE_REVISION, STALE_CATALOG,
        EXPIRED, SOURCE_CHANGED, TARGET_CHANGED, NO_LONGER_ELIGIBLE }
    public Selection select(UUID sessionId, ItemSnapshot source, CatalogIndex index, CatalogAccess access,
                            String targetId, Instant now, Duration lifetime) {
        if (lifetime.isNegative() || lifetime.isZero() || lifetime.compareTo(Duration.ofMinutes(5)) > 0)
            throw new IllegalArgumentException("selection lifetime: require (0, 5 minutes]");
        CatalogIndex.PricedTarget target = eligibleTarget(source, index, access, targetId);
        if (target == null) throw new IllegalArgumentException("selected target is not eligible");
        return new Selection(access.playerId(), sessionId, index.revision(), index.generation(), source.fingerprint(),
                source.facts(), targetId, target.snapshot().fingerprint(), now.plus(lifetime));
    }
    public Check validate(Selection selection, UUID currentSession, ItemSnapshot actualSource,
                          CatalogIndex index, CatalogAccess access, Instant now) {
        if (!selection.viewerId().equals(access.playerId())) return Check.WRONG_VIEWER;
        if (!selection.sessionId().equals(currentSession)) return Check.WRONG_SESSION;
        if (selection.revision() != index.revision()) return Check.STALE_REVISION;
        if (!selection.catalogGeneration().equals(index.generation())) return Check.STALE_CATALOG;
        if (!now.isBefore(selection.expiresAt())) return Check.EXPIRED;
        if (!selection.sourceFingerprint().equals(actualSource.fingerprint()) || !selection.sourceFacts().equals(actualSource.facts()))
            return Check.SOURCE_CHANGED;
        CatalogIndex.PricedTarget target = index.ready().get(selection.targetId());
        if (target == null || !selection.targetFingerprint().equals(target.snapshot().fingerprint())) return Check.TARGET_CHANGED;
        return eligibleTarget(actualSource, index, access, selection.targetId()) == null ? Check.NO_LONGER_ELIGIBLE : Check.VALID_PREVIEW;
    }
    private static CatalogIndex.PricedTarget eligibleTarget(ItemSnapshot source, CatalogIndex index, CatalogAccess access, String targetId) {
        CatalogIndex.PricedTarget target = index.ready().get(targetId);
        if (target == null) return null;
        var sourceValue = new ItemValueService().evaluate(source, index.values(), index.revision());
        if (sourceValue.quote().isEmpty()) return null;
        var path = index.definitions().pathFor(source.facts().key());
        if (path.isPresent() && !access.allows(path.get().permission(), path.get().requiredConditions())) return null;
        if (path.isEmpty() && !index.definitions().settings().allowUnpathed()) return null;
        var total = sourceValue.quote().orElseThrow().totalValue();
        var settings = index.definitions().settings();
        var price = target.value().totalValue();
        if (price.compareTo(total.multiply(settings.minimumRatio())) < 0 || price.compareTo(total.multiply(settings.maximumRatio())) > 0)
            return null;
        return new CatalogService().eligible(source, total, target, path, access) ? target : null;
    }
}
