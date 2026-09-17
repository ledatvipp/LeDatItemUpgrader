package vn.ledat.itemupgrader.transaction;

import java.time.Instant;
import java.util.*;
import vn.ledat.itemupgrader.cost.CostResource;
import vn.ledat.itemupgrader.item.ItemSnapshot;
import vn.ledat.itemupgrader.quote.UpgradeQuoteService;
import vn.ledat.itemupgrader.transaction.model.AttemptPlan;

/** Accept only a just-revalidated server quote and detached, exact snapshots. No input is consumed here. */
public final class AttemptPlanner {
    public AttemptPlan prepare(UpgradeQuoteService.Validation validation, ItemSnapshot source, ItemSnapshot target,
                               Map<Integer, ItemSnapshot> feeSnapshots, Instant now) {
        Objects.requireNonNull(validation); Objects.requireNonNull(now);
        if (validation.status() != UpgradeQuoteService.ValidationStatus.VALID_PREVIEW)
            throw new IllegalArgumentException("quote must be revalidated, unchanged and funded");
        var quote = validation.replacement().orElseThrow(() -> new IllegalArgumentException("missing validated quote"));
        var selection = quote.selection();
        if (!now.isBefore(selection.expiresAt()) || !quote.resources().available()) throw new IllegalArgumentException("expired/unfunded quote");
        if (!selection.sourceFingerprint().equals(source.fingerprint()) || !selection.sourceFacts().equals(source.facts())
                || !selection.targetFingerprint().equals(target.fingerprint())) throw new IllegalArgumentException("snapshot changed");
        List<AttemptPlan.ItemHold> holds = new ArrayList<>();
        for (var allocation : quote.resources().allocations()) {
            var snapshot = feeSnapshots.get(allocation.slot());
            if (allocation.resource().kind() != CostResource.Kind.ITEM || snapshot == null
                    || !allocation.fingerprint().equals(snapshot.fingerprint())
                    || !allocation.resource().key().equals(snapshot.facts().key().value()))
                throw new IllegalArgumentException("fee evidence mismatch");
            holds.add(new AttemptPlan.ItemHold(allocation.slot(), allocation.amount(), snapshot));
        }
        return new AttemptPlan(selection.viewerId(), selection.sessionId(), quote.quoteId(), selection.revision(),
                selection.catalogGeneration(), quote.request().sourceSlot(), source, selection.targetId(), target,
                quote.sourceTotal(), quote.targetTotal(), quote.terms(), holds, selection.expiresAt());
    }
}
