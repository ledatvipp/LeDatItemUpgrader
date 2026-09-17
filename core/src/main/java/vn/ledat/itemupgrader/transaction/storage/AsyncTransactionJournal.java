package vn.ledat.itemupgrader.transaction.storage;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import vn.ledat.itemupgrader.transaction.model.*;

/** Production bridge should return the Platform SQL future directly, never join it on the owner or on its own DB executor. */
public interface AsyncTransactionJournal {
    CompletionStage<TransactionJournal.Claim> claim(AttemptPlan plan, Instant now);
    CompletionStage<Optional<AttemptRecord>> find(UUID id);
    CompletionStage<Boolean> compareAndSet(AttemptRecord expected, AttemptRecord next);
    CompletionStage<List<AttemptRecord>> unfinished(Optional<UUID> after, int limit);
    CompletionStage<Optional<UUID>> activeAttempt(UUID player);
    /** For an independently owned synchronous store; caller supplies a bounded storage executor. No pool is created here. */
    static AsyncTransactionJournal offload(TransactionJournal journal, Executor storageWorker) {
        Objects.requireNonNull(journal); Objects.requireNonNull(storageWorker);
        return new AsyncTransactionJournal() {
            private <T>CompletableFuture<T> call(java.util.function.Supplier<T> work) {
                try{return CompletableFuture.supplyAsync(work,storageWorker);}catch(RejectedExecutionException e){return CompletableFuture.failedFuture(e);}
            }
            public CompletionStage<TransactionJournal.Claim> claim(AttemptPlan plan,Instant now){return call(()->journal.claim(plan,now));}
            public CompletionStage<Optional<AttemptRecord>> find(UUID id){return call(()->journal.find(id));}
            public CompletionStage<Boolean> compareAndSet(AttemptRecord e,AttemptRecord n){return call(()->journal.compareAndSet(e,n));}
            public CompletionStage<List<AttemptRecord>> unfinished(Optional<UUID> after,int limit){return call(()->journal.unfinished(after,limit));}
            public CompletionStage<Optional<UUID>> activeAttempt(UUID player){return call(()->journal.activeAttempt(player));}
        };
    }
}
