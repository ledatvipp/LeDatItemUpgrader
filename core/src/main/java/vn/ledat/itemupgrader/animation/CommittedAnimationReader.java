package vn.ledat.itemupgrader.animation;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import vn.ledat.itemupgrader.transaction.TransactionMachine;
import vn.ledat.itemupgrader.transaction.model.AttemptRecord;
import vn.ledat.itemupgrader.transaction.storage.AsyncTransactionJournal;

/** Read-only seam for future live wiring. No claim, CAS, RNG, effect or settlement call is possible here. */
public final class CommittedAnimationReader {
    private final AsyncTransactionJournal journal;
    public CommittedAnimationReader(AsyncTransactionJournal journal) { this.journal = Objects.requireNonNull(journal); }
    public CompletionStage<Optional<AnimationRequest>> read(UUID viewer, UUID attemptId) {
        Objects.requireNonNull(viewer); Objects.requireNonNull(attemptId);
        return journal.find(attemptId).thenApply(found -> found.flatMap(record -> {
            if (!record.id().equals(attemptId)) throw new IllegalArgumentException("journal returned the wrong attempt");
            if (!record.plan().playerId().equals(viewer)) return Optional.empty();
            TransactionMachine.validate(record);
            if (!java.util.Set.of(AttemptRecord.State.OUTCOME_COMMITTED, AttemptRecord.State.SETTLING,
                    AttemptRecord.State.COMPLETED).contains(record.state())) return Optional.empty();
            return Optional.of(AnimationRequest.fromJournal(record));
        }));
    }
}
