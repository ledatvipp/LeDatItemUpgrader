package vn.ledat.itemupgrader.transaction.model;

import java.time.Instant;
import java.util.*;
import vn.ledat.itemupgrader.chance.Probability;

/** Durable state. State changes are only accepted via the machine and a versioned repository CAS. */
public record AttemptRecord(AttemptPlan plan, State state, long version, List<Step> steps,
                            Long sample, Instant createdAt, Instant updatedAt, String reason) {
    public enum State { PREPARED, RESERVING, DRAW_INTENT, OUTCOME_COMMITTED, SETTLING,
        COMPENSATING, COMPLETED, ABORTED, RECONCILIATION_REQUIRED }
    public record Step(Effect effect, EffectReceipt receipt) {
        public Step { Objects.requireNonNull(effect); Objects.requireNonNull(receipt); }
    }
    public static final int MAX_STEPS = 144;
    public AttemptRecord {
        Objects.requireNonNull(plan); Objects.requireNonNull(state); Objects.requireNonNull(createdAt); Objects.requireNonNull(updatedAt);
        if (version < 0 || version > 10000 || updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("invalid record version/time");
        steps = List.copyOf(steps);
        if (steps.size() > MAX_STEPS) throw new IllegalArgumentException("too many effects");
        if (sample != null && (sample < 0 || sample >= Probability.DENOMINATOR)) throw new IllegalArgumentException("invalid sample");
        if (reason == null || !reason.matches("[A-Z0-9_]{0,64}")) throw new IllegalArgumentException("invalid reason code");
        for (int i = 0; i < steps.size() - 1; i++) {
            var status = steps.get(i).receipt().status();
            if (status == EffectReceipt.Status.INTENT || status == EffectReceipt.Status.UNKNOWN)
                throw new IllegalArgumentException("unresolved effect must be the last effect");
        }
    }
    public UUID id() { return plan.attemptId(); }
    public boolean terminal() { return state == State.COMPLETED || state == State.ABORTED; }
    public boolean successfulRoll() {
        if (sample == null) throw new IllegalStateException("outcome has not been committed");
        return plan.terms().probability().succeeds(sample);
    }
    public String operationKey(int ordinal) {
        if (ordinal < 0 || ordinal >= MAX_STEPS) throw new IllegalArgumentException("invalid operation ordinal");
        return "iup:" + id() + ":" + ordinal;
    }
    public static AttemptRecord initial(AttemptPlan plan, Instant now) {
        if (!now.isBefore(plan.expiresAt())) throw new IllegalArgumentException("expired plan");
        return new AttemptRecord(plan, State.PREPARED, 0, List.of(), null, now, now, "");
    }
}
