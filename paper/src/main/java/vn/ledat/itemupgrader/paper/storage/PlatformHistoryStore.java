package vn.ledat.itemupgrader.paper.storage;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import vn.ledat.itemupgrader.history.*;
import vn.ledat.itemupgrader.history.storage.*;
import vn.ledat.itemupgrader.pity.*;
import vn.ledat.itemupgrader.paper.platform.PlatformAccess;
import vn.ledat.itemupgrader.transaction.storage.*;
/** Async read bridge. A timed-out viewer does not release database capacity until the real query completes. */
public final class PlatformHistoryStore {
    private final PlatformAccess platform;private final JdbcProgressRepository repository;
    private final java.util.function.BooleanSupplier readiness;
    private final AtomicInteger pending=new AtomicInteger();private final AtomicBoolean closed=new AtomicBoolean();
    public PlatformHistoryStore(PlatformAccess platform, JdbcProgressRepository repository, java.util.function.BooleanSupplier readiness) {
        this.platform=Objects.requireNonNull(platform);this.repository=Objects.requireNonNull(repository);this.readiness=Objects.requireNonNull(readiness);
    }
    public boolean available(){return !closed.get()&&readiness.getAsBoolean();}
    /** Live wiring must still inject this hook. Schema availability alone does not start a transaction writer. */
    public JournalCommitHook commitHook(){return repository;}
    public CompletionStage<HistoryPage> page(HistoryQuery query,int maximum){return call("iup-history-page",maximum,c->repository.page(c,query));}
    public CompletionStage<PlayerStatistics> statistics(UUID player,int maximum){return call("iup-history-stats",maximum,c->repository.statistics(c,player));}
    public CompletionStage<PitySnapshot> pity(UUID player,String scope,int maximum){return call("iup-pity-read",maximum,c->repository.pity(c,player,scope));}
    public CompletionStage<Optional<AttemptDiagnostic>> diagnostic(UUID id,int maximum) {
        var journal=new JdbcTransactionRepository(new JournalSql(new JournalSql.Tables(platform.tableName("attempts"),platform.tableName("player_locks"),platform.tableName("tx_events"),platform.tableName("tx_schema"))));
        return call("iup-history-diagnostic",maximum,c->journal.find(c,id).map(AttemptDiagnostic::from));
    }
    public int pending(){return pending.get();}
    public void close(){closed.set(true);}
    private <T>CompletionStage<T> call(String name,int maximum,PlatformAccess.SqlWork<T> work) {
        if(maximum<1||maximum>32)throw new IllegalArgumentException("query limit");
        if(!available())return CompletableFuture.failedFuture(new IllegalStateException("history schema unavailable"));
        int n;
        do{n=pending.get();if(n>=maximum)return CompletableFuture.failedFuture(new RejectedExecutionException("history database capacity reached"));}while(!pending.compareAndSet(n,n+1));
        try {
            if(!available()){pending.decrementAndGet();return CompletableFuture.failedFuture(new IllegalStateException("history schema unavailable"));}
            var future=platform.query(name,c->{if(!available())throw new java.sql.SQLException("history schema unavailable before dispatch");return work.apply(c);});future.whenComplete((value,error)->pending.decrementAndGet());return future.minimalCompletionStage();
        }catch(RuntimeException error){pending.decrementAndGet();return CompletableFuture.failedFuture(error);}
    }
}
