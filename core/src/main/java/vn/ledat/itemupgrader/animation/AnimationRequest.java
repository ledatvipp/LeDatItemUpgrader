package vn.ledat.itemupgrader.animation;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import vn.ledat.itemupgrader.chance.Probability;
import vn.ledat.itemupgrader.transaction.TransactionMachine;
import vn.ledat.itemupgrader.transaction.model.AttemptRecord;

/** Minimal presentation data. No sample, item bytes, costs, rewards or mutable transaction record. */
public final class AnimationRequest {
    public enum Origin { ADMIN_PREVIEW, JOURNAL_OUTCOME }
    public enum Outcome { WIN, LOSS }
    private final UUID viewer, reference;
    private final Origin origin;
    private final Outcome outcome;
    private final Optional<Probability> probability;
    private final long journalVersion;
    private AnimationRequest(UUID viewer, UUID reference, Origin origin, Outcome outcome,
                             Optional<Probability> probability, long journalVersion) {
        this.viewer = Objects.requireNonNull(viewer); this.reference = Objects.requireNonNull(reference);
        this.origin = Objects.requireNonNull(origin); this.outcome = Objects.requireNonNull(outcome);
        this.probability = Objects.requireNonNull(probability); this.journalVersion = journalVersion;
    }
    /** An explicit, labelled test, never disguised as a transaction result. No probability is fabricated. */
    public static AnimationRequest preview(UUID viewer, UUID reference, Outcome selectedOutcome) {
        return new AnimationRequest(viewer, reference, Origin.ADMIN_PREVIEW, selectedOutcome, Optional.empty(), -1);
    }
    // Only the journal reader constructs the live-origin variant. The repository remains a trusted boundary.
    static AnimationRequest fromJournal(AttemptRecord record) {
        TransactionMachine.validate(record);
        if (!java.util.Set.of(AttemptRecord.State.OUTCOME_COMMITTED, AttemptRecord.State.SETTLING,
                AttemptRecord.State.COMPLETED).contains(record.state()) || record.sample() == null)
            throw new IllegalArgumentException("journal record has no displayable committed outcome");
        return new AnimationRequest(record.plan().playerId(), record.id(), Origin.JOURNAL_OUTCOME,
                record.successfulRoll() ? Outcome.WIN : Outcome.LOSS,
                Optional.of(record.plan().terms().probability()), record.version());
    }
    public UUID viewer() { return viewer; }
    public UUID reference() { return reference; }
    public Origin origin() { return origin; }
    public Outcome outcome() { return outcome; }
    public Optional<Probability> probability() { return probability; }
    public long journalVersion() { return journalVersion; }
}
