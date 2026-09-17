package vn.ledat.itemupgrader.demo.support;

import java.util.*;
import java.util.concurrent.*;
import vn.ledat.itemupgrader.output.storage.*;

/** NON-DURABLE TEST/DEMO ONLY. Encodes on write and decodes on read; never used by Paper or packaged in plugin jar. */
public final class SimulationOutputStore implements OutputStore {
    private final Map<UUID,Row> rows=new HashMap<>();
    private final Map<String,UUID> identities=new HashMap<>();
    @Override public synchronized CompletionStage<Claim> claim(Row pending){
        Row previous=rows.putIfAbsent(pending.attemptId(),pending);
        return CompletableFuture.completedFuture(new Claim(previous==null,copy(previous==null?pending:previous)));
    }
    @Override public synchronized CompletionStage<Optional<Row>> find(UUID attempt){return CompletableFuture.completedFuture(Optional.ofNullable(rows.get(attempt)).map(SimulationOutputStore::copy));}
    @Override public synchronized CompletionStage<Boolean> finish(Row expected,Row completed){
        if(expected.state()!=State.PREPARING||completed.state()==State.PREPARING||!expected.attemptId().equals(completed.attemptId())
                ||!expected.playerId().equals(completed.playerId())||!expected.planDigest().equals(completed.planDigest())||!expected.createdAt().equals(completed.createdAt()))
            throw new IllegalArgumentException("simulation output transition");
        if(!expected.equals(rows.get(expected.attemptId())))return CompletableFuture.completedFuture(false);
        Set<String> tokens=new TreeSet<>();completed.output().ifPresent(output->{tokens.addAll(output.successIdentities());tokens.addAll(output.failureIdentities());});
        if(tokens.stream().anyMatch(identities::containsKey))return CompletableFuture.failedFuture(new IllegalStateException("duplicate fresh output identity"));
        tokens.forEach(token->identities.put(token,completed.attemptId()));rows.put(expected.attemptId(),copy(completed));
        return CompletableFuture.completedFuture(true);
    }
    private static Row copy(Row row){return new Row(row.attemptId(),row.playerId(),row.planDigest(),row.state(),row.output().map(value->OutputCodec.decode(OutputCodec.encode(value))),row.reason(),row.createdAt());}
}
