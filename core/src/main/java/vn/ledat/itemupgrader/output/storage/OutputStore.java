package vn.ledat.itemupgrader.output.storage;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletionStage;
import vn.ledat.itemupgrader.output.PreparedOutput;
import vn.ledat.itemupgrader.transfer.MetadataView;

/** Async durable boundary. PREPARING is a no-replay tombstone, not a lease that expires and regenerates items.
 * claim must commit before created=true. finish must atomically store READY payload + fresh identity claims.
 * Conflicting identity token cannot leave a partially READY record. Never upsert/overwrite an existing output.
 */
public interface OutputStore {
    enum State { PREPARING, READY, REJECTED, AMBIGUOUS }
    record Row(UUID attemptId,UUID playerId,String planDigest,State state,Optional<PreparedOutput> output,String reason,Instant createdAt) {
        public Row {
            Objects.requireNonNull(attemptId);Objects.requireNonNull(playerId);Objects.requireNonNull(state);Objects.requireNonNull(output);Objects.requireNonNull(createdAt);MetadataView.digest(planDigest);
            if(reason==null||!reason.matches("[A-Z0-9_]{0,64}")||(state==State.READY)!=output.isPresent())throw new IllegalArgumentException("invalid output row");
            output.ifPresent(p->{if(!p.attemptId().equals(attemptId)||!p.playerId().equals(playerId)||!p.planDigest().equals(planDigest))throw new IllegalArgumentException("output row/payload binding mismatch");});
            if(state==State.PREPARING&&!reason.isEmpty()||state!=State.PREPARING&&state!=State.READY&&reason.isEmpty())throw new IllegalArgumentException("invalid output state reason");
        }
        public static Row pending(UUID attemptId,UUID playerId,String digest,Instant now){return new Row(attemptId,playerId,digest,State.PREPARING,Optional.empty(),"",now);}
    }
    record Claim(boolean created,Row row) { public Claim { Objects.requireNonNull(row);if(created&&row.state()!=State.PREPARING)throw new IllegalArgumentException("created claim must be pending"); } }
    CompletionStage<Claim> claim(Row pending);
    CompletionStage<Optional<Row>> find(UUID attemptId);
    /** Compare-and-set from the exact PREPARING row. Returns false on stale/duplicate completion; never overwrites READY. */
    CompletionStage<Boolean> finish(Row expected,Row completed);
}
