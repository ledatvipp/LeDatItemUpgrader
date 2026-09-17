package vn.ledat.itemupgrader.history;
import java.util.*;
import vn.ledat.itemupgrader.transaction.TransactionMachine;
import vn.ledat.itemupgrader.transaction.model.*;
/** Admin read-only projection: intentionally excludes raw RNG, native receipt evidence and serialized items. */
public record AttemptDiagnostic(UUID transactionId,UUID playerId,AttemptRecord.State state,long version,
        HistoryEntry.Outcome outcome,String reason,int totalSteps,int appliedSteps,Optional<Effect.Kind> pendingEffect,
        boolean needsReconciliation) {
    public static AttemptDiagnostic from(AttemptRecord record) {
        TransactionMachine.validate(record);var h=HistoryEntry.from(record);
        int applied=(int)record.steps().stream().filter(s->s.receipt().status()==EffectReceipt.Status.APPLIED).count();
        var last=record.steps().isEmpty()?Optional.<AttemptRecord.Step>empty():Optional.of(record.steps().getLast());
        var pending=last.filter(s->s.receipt().status()==EffectReceipt.Status.INTENT||s.receipt().status()==EffectReceipt.Status.UNKNOWN).map(AttemptRecord.Step::effect).map(Effect::kind);
        return new AttemptDiagnostic(record.id(),record.plan().playerId(),record.state(),record.version(),h.outcome(),record.reason(),
                record.steps().size(),applied,pending,record.state()==AttemptRecord.State.RECONCILIATION_REQUIRED);
    }
}
