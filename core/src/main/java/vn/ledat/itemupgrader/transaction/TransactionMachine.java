package vn.ledat.itemupgrader.transaction;

import java.time.Instant;
import java.util.*;
import vn.ledat.itemupgrader.transaction.model.*;
import static vn.ledat.itemupgrader.transaction.model.AttemptRecord.State.*;
import static vn.ledat.itemupgrader.transaction.model.EffectReceipt.Status.*;

/** Pure reducer. Persist every Change by CAS BEFORE doing its indicated external work. */
public final class TransactionMachine {
    public enum Work { NONE, EFFECT, DRAW }
    public record Decision(AttemptRecord next, Work work, boolean stopped) {}
    public Decision next(AttemptRecord current, Instant now) {
        validate(current);
        if (current.terminal() || current.state() == RECONCILIATION_REQUIRED || current.state() == DRAW_INTENT || pending(current))
            return new Decision(current, Work.NONE, true);
        return switch (current.state()) {
            case PREPARED -> new Decision(change(current, now.isBefore(current.plan().expiresAt()) ? RESERVING : ABORTED,
                    current.steps(), null, now, now.isBefore(current.plan().expiresAt()) ? "" : "EXPIRED_BEFORE_START"), Work.NONE, false);
            case RESERVING -> {
                var script = EffectScript.reservation(current.plan());
                if (current.steps().size() < script.size()) yield append(current, script.get(current.steps().size()), now);
                yield new Decision(change(current, DRAW_INTENT, current.steps(), null, now, ""), Work.DRAW, false);
            }
            case OUTCOME_COMMITTED -> new Decision(change(current, SETTLING, current.steps(), current.sample(), now, ""), Work.NONE, false);
            case SETTLING -> {
                var script = EffectScript.settlement(current.plan(), current.successfulRoll());
                int done = current.steps().size() - EffectScript.reservation(current.plan()).size();
                if (done < script.size()) yield append(current, script.get(done), now);
                yield new Decision(change(current, COMPLETED, current.steps(), current.sample(), now, ""), Work.NONE, false);
            }
            case COMPENSATING -> {
                int cut = reservationCut(current);
                var script = EffectScript.compensation(current.steps().subList(0, cut));
                int done = current.steps().size() - cut;
                if (done < script.size()) yield append(current, script.get(done), now);
                yield new Decision(change(current, ABORTED, current.steps(), null, now, "RESERVATION_REJECTED"), Work.NONE, false);
            }
            default -> throw new IllegalStateException("unexpected state");
        };
    }
    public AttemptRecord receipt(AttemptRecord current, int ordinal, EffectReceipt receipt, Instant now) {
        validate(current); Objects.requireNonNull(receipt);
        if (!Set.of(RESERVING, SETTLING, COMPENSATING).contains(current.state()) || !pending(current)
                || ordinal != current.steps().size() - 1 || receipt.status() == INTENT)
            throw new IllegalArgumentException("receipt does not match pending effect");
        List<AttemptRecord.Step> steps = new ArrayList<>(current.steps());
        steps.set(ordinal, new AttemptRecord.Step(steps.get(ordinal).effect(), receipt));
        var state = current.state(); String reason = "";
        if (receipt.status() == UNKNOWN) { state = RECONCILIATION_REQUIRED; reason = "EFFECT_UNCERTAIN"; }
        else if (receipt.status() == NOT_APPLIED) {
            if (state == RESERVING) { state = COMPENSATING; reason = "RESERVATION_REJECTED"; }
            else { state = RECONCILIATION_REQUIRED; reason = "SETTLEMENT_OR_REFUND_REJECTED"; }
        }
        var result = change(current, state, steps, current.sample(), now, reason); validate(result); return result;
    }
    public AttemptRecord commitDraw(AttemptRecord current, long sample, Instant now) {
        validate(current);
        if (current.state() != DRAW_INTENT || current.sample() != null) throw new IllegalArgumentException("draw not owned/uncommitted");
        current.plan().terms().probability().succeeds(sample); // Enforces the exact Phase 3 threshold range.
        var result = change(current, OUTCOME_COMMITTED, current.steps(), sample, now, ""); validate(result); return result;
    }
    public AttemptRecord quarantine(AttemptRecord current, Instant now, String reason) {
        validate(current);
        if (current.terminal()) throw new IllegalArgumentException("cannot quarantine terminal record");
        return change(current, RECONCILIATION_REQUIRED, current.steps(), current.sample(), now, reason);
    }
    public static boolean pending(AttemptRecord record) {
        return !record.steps().isEmpty() && record.steps().getLast().receipt().status() == INTENT;
    }
    private Decision append(AttemptRecord current, Effect effect, Instant now) {
        var steps = new ArrayList<>(current.steps()); steps.add(new AttemptRecord.Step(effect, EffectReceipt.intent()));
        return new Decision(change(current, current.state(), steps, current.sample(), now, ""), Work.EFFECT, false);
    }
    private static AttemptRecord change(AttemptRecord r, AttemptRecord.State s, List<AttemptRecord.Step> steps,
                                         Long sample, Instant now, String reason) {
        return new AttemptRecord(r.plan(), s, Math.addExact(r.version(), 1), steps, sample, r.createdAt(),
                now.isBefore(r.updatedAt()) ? r.updatedAt() : now, reason);
    }
    private static int reservationCut(AttemptRecord r) {
        int maximum = Math.min(r.steps().size(), EffectScript.reservation(r.plan()).size());
        for (int i = 0; i < maximum; i++) if (r.steps().get(i).receipt().status() == NOT_APPLIED) return i + 1;
        return maximum;
    }
    /** Checks persisted script consistency, not just enum state. Corrupted records fail closed. */
    public static void validate(AttemptRecord r) {
        var reservation = EffectScript.reservation(r.plan()); int cut = reservationCut(r);
        boolean rejected = cut > 0 && r.steps().get(cut - 1).receipt().status() == NOT_APPLIED;
        for (int i = 0; i < cut; i++) if (!r.steps().get(i).effect().equals(reservation.get(i))) bad();
        boolean allReserved = cut == reservation.size() && r.steps().subList(0, cut).stream().allMatch(s -> s.receipt().status() == APPLIED);
        List<Effect> tail = rejected ? EffectScript.compensation(r.steps().subList(0, cut))
                : r.sample() != null ? EffectScript.settlement(r.plan(), r.successfulRoll()) : List.of();
        int done = r.steps().size() - cut;
        if (done > tail.size()) bad();
        for (int i = 0; i < done; i++) if (!r.steps().get(cut+i).effect().equals(tail.get(i))) bad();
        if (r.sample() != null && (!allReserved || rejected)) bad();
        if (r.state() != RECONCILIATION_REQUIRED) {
            if (r.steps().stream().anyMatch(s -> s.receipt().status() == UNKNOWN)) bad();
            for (int i = cut; i < r.steps().size(); i++) if (r.steps().get(i).receipt().status() == NOT_APPLIED) bad();
        }
        switch (r.state()) {
            case PREPARED -> { if (r.version() != 0 || !r.steps().isEmpty() || r.sample() != null) bad(); }
            case RESERVING -> { if (rejected || r.sample() != null || done != 0) bad(); }
            case DRAW_INTENT -> { if (!allReserved || r.sample() != null || done != 0) bad(); }
            case OUTCOME_COMMITTED -> { if (!allReserved || r.sample() == null || done != 0) bad(); }
            case SETTLING -> { if (!allReserved || r.sample() == null) bad(); }
            case COMPENSATING -> { if (!rejected || r.sample() != null) bad(); }
            case COMPLETED -> { if (!allReserved || r.sample() == null || done != tail.size()
                    || r.steps().stream().anyMatch(s -> s.receipt().status() != APPLIED)) bad(); }
            case ABORTED -> {
                if (r.sample() != null || pending(r)) bad();
                if (!r.steps().isEmpty() && (!rejected || done != tail.size()
                        || r.steps().subList(cut, r.steps().size()).stream().anyMatch(s -> s.receipt().status() != APPLIED))) bad();
            }
            case RECONCILIATION_REQUIRED -> { /* Frozen; retain the unresolved intent and acknowledged prefix. */ }
        }
    }
    private static void bad() { throw new IllegalArgumentException("inconsistent transaction journal"); }
}
