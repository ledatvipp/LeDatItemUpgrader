package vn.ledat.itemupgrader.runtime;

import java.util.Objects;
import java.util.Optional;

/** Atomic all-or-nothing reload. No I/O or callbacks run while holding this monitor. */
public final class RuntimeStore<T> {
    public enum State { STARTING, READY, FAILED, STOPPED }
    public record Snapshot<T>(long revision, T value) {}
    public record Ticket(long sequence) {}
    private State state = State.STARTING;
    private Snapshot<T> current;
    private Ticket active;
    private long sequence;
    private long revision;
    public synchronized Optional<Ticket> beginReload() {
        if (state == State.STOPPED || active != null) return Optional.empty();
        active = new Ticket(++sequence);
        return Optional.of(active);
    }
    public synchronized boolean commit(Ticket ticket, T value) {
        Objects.requireNonNull(value, "runtime value");
        if (state == State.STOPPED || active == null || !active.equals(ticket)) return false;
        current = new Snapshot<>(++revision, value);
        active = null; state = State.READY;
        return true;
    }
    public synchronized boolean reject(Ticket ticket) {
        if (state == State.STOPPED || active == null || !active.equals(ticket)) return false;
        active = null; state = current == null ? State.FAILED : State.READY;
        return true;
    }
    public synchronized Optional<Snapshot<T>> snapshot() { return Optional.ofNullable(current); }
    public synchronized boolean isCurrent(long expected) { return current != null && current.revision() == expected && state != State.STOPPED; }
    public synchronized State state() { return state; }
    public synchronized boolean loading() { return active != null; }
    public synchronized void stop() { state = State.STOPPED; current = null; active = null; sequence++; }
}
