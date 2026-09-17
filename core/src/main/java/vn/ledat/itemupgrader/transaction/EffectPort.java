package vn.ledat.itemupgrader.transaction;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import vn.ledat.itemupgrader.transaction.model.*;

/** Native/Platform boundary, intentionally not implemented by the Phase 4 Paper plugin.
 * execute is invoked from the worker; an adapter MUST schedule Bukkit work on the owner thread.
 * Before HOLD_SOURCE: recheck online/session/expiry/permissions/conditions/native source and target, then acquire
 * escrow ownership in one owner critical section. Before other holds: recheck full snapshot + allocated amount.
 * Success is NOT just "task scheduled" or "economy returned a balance". Retired-before-execution = NOT_APPLIED;
 * partial work/unknown provider reply = UNKNOWN. Late outcomes must complete the ORIGINAL future, not a new attempt.
 * DELIVER_TARGET references the pinned target TEMPLATE, not permission to clone its provider UUIDs. Phase 5 must
 * materialize and persist an attempt-specific output exactly once with provider-safe identity/transfer policy.
 * Item return/delivery means durable mailbox ownership or an equivalent verified delivery protocol, never blind drop.
 * Ledger duplicate may be APPLIED only with evidence of the SAME op and plan; an unverified duplicate is UNKNOWN.
 * Acknowledged effects are assumed restart-durable. Adapters that cannot establish this must not enable live upgrades.
 */
@FunctionalInterface
public interface EffectPort {
    CompletionStage<EffectReceipt> execute(Call call);
    record Call(AttemptRecord intent, int ordinal, String planDigest) {
        public Call {
            Objects.requireNonNull(intent); Objects.requireNonNull(planDigest);
            if (!planDigest.matches("[a-f0-9]{64}") || ordinal != intent.steps().size()-1 || ordinal<0
                    || intent.steps().getLast().receipt().status()!=EffectReceipt.Status.INTENT)
                throw new IllegalArgumentException("invalid effect call binding");
            if(!planDigest.equals(vn.ledat.itemupgrader.transaction.storage.JournalCodec.planDigest(intent.plan())))
                throw new IllegalArgumentException("plan digest mismatch");
        }
        public String operationKey(){return intent.operationKey(ordinal);}
        public Effect effect(){return intent.steps().get(ordinal).effect();}
    }
}
