package vn.ledat.itemupgrader.transaction;

import java.util.*;
import java.util.concurrent.*;

/** Process-local admission only; durable per-player ownership lives in the journal. No expiry/lock stealing. */
public final class AttemptRunGate {
    public enum Status { ENTERED, PLAYER_BUSY, CAPACITY, STOPPED }
    public record Ticket(UUID token, UUID player, UUID attempt) {}
    public record Admission(Status status, Optional<Ticket> ticket) {}
    private final int maximum;
    private final Map<UUID,Ticket> active = new HashMap<>();
    private boolean accepting = true;
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    public AttemptRunGate(int maximum) {
        if (maximum < 1 || maximum > 256) throw new IllegalArgumentException("in-flight limit outside 1..256");
        this.maximum=maximum;
    }
    public synchronized Admission enter(UUID player, UUID attempt) {
        Objects.requireNonNull(player); Objects.requireNonNull(attempt);
        if (!accepting) return new Admission(Status.STOPPED, Optional.empty());
        if (active.containsKey(player)) return new Admission(Status.PLAYER_BUSY, Optional.empty());
        if (active.size()>=maximum) return new Admission(Status.CAPACITY, Optional.empty());
        Ticket ticket=new Ticket(UUID.randomUUID(),player,attempt); active.put(player,ticket);
        return new Admission(Status.ENTERED,Optional.of(ticket));
    }
    public void leave(Ticket ticket) {
        boolean finish;
        synchronized(this) {
            if (!active.remove(ticket.player(),ticket)) return; // A stale callback must not release a new ticket.
            finish=!accepting && active.isEmpty();
        }
        if(finish) drained.complete(null);
    }
    public CompletionStage<Void> stop() {
        boolean finish;
        synchronized(this) { accepting=false; finish=active.isEmpty(); }
        if(finish)drained.complete(null);
        return drained.minimalCompletionStage();
    }
    public synchronized boolean accepting(){return accepting;}
    public synchronized int inFlight(){return active.size();}
}
