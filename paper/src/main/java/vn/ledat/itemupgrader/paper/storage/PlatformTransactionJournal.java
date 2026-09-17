package vn.ledat.itemupgrader.paper.storage;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletionStage;
import vn.ledat.itemupgrader.paper.platform.PlatformAccess;
import vn.ledat.itemupgrader.transaction.model.*;
import vn.ledat.itemupgrader.transaction.storage.*;

/** SQL bridge SOURCE ONLY until real Platform compilation/staging. Not bootstrapped and no live upgrade command.
 * It uses the documented async query callback, holds no Connection and does not join any future.
 * The selected database must provide local transactions on an initially auto-commit connection.
 */
public final class PlatformTransactionJournal implements AsyncTransactionJournal {
    private final PlatformAccess platform;
    private final JdbcTransactionRepository repository;
    public PlatformTransactionJournal(PlatformAccess platform) { this(platform,JournalCommitHook.NONE); }
    public PlatformTransactionJournal(PlatformAccess platform,JournalCommitHook hook) {
        this.platform=Objects.requireNonNull(platform);
        repository=new JdbcTransactionRepository(new JournalSql(new JournalSql.Tables(
                platform.tableName("attempts"),platform.tableName("player_locks"),platform.tableName("tx_events"),platform.tableName("tx_schema"))),hook);
    }
    public CompletionStage<Integer> initialize(JournalSql.Dialect dialect,Instant now){return platform.query("iup-tx-schema",c->repository.initialize(c,dialect,now));}
    @Override public CompletionStage<TransactionJournal.Claim> claim(AttemptPlan p,Instant now){return platform.query("iup-tx-claim",c->repository.claim(c,p,now));}
    @Override public CompletionStage<Optional<AttemptRecord>> find(UUID id){return platform.query("iup-tx-find",c->repository.find(c,id));}
    @Override public CompletionStage<Boolean> compareAndSet(AttemptRecord old,AttemptRecord next){return platform.query("iup-tx-cas",c->repository.compareAndSet(c,old,next));}
    @Override public CompletionStage<List<AttemptRecord>> unfinished(Optional<UUID> after,int limit){return platform.query("iup-tx-recovery-page",c->repository.unfinished(c,after,limit));}
    @Override public CompletionStage<Optional<UUID>> activeAttempt(UUID player){return platform.query("iup-tx-owner",c->repository.activeAttempt(c,player));}
}
