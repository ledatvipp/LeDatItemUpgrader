package vn.ledat.itemupgrader.test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import vn.ledat.itemupgrader.history.*;
import vn.ledat.itemupgrader.history.storage.*;
import vn.ledat.itemupgrader.pity.*;
import vn.ledat.itemupgrader.transaction.*;
import vn.ledat.itemupgrader.transaction.model.*;
import vn.ledat.itemupgrader.transaction.storage.*;

/** Actual Java repositories + actual SQLite engine through a TEST-ONLY Python/JDBC-interface transport.
 * Not xerial JDBC, MySQL, Platform or Minecraft integration. Tests intentionally inject DB faults, never native effects. */
public final class Phase08SqliteRepositoryTest {
    @FunctionalInterface interface Checked {void run()throws Exception;}
    private static final Map<String,Checked> TESTS=new LinkedHashMap<>();private static int assertions;
    private static Path helper;
    private static final Instant NOW=ProgressFixtures.NOW;
    private static final JournalSql JS=new JournalSql(JournalSql.Tables.prefixed("iup_"));
    private static final ProgressSql PS=new ProgressSql(ProgressSql.Tables.prefixed("iup_"));
    private static final JdbcProgressRepository PROGRESS=new JdbcProgressRepository(PS);
    private static final JdbcTransactionRepository JOURNAL=new JdbcTransactionRepository(JS,PROGRESS);
    private static final UUID PLAYER=ProgressFixtures.plan(1,0,0).playerId();
    private Phase08SqliteRepositoryTest(){}
    public static void main(String[] args)throws Exception {
        if(args.length>0&&args[0].equals("--crash-child")){helper=Path.of(args[1]);crashChild(Path.of(args[2]),args[3].equals("after"));return;}
        helper=Path.of(args.length>0?args[0]:"scripts/testing/sqlite_bridge.py");
        tests();int failures=0;var xml=new StringBuilder();
        for(var e:TESTS.entrySet()){String failure=null;long start=System.nanoTime();try{e.getValue().run();}catch(Exception|AssertionError error){failure=error.toString();failures++;error.printStackTrace(System.err);}
            System.out.println((failure==null?"PASS ":"FAIL ")+e.getKey());xml.append("  <testcase classname=\"Phase08SQLiteBridge\" name=\"").append(escape(e.getKey())).append("\" time=\"").append(String.format(Locale.ROOT,"%.6f",(System.nanoTime()-start)/1e9)).append("\">");
            if(failure!=null)xml.append("<failure message=\"").append(escape(failure)).append("\"/>");xml.append("</testcase>\n");}
        Path report=Path.of(args.length>1?args[1]:"core/build/reports/phase08-sqlite-bridge.xml");Files.createDirectories(report.toAbsolutePath().getParent());
        Files.writeString(report,"<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuite name=\"Phase08SQLiteBridge\" tests=\""+TESTS.size()+"\" failures=\""+failures+"\" errors=\"0\">\n"+xml
                +"<system-out>assertions="+assertions+"; real Java repositories and SQLite through test-only bridge; NOT a production JDBC driver</system-out>\n</testsuite>\n",StandardCharsets.UTF_8);
        System.out.println("SQLITE_BRIDGE_RESULT tests="+TESTS.size()+" assertions="+assertions+" failures="+failures);if(failures!=0)throw new AssertionError("SQL bridge failures="+failures);
    }
    private static void test(String id,Checked body){if(TESTS.put(id,body)!=null)throw new IllegalArgumentException("duplicate test");}
    private static void check(boolean v){assertions++;if(!v)throw new AssertionError("expected true");}
    private static void eq(Object e,Object a){assertions++;if(!Objects.equals(e,a))throw new AssertionError("expected "+e+", got "+a);}
    private static void rejected(Checked f)throws Exception{assertions++;try{f.run();}catch(SQLException|IllegalArgumentException expected){return;}throw new AssertionError("expected rejection");}
    private static String escape(String v){return v.replace("&","&amp;").replace("<","&lt;").replace("\"","&quot;");}
    private static final class Env implements AutoCloseable {
        final Path root=Files.createTempDirectory("iup-progress-");final Path file=root.resolve("database.sqlite");
        final PythonSqliteBridge bridge=new PythonSqliteBridge(helper,file);final Connection c=bridge.connection();
        Env()throws IOException,SQLException {JOURNAL.initialize(c,JournalSql.Dialect.SQLITE,NOW);PROGRESS.initialize(c,JournalSql.Dialect.SQLITE);}
        @Override public void close()throws IOException,SQLException {
            try{bridge.close();}finally{try(var paths=Files.walk(root)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
        }
    }
    private static long number(Connection c,String sql)throws SQLException {try(var s=c.createStatement();var r=s.executeQuery(sql)){if(!r.next())throw new SQLException("no row");return r.getLong(1);}}
    private static void execute(Connection c,String sql)throws SQLException {try(var s=c.createStatement()){s.executeUpdate(sql);}}
    private static List<AttemptRecord> claimTo(Env e,long quote,long version,long failures,boolean win,int endOffset)throws SQLException {
        var rows=ProgressFixtures.history(ProgressFixtures.plan(quote,version,failures),win);
        eq(TransactionJournal.ClaimStatus.CREATED,JOURNAL.claim(e.c,rows.getFirst().plan(),NOW).status());
        for(int i=1;i<rows.size()-endOffset;i++)check(JOURNAL.compareAndSet(e.c,rows.get(i-1),rows.get(i)));return rows;
    }
    private static HistoryPage page(Connection c,UUID player,HistoryQuery.Filter filter,int size)throws SQLException {
        return PROGRESS.page(c,new HistoryQuery(player,filter,NOW.plusSeconds(1).toEpochMilli(),Optional.empty(),size));
    }
    private static void tests() {
        test("schema.idempotent-version-indexes-wal",()->{try(var e=new Env()){PROGRESS.initialize(e.c,JournalSql.Dialect.SQLITE);eq(1L,number(e.c,"SELECT version FROM iup_progress_schema"));eq(2L,number(e.c,"PRAGMA synchronous"));eq(3L,number(e.c,"SELECT COUNT(*) FROM pragma_index_list('iup_history')"));}});
        test("schema.unknown-version-failclosed",()->{try(var e=new Env()){execute(e.c,"UPDATE iup_progress_schema SET version=99");rejected(()->PROGRESS.initialize(e.c,JournalSql.Dialect.SQLITE));}});
        test("claim.atomic-history-pity-lock-zero-statistics",()->{try(var e=new Env()){var p=ProgressFixtures.plan(1,0,0);eq(TransactionJournal.ClaimStatus.CREATED,JOURNAL.claim(e.c,p,NOW).status());for(String table:List.of("iup_attempts","iup_player_locks","iup_history","iup_pity","iup_statistics","iup_tx_events"))eq(1L,number(e.c,"SELECT COUNT(*) FROM "+table));eq(0L,PROGRESS.statistics(e.c,PLAYER).completed());eq(HistoryEntry.Outcome.NOT_ROLLED,page(e.c,PLAYER,HistoryQuery.Filter.ALL,28).rows().getFirst().outcome());}});
        test("claim.stale-pity-rolls-back-all-new-rows",()->{try(var e=new Env()){rejected(()->JOURNAL.claim(e.c,ProgressFixtures.plan(1,1,1),NOW));for(String table:List.of("iup_attempts","iup_player_locks","iup_history","iup_pity","iup_statistics","iup_tx_events"))eq(0L,number(e.c,"SELECT COUNT(*) FROM "+table));check(e.c.getAutoCommit());}});
        test("claim.no-hook-cannot-drop-pity",()->{try(var e=new Env()){var unsafe=new JdbcTransactionRepository(JS);rejected(()->unsafe.claim(e.c,ProgressFixtures.plan(1,0,0),NOW));eq(0L,number(e.c,"SELECT COUNT(*) FROM iup_attempts"));eq(0L,number(e.c,"SELECT COUNT(*) FROM iup_player_locks"));}});
        test("claim.duplicate-does-not-duplicate-history",()->{try(var e=new Env()){var p=ProgressFixtures.plan(1,0,0);JOURNAL.claim(e.c,p,NOW);eq(TransactionJournal.ClaimStatus.DUPLICATE,JOURNAL.claim(e.c,p,NOW).status());eq(1L,number(e.c,"SELECT COUNT(*) FROM iup_history"));eq(1L,number(e.c,"SELECT COUNT(*) FROM iup_tx_events"));}});
        test("claim.second-quote-same-player-is-busy",()->{try(var e=new Env()){JOURNAL.claim(e.c,ProgressFixtures.plan(1,0,0),NOW);eq(TransactionJournal.ClaimStatus.PLAYER_BUSY,JOURNAL.claim(e.c,ProgressFixtures.plan(2,0,0),NOW).status());eq(1L,number(e.c,"SELECT COUNT(*) FROM iup_attempts"));}});
        for(boolean win:List.of(true,false))test("settlement.history-at-every-cas-"+(win?"win":"loss"),()->{try(var e=new Env()){var rows=ProgressFixtures.history(ProgressFixtures.plan(1,0,0),win);JOURNAL.claim(e.c,rows.getFirst().plan(),NOW);for(int i=1;i<rows.size();i++){var r=rows.get(i);check(JOURNAL.compareAndSet(e.c,rows.get(i-1),r));var h=page(e.c,PLAYER,HistoryQuery.Filter.ALL,28).rows().getFirst();eq(r.state(),h.state());eq(r.version(),h.version());eq(r.terminal()?1L:0L,PROGRESS.statistics(e.c,PLAYER).completed());}eq(win?1L:0L,PROGRESS.statistics(e.c,PLAYER).wins());eq(win?0L:1L,PROGRESS.pity(e.c,PLAYER,ProgressFixtures.SCOPE).failures());eq(1L,PROGRESS.pity(e.c,PLAYER,ProgressFixtures.SCOPE).version());eq(1L,number(e.c,"SELECT COUNT(*) FROM iup_completions"));check(JOURNAL.activeAttempt(e.c,PLAYER).isEmpty());}});
        test("settlement.repeated-cas-does-not-increment-twice",()->{try(var e=new Env()){var rows=claimTo(e,1,0,0,false,0);check(!JOURNAL.compareAndSet(e.c,rows.get(rows.size()-2),rows.getLast()));eq(1L,PROGRESS.statistics(e.c,PLAYER).completed());eq(1L,PROGRESS.pity(e.c,PLAYER,ProgressFixtures.SCOPE).failures());}});
        test("settlement.failures-increment-win-resets",()->{try(var e=new Env()){claimTo(e,1,0,0,false,0);claimTo(e,2,1,1,false,0);eq(2L,PROGRESS.pity(e.c,PLAYER,ProgressFixtures.SCOPE).failures());claimTo(e,3,2,2,true,0);var s=PROGRESS.pity(e.c,PLAYER,ProgressFixtures.SCOPE);eq(3L,s.version());eq(0L,s.failures());eq(new PlayerStatistics(PLAYER,3,1,2),PROGRESS.statistics(e.c,PLAYER));}});
        test("settlement.expired-abort-not-loss-or-pity",()->{try(var e=new Env()){var p=ProgressFixtures.plan(1,0,0);var r=JOURNAL.claim(e.c,p,NOW).record().orElseThrow();var abort=new TransactionMachine().next(r,p.expiresAt()).next();check(JOURNAL.compareAndSet(e.c,r,abort));eq(0L,PROGRESS.statistics(e.c,PLAYER).completed());eq(0L,PROGRESS.pity(e.c,PLAYER,ProgressFixtures.SCOPE).version());eq(0L,number(e.c,"SELECT COUNT(*) FROM iup_completions"));check(page(e.c,PLAYER,HistoryQuery.Filter.UNFINISHED,28).rows().isEmpty());}});
        test("settlement.uncertain-pending-loss-keeps-lock-no-stat",()->{try(var e=new Env()){var rows=ProgressFixtures.history(ProgressFixtures.plan(1,0,0),false);JOURNAL.claim(e.c,rows.getFirst().plan(),NOW);int stop=0;for(int i=1;i<rows.size();i++){check(JOURNAL.compareAndSet(e.c,rows.get(i-1),rows.get(i)));if(rows.get(i).state()==AttemptRecord.State.OUTCOME_COMMITTED){stop=i;break;}}var frozen=new TransactionMachine().quarantine(rows.get(stop),NOW.plusSeconds(1),"TEST_UNCERTAIN");check(JOURNAL.compareAndSet(e.c,rows.get(stop),frozen));var h=page(e.c,PLAYER,HistoryQuery.Filter.LOSS,28).rows().getFirst();check(!h.settled());eq(AttemptRecord.State.RECONCILIATION_REQUIRED,h.state());eq(0L,PROGRESS.statistics(e.c,PLAYER).completed());check(JOURNAL.activeAttempt(e.c,PLAYER).isPresent());}});
        test("atomic.hook-error-after-counters-rolls-back-unlock-event-all",()->{try(var e=new Env()){var rows=claimTo(e,1,0,0,false,1);var previous=rows.get(rows.size()-2);long events=number(e.c,"SELECT COUNT(*) FROM iup_tx_events");var failing=new JdbcTransactionRepository(JS,new JournalCommitHook(){public void claimed(Connection c,AttemptRecord r)throws SQLException{PROGRESS.claimed(c,r);}public void transitioned(Connection c,AttemptRecord a,AttemptRecord b)throws SQLException{PROGRESS.transitioned(c,a,b);if(b.terminal())throw new SQLException("synthetic after hook failure");}});rejected(()->failing.compareAndSet(e.c,previous,rows.getLast()));eq(previous.version(),JOURNAL.find(e.c,previous.id()).orElseThrow().version());eq(previous.version(),page(e.c,PLAYER,HistoryQuery.Filter.ALL,28).rows().getFirst().version());eq(0L,PROGRESS.statistics(e.c,PLAYER).completed());eq(0L,PROGRESS.pity(e.c,PLAYER,ProgressFixtures.SCOPE).failures());eq(0L,number(e.c,"SELECT COUNT(*) FROM iup_completions"));eq(events,number(e.c,"SELECT COUNT(*) FROM iup_tx_events"));check(JOURNAL.activeAttempt(e.c,PLAYER).isPresent());check(JOURNAL.compareAndSet(e.c,previous,rows.getLast()));eq(1L,PROGRESS.statistics(e.c,PLAYER).completed());}});
        test("atomic.pity-changed-during-attempt-refuses-completion",()->{try(var e=new Env()){var rows=claimTo(e,1,0,0,false,1);execute(e.c,"UPDATE iup_pity SET version=2,failures=2");rejected(()->JOURNAL.compareAndSet(e.c,rows.get(rows.size()-2),rows.getLast()));eq(0L,PROGRESS.statistics(e.c,PLAYER).completed());eq(rows.get(rows.size()-2).version(),JOURNAL.find(e.c,rows.getFirst().id()).orElseThrow().version());check(JOURNAL.activeAttempt(e.c,PLAYER).isPresent());}});
        test("atomic.completion-tombstone-conflict-rolls-back",()->{try(var e=new Env()){var rows=claimTo(e,1,0,0,false,1);try(var s=e.c.prepareStatement(PS.insertReceipt())){s.setString(1,rows.getLast().id().toString());s.setString(2,PLAYER.toString());s.setString(3,"0".repeat(64));s.setString(4,"0".repeat(64));s.setLong(5,NOW.toEpochMilli());s.executeUpdate();}rejected(()->JOURNAL.compareAndSet(e.c,rows.get(rows.size()-2),rows.getLast()));eq(0L,PROGRESS.statistics(e.c,PLAYER).completed());check(JOURNAL.activeAttempt(e.c,PLAYER).isPresent());}});
        test("atomic.history-predecessor-mismatch-refuses-cas",()->{try(var e=new Env()){var p=ProgressFixtures.plan(1,0,0);var old=JOURNAL.claim(e.c,p,NOW).record().orElseThrow();execute(e.c,"UPDATE iup_history SET state_digest='"+"0".repeat(64)+"'");rejected(()->JOURNAL.compareAndSet(e.c,old,new TransactionMachine().next(old,NOW).next()));eq(0L,JOURNAL.find(e.c,old.id()).orElseThrow().version());}});
        test("atomic.lost-commit-ack-readback-no-double-count",()->{try(var e=new Env()){var rows=claimTo(e,1,0,0,false,1);e.bridge.failAfterNextCommit=true;rejected(()->JOURNAL.compareAndSet(e.c,rows.get(rows.size()-2),rows.getLast()));eq(AttemptRecord.State.COMPLETED,JOURNAL.find(e.c,rows.getLast().id()).orElseThrow().state());eq(1L,PROGRESS.statistics(e.c,PLAYER).completed());eq(TransactionJournal.ClaimStatus.DUPLICATE,JOURNAL.claim(e.c,rows.getLast().plan(),NOW).status());check(!JOURNAL.compareAndSet(e.c,rows.get(rows.size()-2),rows.getLast()));eq(1L,PROGRESS.pity(e.c,PLAYER,ProgressFixtures.SCOPE).failures());}});
        test("read.absent-row-zero-but-missing-table-error",()->{try(var e=new Env()){eq(PlayerStatistics.empty(PLAYER),PROGRESS.statistics(e.c,PLAYER));eq(PitySnapshot.empty(PLAYER,ProgressFixtures.SCOPE),PROGRESS.pity(e.c,PLAYER,ProgressFixtures.SCOPE));execute(e.c,"DROP TABLE iup_statistics");rejected(()->PROGRESS.statistics(e.c,PLAYER));}});
        test("read.malformed-statistics-not-zero",()->{try(var e=new Env()){JOURNAL.claim(e.c,ProgressFixtures.plan(1,0,0),NOW);execute(e.c,"UPDATE iup_statistics SET wins=2");rejected(()->PROGRESS.statistics(e.c,PLAYER));}});
        test("read.keyset-owner-filter-pagination",()->{try(var e=new Env()){List<UUID> expected=new ArrayList<>();for(int i=0;i<6;i++)expected.add(claimTo(e,i+1,i,i,false,0).getLast().id());
            expected.sort(Comparator.comparing(UUID::toString).reversed());List<UUID> actual=new ArrayList<>();var q=new HistoryQuery(PLAYER,HistoryQuery.Filter.ALL,NOW.plusSeconds(1).toEpochMilli(),Optional.empty(),2);while(true){var p=PROGRESS.page(e.c,q);p.rows().forEach(r->actual.add(r.transactionId()));if(!p.hasMore())break;q=new HistoryQuery(PLAYER,q.filter(),q.upperCreatedMillis(),p.next(),2);}eq(expected,actual);check(page(e.c,new UUID(0,200),HistoryQuery.Filter.ALL,2).rows().isEmpty());eq(0,page(e.c,PLAYER,HistoryQuery.Filter.WIN,28).rows().size());eq(6,page(e.c,PLAYER,HistoryQuery.Filter.LOSS,28).rows().size());}});
        test("read.corrupt-outcome-does-not-render-success",()->{try(var e=new Env()){JOURNAL.claim(e.c,ProgressFixtures.plan(1,0,0),NOW);execute(e.c,"UPDATE iup_history SET outcome='WIN'");rejected(()->page(e.c,PLAYER,HistoryQuery.Filter.ALL,28));}});
        test("retention.bounded-terminal-only-keeps-tombstones",()->{try(var e=new Env()){claimTo(e,1,0,0,false,0);claimTo(e,2,1,1,false,0);JOURNAL.claim(e.c,ProgressFixtures.plan(3,2,2),NOW);eq(1,PROGRESS.pruneHistory(e.c,NOW.plusSeconds(1),1));eq(2L,number(e.c,"SELECT COUNT(*) FROM iup_history"));eq(2L,number(e.c,"SELECT COUNT(*) FROM iup_completions"));eq(3L,number(e.c,"SELECT COUNT(*) FROM iup_attempts"));eq(2L,PROGRESS.statistics(e.c,PLAYER).completed());eq(1,PROGRESS.pruneHistory(e.c,NOW.plusSeconds(1),100));eq(1,page(e.c,PLAYER,HistoryQuery.Filter.UNFINISHED,28).rows().size());eq(0,PROGRESS.pruneHistory(e.c,NOW.plusSeconds(1),100));rejected(()->PROGRESS.pruneHistory(e.c,NOW,1001));}});
        test("backfill.history-only-no-stats-pity-or-tombstone",()->{try(var e=new Env()){var record=ProgressFixtures.history(TransactionFixtures.plan(),true,TransactionFixtures.NOW).getLast();check(PROGRESS.backfillHistoryOnly(e.c,record));check(!PROGRESS.backfillHistoryOnly(e.c,record));eq(1L,number(e.c,"SELECT COUNT(*) FROM iup_history"));eq(0L,number(e.c,"SELECT COUNT(*) FROM iup_statistics"));eq(0L,number(e.c,"SELECT COUNT(*) FROM iup_pity"));eq(0L,number(e.c,"SELECT COUNT(*) FROM iup_completions"));}});
        test("backfill.never-overwrites-live-newer-projection",()->{try(var e=new Env()){var rows=claimTo(e,1,0,0,true,0);check(!PROGRESS.backfillHistoryOnly(e.c,rows.getFirst()));eq(rows.getLast().version(),page(e.c,PLAYER,HistoryQuery.Filter.ALL,28).rows().getFirst().version());var conflict=ProgressFixtures.plan(1,1,1);rejected(()->PROGRESS.backfillHistoryOnly(e.c,AttemptRecord.initial(conflict,NOW)));}});
        test("hook.requires-owned-transaction-and-initializer-does-not-nest",()->{try(var e=new Env()){rejected(()->PROGRESS.claimed(e.c,AttemptRecord.initial(ProgressFixtures.plan(1,0,0),NOW)));e.c.setAutoCommit(false);rejected(()->PROGRESS.initialize(e.c,JournalSql.Dialect.SQLITE));rejected(()->PROGRESS.pruneHistory(e.c,NOW,1));e.c.rollback();e.c.setAutoCommit(true);}});
        test("legacy.add-hook-on-next-transition-no-plan-rewrite",()->{try(var e=new Env()){var oldJournal=new JdbcTransactionRepository(JS);var p=TransactionFixtures.plan();var r=oldJournal.claim(e.c,p,TransactionFixtures.NOW).record().orElseThrow();var next=new TransactionMachine().next(r,TransactionFixtures.NOW).next();check(JOURNAL.compareAndSet(e.c,r,next));eq(1,page(e.c,p.playerId(),HistoryQuery.Filter.ALL,28).rows().size());eq(JournalCodec.planDigest(p),JournalCodec.planDigest(JOURNAL.find(e.c,p.attemptId()).orElseThrow().plan()));}});
        test("reopen.pity-stats-and-journal-bytes-survive",()->{try(var e=new Env()){var rows=claimTo(e,1,0,0,false,0);try(var other=new PythonSqliteBridge(helper,e.file)){eq(1L,PROGRESS.statistics(other.connection(),PLAYER).completed());eq(1L,PROGRESS.pity(other.connection(),PLAYER,ProgressFixtures.SCOPE).failures());check(Arrays.equals(JournalCodec.encodeRecord(rows.getLast()),JournalCodec.encodeRecord(JOURNAL.find(other.connection(),rows.getLast().id()).orElseThrow())));}}});
        test("concurrency.separate-connections-one-player-one-claim",()->{try(var e=new Env();var other=new PythonSqliteBridge(helper,e.file);var workers=Executors.newFixedThreadPool(2)){var barrier=new CyclicBarrier(2);var a=workers.submit(()->{barrier.await(5,TimeUnit.SECONDS);return JOURNAL.claim(e.c,ProgressFixtures.plan(1,0,0),NOW).status();});var b=workers.submit(()->{barrier.await(5,TimeUnit.SECONDS);return JOURNAL.claim(other.connection(),ProgressFixtures.plan(2,0,0),NOW).status();});var results=List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));eq(1L,results.stream().filter(v->v==TransactionJournal.ClaimStatus.CREATED).count());eq(1L,results.stream().filter(v->v==TransactionJournal.ClaimStatus.PLAYER_BUSY).count());eq(1L,number(e.c,"SELECT COUNT(*) FROM iup_history"));}});
        for(boolean after:List.of(false,true))test("process-exit."+(after?"after":"before")+"-terminal-commit",()->crashTest(after));
    }
    private static void crashTest(boolean after)throws Exception {
        try(var e=new Env()) {
            var rows=claimTo(e,1,0,0,false,1);var previous=rows.get(rows.size()-2);
            var command=List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),
                    Phase08SqliteRepositoryTest.class.getName(),"--crash-child",helper.toAbsolutePath().toString(),e.file.toString(),after?"after":"before");
            var child=new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.INHERIT).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            if(!child.waitFor(15,TimeUnit.SECONDS)){child.descendants().forEach(ProcessHandle::destroyForcibly);child.destroyForcibly();throw new AssertionError("crash child timed out");}
            eq(73,child.exitValue());
            eq(after?1L:0L,PROGRESS.statistics(e.c,PLAYER).completed());eq(after?1L:0L,PROGRESS.pity(e.c,PLAYER,ProgressFixtures.SCOPE).failures());
            eq(after?AttemptRecord.State.COMPLETED:previous.state(),JOURNAL.find(e.c,previous.id()).orElseThrow().state());
            eq(!after,JOURNAL.activeAttempt(e.c,PLAYER).isPresent());eq(after?1L:0L,number(e.c,"SELECT COUNT(*) FROM iup_completions"));
            if(!after)check(JOURNAL.compareAndSet(e.c,previous,rows.getLast()));else check(!JOURNAL.compareAndSet(e.c,previous,rows.getLast()));
            eq(1L,PROGRESS.statistics(e.c,PLAYER).completed());
        }
    }
    private static void crashChild(Path database,boolean after)throws Exception {
        // Runtime.halt bypasses finally/bridge.close. EOF causes SQLite helper to close and rollback any uncommitted txn.
        try(var bridge=new PythonSqliteBridge(helper,database)) {
            var rows=ProgressFixtures.history(ProgressFixtures.plan(1,0,0),false);var prior=rows.get(rows.size()-2);
            JournalCommitHook hook=after?PROGRESS:new JournalCommitHook(){
                public void claimed(Connection c,AttemptRecord r)throws SQLException{PROGRESS.claimed(c,r);}
                public void transitioned(Connection c,AttemptRecord a,AttemptRecord b)throws SQLException{PROGRESS.transitioned(c,a,b);Runtime.getRuntime().halt(73);}
            };
            var journal=new JdbcTransactionRepository(JS,hook);
            if(!journal.compareAndSet(bridge.connection(),prior,rows.getLast()))throw new AssertionError("terminal CAS missing");
            Runtime.getRuntime().halt(73);
        }
    }
}
