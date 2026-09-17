package vn.ledat.itemupgrader.runtime;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Bounded transient read-only requests. Not a transaction lock/ledger. Timeouts release lost callbacks. */
public final class PreviewRequestGate {
    public record Ticket(UUID viewer, long sequence, long startedNanos) {}
    private final int capacity;
    private final long timeoutNanos;
    private final LongSupplier clock;
    private final Map<UUID, Ticket> current = new HashMap<>();
    private long sequence;
    private boolean stopped;
    public PreviewRequestGate(int capacity, Duration timeout, LongSupplier clock) {
        if (capacity < 1 || capacity > 256 || timeout.compareTo(Duration.ofMillis(100)) < 0 || timeout.compareTo(Duration.ofMinutes(5)) > 0)
            throw new IllegalArgumentException("invalid preview gate capacity/timeout");
        this.capacity = capacity; this.timeoutNanos = timeout.toNanos(); this.clock = Objects.requireNonNull(clock);
    }
    public synchronized Optional<Ticket> begin(UUID viewer) {
        Objects.requireNonNull(viewer); expire();
        if (stopped || current.containsKey(viewer) || current.size() >= capacity) return Optional.empty();
        Ticket ticket = new Ticket(viewer, ++sequence, clock.getAsLong()); current.put(viewer, ticket); return Optional.of(ticket);
    }
    public synchronized boolean contains(UUID viewer) { expire(); return current.containsKey(viewer); }
    public synchronized boolean isCurrent(Ticket ticket) { expire(); return !stopped && ticket.equals(current.get(ticket.viewer())); }
    public synchronized boolean finish(Ticket ticket) { expire(); return !stopped && current.remove(ticket.viewer(), ticket); }
    public synchronized void forget(UUID viewer) { current.remove(viewer); }
    public synchronized int size() { expire(); return current.size(); }
    public synchronized void close() { stopped = true; current.clear(); }
    private void expire() {
        long now = clock.getAsLong(); current.values().removeIf(ticket -> now - ticket.startedNanos() >= timeoutNanos);
    }
}
