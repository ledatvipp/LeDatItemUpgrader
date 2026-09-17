package vn.ledat.itemupgrader.demo.support;

import java.time.Instant;
import java.util.*;
import vn.ledat.itemupgrader.transaction.storage.*;
import vn.ledat.itemupgrader.transaction.model.*;

/** OFFLINE SIMULATION ONLY. NOT durable storage, no disk I/O, no SQL, never bootstrapped by the Paper plugin.
 * Encodes on write and decodes on read to exercise persistence boundaries and detached object identity in tests. */
public final class SimulationJournal implements TransactionJournal {
    @FunctionalInterface public interface WriteProbe { void check(AttemptRecord before,AttemptRecord after); }
    private final Map<UUID,byte[]> records=new HashMap<>();
    private final Map<UUID,UUID> owners=new HashMap<>();
    private final List<byte[]> history=new ArrayList<>();
    private WriteProbe before=(a,b)->{}, after=(a,b)->{};
    private boolean available=true;
    public synchronized void available(boolean value){available=value;}
    public synchronized void probes(WriteProbe before,WriteProbe after){this.before=Objects.requireNonNull(before);this.after=Objects.requireNonNull(after);}
    private void healthy(){if(!available)throw new IllegalStateException("simulated journal outage");}
    @Override public synchronized Claim claim(AttemptPlan plan,Instant now) {
        healthy();var prior=find(plan.attemptId());
        if(prior.isPresent())return new Claim(JournalCodec.planDigest(prior.orElseThrow().plan()).equals(JournalCodec.planDigest(plan))
                ?ClaimStatus.DUPLICATE:ClaimStatus.IDEMPOTENCY_CONFLICT,prior,Optional.empty());
        if(!now.isBefore(plan.expiresAt()))return new Claim(ClaimStatus.EXPIRED,Optional.empty(),Optional.empty());
        if(owners.containsKey(plan.playerId()))return new Claim(ClaimStatus.PLAYER_BUSY,Optional.empty(),Optional.of(owners.get(plan.playerId())));
        var record=AttemptRecord.initial(plan,now);before.check(null,record);byte[] bytes=JournalCodec.encodeRecord(record);
        records.put(record.id(),bytes);owners.put(plan.playerId(),record.id());history.add(bytes.clone());after.check(null,record);
        return new Claim(ClaimStatus.CREATED,Optional.of(JournalCodec.decodeRecord(bytes)),Optional.empty());
    }
    @Override public synchronized Optional<AttemptRecord> find(UUID id){healthy();return Optional.ofNullable(records.get(id)).map(JournalCodec::decodeRecord);}
    @Override public synchronized boolean compareAndSet(AttemptRecord old,AttemptRecord next){
        healthy();JournalCodec.checkTransition(old,next);byte[] actual=records.get(old.id());
        if(actual==null||!Arrays.equals(actual,JournalCodec.encodeRecord(old)))return false;
        if(!Objects.equals(owners.get(old.plan().playerId()),old.id()))throw new IllegalStateException("missing simulation owner");
        before.check(old,next);byte[] bytes=JournalCodec.encodeRecord(next);records.put(next.id(),bytes);history.add(bytes.clone());
        if(next.terminal())owners.remove(next.plan().playerId(),next.id());after.check(old,next);return true;
    }
    @Override public synchronized Optional<UUID> activeAttempt(UUID player){healthy();return Optional.ofNullable(owners.get(player));}
    @Override public synchronized List<AttemptRecord> unfinished(Optional<UUID> afterId,int limit){
        healthy();if(limit<1||limit>8)throw new IllegalArgumentException("recovery page limit 1..8");
        String after=afterId.map(UUID::toString).orElse("");
        return records.values().stream().map(JournalCodec::decodeRecord).filter(r->!r.terminal()&&r.id().toString().compareTo(after)>0)
                .sorted(Comparator.comparing(r->r.id().toString())).limit(limit).toList();
    }
    public synchronized List<byte[]> history(){return history.stream().map(byte[]::clone).toList();}
    public static SimulationJournal restored(AttemptRecord record){
        var journal=new SimulationJournal();byte[] bytes=JournalCodec.encodeRecord(record);journal.records.put(record.id(),bytes);
        if(!record.terminal())journal.owners.put(record.plan().playerId(),record.id());journal.history.add(bytes.clone());return journal;
    }
}
