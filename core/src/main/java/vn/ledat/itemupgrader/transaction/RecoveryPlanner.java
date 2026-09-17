package vn.ledat.itemupgrader.transaction;

import java.util.Objects;
import vn.ledat.itemupgrader.transaction.model.AttemptRecord;

/** Read-only crash recovery classification. Call only after the previous executor/process is quiescent.
 * Never steal an active owner lock using a timeout. Unknown effects need authoritative reconciliation. */
public final class RecoveryPlanner {
    public enum Action { NONE, RESUME_WITH_SAME_PLAN, VERIFY_EXTERNAL_EFFECT, REVIEW_UNCOMMITTED_DRAW, MANUAL_RECONCILIATION }
    public record Recommendation(Action action, String reason) {}
    public Recommendation inspect(AttemptRecord record) {
        Objects.requireNonNull(record); TransactionMachine.validate(record);
        if (record.terminal()) return new Recommendation(Action.NONE, "TERMINAL");
        if (record.state() == AttemptRecord.State.RECONCILIATION_REQUIRED)
            return new Recommendation(Action.MANUAL_RECONCILIATION, record.reason());
        if (TransactionMachine.pending(record)) return new Recommendation(Action.VERIFY_EXTERNAL_EFFECT, "INTENT_WITHOUT_ACK");
        if (record.state() == AttemptRecord.State.DRAW_INTENT)
            return new Recommendation(Action.REVIEW_UNCOMMITTED_DRAW, "NO_DURABLE_OUTCOME_DO_NOT_REDRAW");
        return new Recommendation(Action.RESUME_WITH_SAME_PLAN, "ACKNOWLEDGED_PREFIX_ONLY");
    }
}
