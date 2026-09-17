package vn.ledat.itemupgrader.transaction;

import java.math.BigDecimal;
import java.util.*;
import vn.ledat.itemupgrader.cost.*;
import vn.ledat.itemupgrader.profile.RiskProfile;
import vn.ledat.itemupgrader.transaction.model.*;
import static vn.ledat.itemupgrader.transaction.model.Effect.Kind.*;

/** Pinned effects only; never reads live config, a new target template or an economy balance. */
public final class EffectScript {
    private EffectScript() {}
    public static List<Effect> reservation(AttemptPlan plan) {
        List<Effect> result = new ArrayList<>(); result.add(Effect.simple(LEDGER_CLAIM));
        if (plan.terms().output().isPresent()) result.add(Effect.simple(PREPARE_OUTPUT));
        result.add(Effect.simple(HOLD_SOURCE));
        for (int i = 0; i < plan.feeItems().size(); i++)
            result.add(new Effect(HOLD_FEE_ITEM, i, BigDecimal.valueOf(plan.feeItems().get(i).amount())));
        for (int i = 0; i < plan.terms().costs().lines().size(); i++) {
            var line = plan.terms().costs().lines().get(i);
            if (line.resource().kind() == CostResource.Kind.CURRENCY) result.add(new Effect(DEBIT_CURRENCY, i, line.reserve()));
        }
        return List.copyOf(result);
    }
    public static List<Effect> settlement(AttemptPlan plan, boolean success) {
        List<Effect> result = new ArrayList<>();
        Map<CostResource, BigDecimal> remaining = new TreeMap<>();
        for (var line : plan.terms().costs().lines()) remaining.put(line.resource(), line.consumed(success));
        for (int i = 0; i < plan.feeItems().size(); i++) {
            var hold = plan.feeItems().get(i); var resource = CostResource.item(hold.snapshot().facts().key());
            BigDecimal held = BigDecimal.valueOf(hold.amount()), consume = remaining.get(resource).min(held);
            remaining.put(resource, remaining.get(resource).subtract(consume));
            BigDecimal refund = held.subtract(consume);
            if (refund.signum() > 0) result.add(new Effect(RETURN_FEE_ITEM, i, refund));
        }
        for (int i = 0; i < plan.terms().costs().lines().size(); i++) {
            var line = plan.terms().costs().lines().get(i);
            BigDecimal refund = line.reserve().subtract(line.consumed(success));
            if (line.resource().kind() == CostResource.Kind.CURRENCY && refund.signum() > 0)
                result.add(new Effect(REFUND_CURRENCY, i, refund));
        }
        if (success) result.add(Effect.simple(DELIVER_TARGET));
        else if (plan.terms().failure() == RiskProfile.FailureMode.KEEP) result.add(Effect.simple(RETURN_SOURCE));
        else if (plan.terms().failure() == RiskProfile.FailureMode.DAMAGE || plan.terms().failure() == RiskProfile.FailureMode.DOWNGRADE)
            result.add(Effect.simple(DELIVER_FAILURE_OUTPUT));
        result.add(Effect.simple(LEDGER_SUCCESS)); // A losing roll is still a successfully settled transaction.
        return List.copyOf(result);
    }
    /** Compensate only acknowledged reservations preceding a definite no-effect failure. Never infer unknowns. */
    public static List<Effect> compensation(List<AttemptRecord.Step> reservations) {
        List<Effect> result = new ArrayList<>(); boolean ledger = false;
        for (int i = reservations.size() - 1; i >= 0; i--) {
            var step = reservations.get(i);
            if (step.receipt().status() != EffectReceipt.Status.APPLIED) continue;
            var e = step.effect();
            switch (e.kind()) {
                case LEDGER_CLAIM -> ledger = true;
                case PREPARE_OUTPUT -> { /* Prepared templates are not player entitlements. Do not grant them on abort. */ }
                case HOLD_SOURCE -> result.add(Effect.simple(RETURN_SOURCE));
                case HOLD_FEE_ITEM -> result.add(new Effect(RETURN_FEE_ITEM, e.reference(), e.amount()));
                case DEBIT_CURRENCY -> result.add(new Effect(REFUND_CURRENCY, e.reference(), e.amount()));
                default -> throw new IllegalArgumentException("not a reservation prefix");
            }
        }
        if (ledger) result.add(Effect.simple(LEDGER_FAIL));
        return List.copyOf(result);
    }
}
