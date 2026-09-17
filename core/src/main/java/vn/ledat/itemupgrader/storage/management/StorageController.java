package vn.ledat.itemupgrader.storage.management;

import java.time.Instant;
import java.time.Duration;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Thread-safe, single-flight lifecycle. No scheduler/connection/Player is owned here.
 * A timeout fences publication but does NOT free the real database operation or cancel DDL.
 * Completion callbacks from old revisions cannot mark the new revision ready.
 * Only the caller's tick dispatches work, never a recursive future-completion retry loop. */
public final class StorageController implements AutoCloseable {
    public interface Port {
        CompletionStage<SchemaReport> prepare(StorageSettings.Mode mode, Instant now);
        CompletionStage<Integer> prune(Instant terminalBefore, int maximum);
    }
    public enum State { OFF, WAITING, CHECKING, READ_READY, FAILED, STOPPED }
    public enum Reason { NONE, DATABASE_ERROR, DEADLINE, DISABLED, STOPPED }
    public enum Operation { SCHEMA, PRUNE }
    public record View(long revision, State state, Reason reason, boolean busy, Optional<Operation> operation,
            Optional<SchemaReport> schema, int lastPruned, Optional<Instant> lastPruneAt) {
        public View {
            Objects.requireNonNull(state); Objects.requireNonNull(reason); Objects.requireNonNull(operation);
            Objects.requireNonNull(schema); Objects.requireNonNull(lastPruneAt);
            if (busy != operation.isPresent()) throw new IllegalArgumentException("storage busy/operation mismatch");
        }
        public boolean readable(long expectedRevision) { return revision == expectedRevision && state == State.READ_READY; }
    }
    private record Ticket(long sequence, long revision, Operation operation, Instant deadline, int maximum) {}
    private final Port port;
    private final Clock clock;
    private final Consumer<Throwable> errorSink;
    private StorageSettings settings = StorageSettings.off();
    private long revision = -1, sequence;
    private State state = State.OFF;
    private Reason reason = Reason.DISABLED;
    private SchemaReport schema;
    private Ticket flight;
    private boolean expired, requested;
    private Instant nextPrune = Instant.MAX, lastPrune;
    private int lastPruned;

    public StorageController(Port port, Consumer<Throwable> errorSink) {
        this(port, Clock.systemUTC(), errorSink);
    }
    public StorageController(Port port, Clock clock, Consumer<Throwable> errorSink) {
        this.port = Objects.requireNonNull(port); this.clock = Objects.requireNonNull(clock); this.errorSink = Objects.requireNonNull(errorSink);
    }
    /** Same revision is immutable; an out-of-order revision is ignored. */
    public synchronized boolean configure(long nextRevision, StorageSettings next, Instant now) {
        Objects.requireNonNull(next); Objects.requireNonNull(now);
        if (nextRevision < 1) throw new IllegalArgumentException("revision must be positive");
        if (state == State.STOPPED || nextRevision < revision) return false;
        if (nextRevision == revision) {
            if (!next.equals(settings)) throw new IllegalArgumentException("configuration changed without revision");
            return false;
        }
        revision = nextRevision; settings = next; schema = null; expired = false;
        lastPrune = null; lastPruned = 0; nextPrune = Instant.MAX;
        requested = next.mode() != StorageSettings.Mode.OFF;
        state = requested ? State.WAITING : State.OFF; reason = requested ? Reason.NONE : Reason.DISABLED;
        // An old flight still holds capacity; it can only retire, not publish.
        return true;
    }
    public synchronized boolean recheck(long expectedRevision) {
        if (revision != expectedRevision || flight != null || requested || settings.mode() == StorageSettings.Mode.OFF || state == State.STOPPED) return false;
        requested = true; state = State.WAITING; reason = Reason.NONE; schema = null; return true;
    }
    public void tick(Instant now) {
        Objects.requireNonNull(now);
        Ticket dispatch;
        StorageSettings captured;
        synchronized (this) {
            if (state == State.STOPPED) return;
            if (flight != null) {
                if (flight.revision() == revision && !expired && !now.isBefore(flight.deadline())) {
                    expired = true; state = State.FAILED; reason = Reason.DEADLINE; schema = null;
                }
                return;
            }
            if (settings.mode() == StorageSettings.Mode.OFF) return;
            Operation operation;
            if (requested) { requested = false; operation = Operation.SCHEMA; state = State.CHECKING; }
            else if (state == State.READ_READY && settings.pruneEnabled() && !now.isBefore(nextPrune)) operation = Operation.PRUNE;
            else return;
            sequence = Math.incrementExact(sequence);
            dispatch = new Ticket(sequence, revision, operation, now.plus(settings.operationTimeout()), settings.pruneBatch());
            flight = dispatch; expired = false; captured = settings;
        }
        try {
            if (dispatch.operation() == Operation.SCHEMA) {
                Objects.requireNonNull(port.prepare(captured.mode(), now), "null schema future")
                    .whenComplete((result, error) -> complete(dispatch, result, null, error));
            } else {
                // Negative instants are clamped to the epoch; no deletion of future/unfinished rows.
                Instant before = now.minus(Duration.ofDays(captured.retentionDays()));
                if (before.isBefore(Instant.EPOCH)) before = Instant.EPOCH;
                Objects.requireNonNull(port.prune(before, captured.pruneBatch()), "null prune future")
                    .whenComplete((count, error) -> complete(dispatch, null, count, error));
            }
        } catch (RuntimeException rejected) { complete(dispatch, null, null, rejected); }
    }
    private void complete(Ticket ticket, SchemaReport report, Integer count, Throwable error) {
        Throwable warning = error;
        synchronized (this) {
            if (flight != ticket) return;
            flight = null; // Actual completion, not the viewer timeout, releases capacity.
            // A stale/expired operation may still fail physically. Report its failure once,
            // but never publish a ready state or mutate the replacement revision.
            if (state != State.STOPPED && ticket.revision() == revision && !expired) {
                Instant finished = clock.instant();
                if (!finished.isBefore(ticket.deadline())) {
                    state = State.FAILED; reason = Reason.DEADLINE; schema = null;
                } else {
                    if (error == null && (ticket.operation() == Operation.SCHEMA && report == null
                            || ticket.operation() == Operation.PRUNE && (count == null || count < 0 || count > ticket.maximum())))
                        error = new IllegalStateException("invalid storage operation result");
                    if (error != null) {
                        state = State.FAILED; reason = Reason.DATABASE_ERROR; schema = null; warning = error;
                    } else {
                        if (ticket.operation() == Operation.SCHEMA) schema = report;
                        else { lastPruned = count; lastPrune = finished; }
                        state = State.READ_READY; reason = Reason.NONE;
                        nextPrune = finished.plus(settings.pruneInterval());
                    }
                }
            }
        }
        if (warning != null) errorSink.accept(warning);
    }
    public synchronized View view() {
        return new View(revision, state, reason, flight != null, Optional.ofNullable(flight).map(Ticket::operation),
                Optional.ofNullable(schema), lastPruned, Optional.ofNullable(lastPrune));
    }
    @Override public synchronized void close() {
        state = State.STOPPED; reason = Reason.STOPPED; schema = null; requested = false;
        // Do not cancel a provider future or pretend an in-flight schema operation was rolled back.
    }
}
