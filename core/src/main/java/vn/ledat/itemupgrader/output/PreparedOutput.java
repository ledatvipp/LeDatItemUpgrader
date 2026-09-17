package vn.ledat.itemupgrader.output;

import java.time.Instant;
import java.util.*;
import vn.ledat.itemupgrader.item.ItemSnapshot;
import vn.ledat.itemupgrader.transaction.model.AttemptPlan;
import vn.ledat.itemupgrader.transaction.storage.JournalCodec;
import vn.ledat.itemupgrader.failure.FailurePolicy;
import vn.ledat.itemupgrader.transfer.MetadataView;

/** Immutable private candidates, NOT a mailbox entitlement. Only a committed outcome chooses the deliverable. */
public record PreparedOutput(UUID attemptId, UUID playerId, String planDigest, ItemSnapshot success,
        Optional<ItemSnapshot> failure, Set<String> successIdentities, Set<String> failureIdentities, Instant createdAt) {
    public PreparedOutput {
        Objects.requireNonNull(attemptId); Objects.requireNonNull(playerId); MetadataView.digest(planDigest);
        Objects.requireNonNull(success); Objects.requireNonNull(failure); Objects.requireNonNull(createdAt);
        successIdentities = Set.copyOf(successIdentities); failureIdentities = Set.copyOf(failureIdentities);
        if (successIdentities.size() > 16 || failureIdentities.size() > 16) throw new IllegalArgumentException("output identity budget");
        successIdentities.forEach(MetadataView::digest); failureIdentities.forEach(MetadataView::digest);
        if (!Collections.disjoint(successIdentities, failureIdentities)) throw new IllegalArgumentException("success/downgrade reuse an identity");
        if (failure.isEmpty() && !failureIdentities.isEmpty()) throw new IllegalArgumentException("identity without failure output");
        if (!success.facts().risks().isEmpty() || failure.stream().anyMatch(s -> !s.facts().risks().isEmpty())) throw new IllegalArgumentException("unsafe output snapshot");
    }
    public void checkPlan(AttemptPlan plan) {
        if (!attemptId.equals(plan.attemptId()) || !playerId.equals(plan.playerId()) || !planDigest.equals(JournalCodec.planDigest(plan))
                || !success.facts().key().equals(plan.target().facts().key()) || success.facts().amount() != plan.target().facts().amount())
            throw new IllegalArgumentException("prepared output/plan binding mismatch");
        var policy = plan.terms().output().orElseThrow(() -> new IllegalArgumentException("legacy plan has no output policy")).failure();
        switch (policy) {
            case FailurePolicy.Destroy ignored -> { if (failure.isPresent()) throw new IllegalArgumentException("destroy must not have failure item"); }
            case FailurePolicy.Keep ignored -> {
                var item = failure.orElseThrow(() -> new IllegalArgumentException("KEEP must retain original source"));
                if (!item.fingerprint().equals(plan.source().fingerprint()) || !item.facts().equals(plan.source().facts()))
                    throw new IllegalArgumentException("KEEP changed the original source");
            }
            case FailurePolicy.Damage p -> {
                var f = plan.source().facts();
                if (f.amount() != 1 || f.maximumDamage() < 1 || f.damage() >= f.maximumDamage()) throw new IllegalArgumentException("invalid damage source");
                long next = f.damage() + ((long) f.maximumDamage() * p.basisPoints() + 9999) / 10000;
                boolean destroyed = next >= f.maximumDamage() && p.breakBehavior() == FailurePolicy.BreakBehavior.DESTROY;
                if (destroyed != failure.isEmpty()) throw new IllegalArgumentException("damage break result mismatch");
                if (!destroyed) {
                    var out = failure.orElseThrow().facts(); int damage = (int) Math.min(next, f.maximumDamage()-1L);
                    if (damage <= f.damage() || out.damage()!=damage || !out.key().equals(f.key()) || out.amount()!=f.amount()
                            || out.maximumDamage()!=f.maximumDamage() || !out.enchantments().equals(f.enchantments()) || !out.rarity().equals(f.rarity()))
                        throw new IllegalArgumentException("damage output facts mismatch");
                }
            }
            case FailurePolicy.Downgrade p -> {
                var f = failure.orElseThrow(() -> new IllegalArgumentException("missing downgrade output")).facts();
                if (!f.key().equals(p.key()) || f.amount()!=p.amount() || f.key().equals(plan.source().facts().key()))
                    throw new IllegalArgumentException("invalid explicit downgrade target");
            }
        }
        if (!(policy instanceof FailurePolicy.Downgrade) && !failureIdentities.isEmpty())
            throw new IllegalArgumentException("retained source identities must not be registered as newly minted");
    }
}
