package vn.ledat.itemupgrader.test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import vn.ledat.itemupgrader.storage.management.*;
import vn.ledat.itemupgrader.transaction.model.*;
import vn.ledat.itemupgrader.transaction.storage.*;
import vn.ledat.itemupgrader.output.storage.OutputSql;

/** Actual repositories and SQLite SQL. Default transport is TEST ONLY PythonSqliteBridge.
 * -Diup.test.jdbc=true plus user-supplied JDBC/SLF4J classpath runs the SAME assertions on a real driver.
 * No dependency is downloaded by this class. Neither mode tests Paper/Platform or native item effects. */
public final class Phase09SqliteRepositoryTest {
    @FunctionalInterface interface Checked { void run() throws Exception; }
    private static final Map<String,Checked> TESTS=new LinkedHashMap<>();
    private static int assertions;
    private static Path helper;
    private static final Instant NOW=ProgressFixtures.NOW;
    private static final boolean DRIVER=Boolean.getBoolean("iup.test.jdbc");
    private static final JdbcStorageBootstrap BUNDLE=JdbcStorageBootstrap.prefixed("iup_");
    private static final JdbcRecoveryInspector INSPECTOR=new JdbcRecoveryInspector(BUNDLE.journalSql().tables());
    private Phase09SqliteRepositoryTest(){}
    public static void main(String[] args)throws Exception {
        helper=Path.of(args.length>0?args[0]:"scripts/testing/sqlite_bridge.py");
        tests();int failures=0;var xml=new StringBuilder();
        for(var entry:TESTS.entrySet()) {
            Throwable failure=null;try{entry.getValue().run();}catch(Exception|AssertionError error){failure=error;failures++;error.printStackTrace(System.err);}
            System.out.println((failure==null?"PASS ":"FAIL ")+entry.getKey());
            xml.append("<testcase classname=\"Phase09SQLite\" name=\"").append(escape(entry.getKey())).append("\">");
            if(failure!=null)xml.append("<failure message=\"").append(escape(failure.toString())).append("\"/>");xml.append("</testcase>\n");
        }
        String mode=DRIVER?"USER_SUPPLIED_JDBC_DRIVER":"TEST_ONLY_PYTHON_BRIDGE";
        Path report=Path.of(args.length>1?args[1]:"core/build/reports/phase09-sqlite.xml");Files.createDirectories(report.toAbsolutePath().getParent());
        Files.writeString(report,"<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuite name=\"Phase09SQLite\" tests=\""+TESTS.size()+"\" failures=\""+failures+"\" errors=\"0\">\n"+xml
                +"<system-out>assertions="+assertions+" mode="+mode+"</system-out></testsuite>\n",StandardCharsets.UTF_8);
        System.out.println("SQLITE_RESULT tests="+TESTS.size()+" assertions="+assertions+" failures="+failures+" mode="+mode);
        if(failures!=0)throw new AssertionError("storage SQLite tests failed");
    }
    private static void test(String name,Checked body){if(TESTS.put(name,body)!=null)throw new IllegalArgumentException("duplicate test");}
    private static void check(boolean value){assertions++;if(!value)throw new AssertionError("expected true");}
    private static void eq(Object expected,Object actual){assertions++;if(!Objects.equals(expected,actual))throw new AssertionError("expected "+expected+", got "+actual);}
    private static String escape(String text){return text.replace("&","&amp;").replace("<","&lt;").replace("\"","&quot;");}
    private static void rejects(Checked body)throws Exception{assertions++;try{body.run();}catch(SQLException|IllegalArgumentException expected){return;}throw new AssertionError("expected rejection");}
    private static void problem(SchemaProblem.Code code,Checked body)throws Exception {assertions++;try{body.run();}catch(SchemaProblem actual){eq(code,actual.code());return;}throw new AssertionError("expected schema problem "+code);}
    private static final class Env implements AutoCloseable {
        final Path root=Files.createTempDirectory("iup-storage-test-");
        final Path path=root.resolve("database.sqlite");
        final PythonSqliteBridge bridge;
        final Connection c;
        Env(boolean initialize)throws IOException,SQLException {
            if(DRIVER){bridge=null;c=DriverManager.getConnection("jdbc:sqlite:"+path.toAbsolutePath());
                try(var s=c.createStatement()){s.executeUpdate("PRAGMA journal_mode=WAL");s.executeUpdate("PRAGMA synchronous=FULL");s.executeUpdate("PRAGMA busy_timeout=5000");}}
            else{bridge=new PythonSqliteBridge(helper,path);c=bridge.connection();}
            if(initialize)BUNDLE.prepare(c,StorageSettings.Mode.INITIALIZE,NOW);
        }
        @Override public void close()throws IOException,SQLException {
            try{if(bridge==null)c.close();else bridge.close();}finally{try(var paths=Files.walk(root)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
        }
    }
    private static void execute(Connection c,String sql)throws SQLException{try(var s=c.createStatement()){s.executeUpdate(sql);}}
    private static long number(Connection c,String sql)throws SQLException{try(var s=c.createStatement();var r=s.executeQuery(sql)){if(!r.next())throw new SQLException("no test value");return r.getLong(1);}}
    private static long tables(Connection c)throws SQLException{return number(c,"SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name LIKE 'iup_%'");}
    private static AttemptPlan plan(int id) {
        var p=ProgressFixtures.plan(id,0,0);
        return new AttemptPlan(new UUID(55,id),p.sessionId(),p.quoteId(),p.configRevision(),p.catalogGeneration(),p.sourceSlot(),p.source(),p.targetId(),p.target(),p.sourceTotal(),p.targetTotal(),p.terms(),p.feeItems(),p.expiresAt());
    }
    private static List<AttemptRecord> completed(Env e,int id,boolean win)throws SQLException {
        var rows=ProgressFixtures.history(plan(id),win);
        eq(TransactionJournal.ClaimStatus.CREATED,BUNDLE.journal().claim(e.c,rows.getFirst().plan(),NOW).status());
        for(int i=1;i<rows.size();i++)check(BUNDLE.journal().compareAndSet(e.c,rows.get(i-1),rows.get(i)));
        return rows;
    }
    private static void tests() {
        test("verify.empty-db-does-not-create",()->{try(var e=new Env(false)){problem(SchemaProblem.Code.MISSING_TABLE,()->BUNDLE.prepare(e.c,StorageSettings.Mode.VERIFY,NOW));eq(0L,tables(e.c));}});
        test("initialize.twelve-tables-four-named-indexes",()->{try(var e=new Env(false)){var r=BUNDLE.prepare(e.c,StorageSettings.Mode.INITIALIZE,NOW);eq(12,r.tables());eq(4,r.indexes());eq(12L,tables(e.c));check(r.initializeRequested());check(e.c.getAutoCommit());eq(4L,number(e.c,"SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name LIKE 'iup_%'"));}});
        test("initialize.idempotent-does-not-clear-data",()->{try(var e=new Env(true)){var p=plan(1);BUNDLE.journal().claim(e.c,p,NOW);BUNDLE.prepare(e.c,StorageSettings.Mode.INITIALIZE,NOW);eq(1L,number(e.c,"SELECT COUNT(*) FROM iup_attempts"));eq(1L,number(e.c,"SELECT COUNT(*) FROM iup_history"));check(BUNDLE.journal().activeAttempt(e.c,p.playerId()).isPresent());}});
        test("verify.works-on-query-only-connection",()->{try(var e=new Env(true)){long before=number(e.c,"SELECT total_changes()");execute(e.c,"PRAGMA query_only=ON");var report=BUNDLE.prepare(e.c,StorageSettings.Mode.VERIFY,NOW);check(!report.initializeRequested());eq(before,number(e.c,"SELECT total_changes()"));}});
        test("initialize.readonly-db-cannot-be-labelled-ready",()->{try(var e=new Env(false)){execute(e.c,"PRAGMA query_only=ON");rejects(()->BUNDLE.prepare(e.c,StorageSettings.Mode.INITIALIZE,NOW));eq(0L,tables(e.c));}});
        test("schema.borrowed-transaction-not-committed",()->{try(var e=new Env(true)){e.c.setAutoCommit(false);problem(SchemaProblem.Code.BORROWED_TRANSACTION,()->BUNDLE.prepare(e.c,StorageSettings.Mode.VERIFY,NOW));check(!e.c.getAutoCommit());e.c.rollback();e.c.setAutoCommit(true);}});
        test("schema.off-never-initializes",()->{try(var e=new Env(false)){rejects(()->BUNDLE.prepare(e.c,StorageSettings.Mode.OFF,NOW));eq(0L,tables(e.c));}});
        test("schema.all-versions-preflight-before-any-create",()->{try(var e=new Env(true)){execute(e.c,"UPDATE iup_tx_schema SET version=99");execute(e.c,"DROP TABLE iup_outputs");problem(SchemaProblem.Code.UNSUPPORTED_VERSION,()->BUNDLE.prepare(e.c,StorageSettings.Mode.INITIALIZE,NOW));eq(11L,tables(e.c));}});
        test("schema.fractional-version-never-truncated-to-one",()->{try(var e=new Env(true)){
            execute(e.c,"UPDATE iup_tx_schema SET version=1.5");
            problem(SchemaProblem.Code.UNSUPPORTED_VERSION,()->BUNDLE.prepare(e.c,StorageSettings.Mode.INITIALIZE,NOW));
            eq(12L,tables(e.c));
        }});
        test("schema.unknown-component-id-not-relabelled",()->{try(var e=new Env(true)){execute(e.c,"UPDATE iup_tx_schema SET component='future-v9'");problem(SchemaProblem.Code.UNSUPPORTED_VERSION,()->BUNDLE.prepare(e.c,StorageSettings.Mode.INITIALIZE,NOW));eq(1L,number(e.c,"SELECT COUNT(*) FROM iup_tx_schema"));}});
        test("schema.versioned-table-loss-not-empty-replacement",()->{try(var e=new Env(true)){execute(e.c,"DROP TABLE iup_statistics");problem(SchemaProblem.Code.MISSING_TABLE,()->BUNDLE.prepare(e.c,StorageSettings.Mode.INITIALIZE,NOW));eq(11L,tables(e.c));}});
        test("schema.unversioned-populated-component-not-blessed",()->{try(var e=new Env(true)){BUNDLE.journal().claim(e.c,plan(1),NOW);execute(e.c,"DELETE FROM iup_progress_schema");problem(SchemaProblem.Code.UNVERSIONED_DATA,()->BUNDLE.prepare(e.c,StorageSettings.Mode.INITIALIZE,NOW));eq(0L,number(e.c,"SELECT COUNT(*) FROM iup_progress_schema"));}});
        test("schema.partial-empty-install-can-retry",()->{try(var e=new Env(false)){execute(e.c,BUNDLE.journalSql().ddl(JournalSql.Dialect.SQLITE).getFirst());execute(e.c,BUNDLE.journalSql().ddl(JournalSql.Dialect.SQLITE).get(1));eq(12,BUNDLE.prepare(e.c,StorageSettings.Mode.INITIALIZE,NOW).tables());}});
        test("schema.missing-index-verify-fails-initialize-adds",()->{try(var e=new Env(true)){execute(e.c,"DROP INDEX iup_attempts_player_time");problem(SchemaProblem.Code.INDEX,()->BUNDLE.prepare(e.c,StorageSettings.Mode.VERIFY,NOW));eq(4,BUNDLE.prepare(e.c,StorageSettings.Mode.INITIALIZE,NOW).indexes());}});
        test("schema.wrong-index-order-not-dropped-or-replaced",()->{try(var e=new Env(true)){execute(e.c,"DROP INDEX iup_attempts_player_time");execute(e.c,"CREATE INDEX iup_attempts_player_time ON iup_attempts(tx_id,created_at,player_uuid)");problem(SchemaProblem.Code.INDEX,()->BUNDLE.prepare(e.c,StorageSettings.Mode.INITIALIZE,NOW));eq(1L,number(e.c,"SELECT COUNT(*) FROM pragma_index_info('iup_attempts_player_time') WHERE seqno=0 AND name='tx_id'"));}});
        test("schema.missing-column-rejected-before-version-write",()->{try(var e=new Env(true)){execute(e.c,"ALTER TABLE iup_statistics RENAME COLUMN wins TO other_wins");problem(SchemaProblem.Code.COLUMNS,()->BUNDLE.prepare(e.c,StorageSettings.Mode.INITIALIZE,NOW));eq(12L,tables(e.c));}});
        test("schema.primary-key-required",()->{try(var e=new Env(true)){execute(e.c,"DROP TABLE iup_statistics");execute(e.c,"CREATE TABLE iup_statistics(player_uuid VARCHAR(36),completed BIGINT NOT NULL,wins BIGINT NOT NULL,losses BIGINT NOT NULL)");problem(SchemaProblem.Code.PRIMARY_KEY,()->BUNDLE.prepare(e.c,StorageSettings.Mode.VERIFY,NOW));}});
        test("schema.unique-idempotency-key-required",()->{try(var e=new Env(true)){execute(e.c,"DROP TABLE iup_attempts");execute(e.c,BUNDLE.journalSql().ddl(JournalSql.Dialect.SQLITE).get(1).replace("idempotency_key VARCHAR(128) NOT NULL UNIQUE","idempotency_key VARCHAR(128) NOT NULL"));problem(SchemaProblem.Code.UNIQUE_KEY,()->BUNDLE.prepare(e.c,StorageSettings.Mode.INITIALIZE,NOW));}});
        test("schema.partial-unique-index-is-not-global-guard",()->{try(var e=new Env(true)){execute(e.c,"DROP TABLE iup_attempts");execute(e.c,BUNDLE.journalSql().ddl(JournalSql.Dialect.SQLITE).get(1).replace("idempotency_key VARCHAR(128) NOT NULL UNIQUE","idempotency_key VARCHAR(128) NOT NULL"));execute(e.c,"CREATE UNIQUE INDEX partial_id ON iup_attempts(idempotency_key) WHERE state='PREPARED'");problem(SchemaProblem.Code.UNIQUE_KEY,()->BUNDLE.prepare(e.c,StorageSettings.Mode.INITIALIZE,NOW));}});
        test("schema.view-cannot-replace-table",()->{try(var e=new Env(true)){execute(e.c,"DROP TABLE iup_statistics");execute(e.c,"CREATE VIEW iup_statistics AS SELECT '' AS player_uuid,0 AS completed,0 AS wins,0 AS losses");problem(SchemaProblem.Code.WRONG_TABLE_TYPE,()->BUNDLE.prepare(e.c,StorageSettings.Mode.VERIFY,NOW));}});
        test("schema.metadata-pattern-escaped-and-exact",()->{try(var e=new Env(false)){execute(e.c,"CREATE TABLE iupXtx_schema(component TEXT,version INTEGER,updated_at BIGINT)");problem(SchemaProblem.Code.MISSING_TABLE,()->BUNDLE.prepare(e.c,StorageSettings.Mode.VERIFY,NOW));}});
        test("bundle.journal-always-has-progress-hook",()->{try(var e=new Env(true)){var rows=completed(e,1,false);var p=rows.getFirst().plan();eq(1L,BUNDLE.progress().statistics(e.c,p.playerId()).completed());eq(1L,BUNDLE.progress().pity(e.c,p.playerId(),ProgressFixtures.SCOPE).failures());eq(1L,number(e.c,"SELECT COUNT(*) FROM iup_completions"));check(BUNDLE.journal().activeAttempt(e.c,p.playerId()).isEmpty());}});
        test("recovery.bounded-pages-without-duplicate-rows",()->{try(var e=new Env(true)){for(int i=1;i<=7;i++)BUNDLE.journal().claim(e.c,plan(i),NOW);Set<UUID> seen=new HashSet<>();Optional<UUID> cursor=Optional.empty();int pages=0;do{var page=INSPECTOR.page(e.c,cursor,2);check(page.rows().size()<=2);for(var r:page.rows()){check(seen.add(r.transactionId()));eq(RecoveryPage.LockStatus.OWNED,r.lock());}cursor=page.nextCursor();pages++;}while(cursor.isPresent()&&pages<10);eq(7,seen.size());eq(4,pages);eq(7L,number(e.c,"SELECT COUNT(*) FROM iup_player_locks"));}});
        test("recovery.never-reads-item-payloads",()->{try(var e=new Env(true)){BUNDLE.journal().claim(e.c,plan(1),NOW);execute(e.c,"UPDATE iup_attempts SET plan_payload=X'00',payload=X'00'");long before=number(e.c,"SELECT total_changes()");var page=INSPECTOR.page(e.c,Optional.empty(),16);eq(1,page.rows().size());eq(before,number(e.c,"SELECT total_changes()"));rejects(()->BUNDLE.journal().find(e.c,plan(1).attemptId()));}});
        test("recovery.missing-lock-is-visible-not-repaired",()->{try(var e=new Env(true)){BUNDLE.journal().claim(e.c,plan(1),NOW);execute(e.c,"DELETE FROM iup_player_locks");var page=INSPECTOR.page(e.c,Optional.empty(),16);eq(RecoveryPage.LockStatus.MISSING,page.rows().getFirst().lock());eq(0L,number(e.c,"SELECT COUNT(*) FROM iup_player_locks"));}});
        test("recovery.conflicting-lock-is-visible-not-replaced",()->{try(var e=new Env(true)){BUNDLE.journal().claim(e.c,plan(1),NOW);execute(e.c,"UPDATE iup_player_locks SET tx_id='00000000-0000-0000-0000-000000000009'");eq(RecoveryPage.LockStatus.CONFLICT,INSPECTOR.page(e.c,Optional.empty(),16).rows().getFirst().lock());}});
        test("recovery.completed-excluded-unfinished-stays",()->{try(var e=new Env(true)){completed(e,1,true);BUNDLE.journal().claim(e.c,plan(2),NOW);var page=INSPECTOR.page(e.c,Optional.empty(),16);eq(1,page.rows().size());eq(plan(2).attemptId(),page.rows().getFirst().transactionId());}});
        test("recovery.unknown-state-fails-not-empty",()->{try(var e=new Env(true)){BUNDLE.journal().claim(e.c,plan(1),NOW);execute(e.c,"UPDATE iup_attempts SET state='FUTURE_STATE'");rejects(()->INSPECTOR.page(e.c,Optional.empty(),16));eq(1L,number(e.c,"SELECT COUNT(*) FROM iup_attempts"));}});
        test("recovery.page-input-bounds",()->{try(var e=new Env(true)){for(int n:List.of(0,51,Integer.MAX_VALUE))rejects(()->INSPECTOR.page(e.c,Optional.empty(),n));}});
        test("retention.one-batch-terminal-history-only",()->{try(var e=new Env(true)){completed(e,1,true);completed(e,2,false);BUNDLE.journal().claim(e.c,plan(3),NOW);eq(1,BUNDLE.progress().pruneHistory(e.c,NOW.plus(Duration.ofDays(91)),1));eq(2L,number(e.c,"SELECT COUNT(*) FROM iup_history"));eq(3L,number(e.c,"SELECT COUNT(*) FROM iup_attempts"));eq(2L,number(e.c,"SELECT COUNT(*) FROM iup_completions"));eq(3L,number(e.c,"SELECT COUNT(*) FROM iup_pity"));eq(1L,number(e.c,"SELECT COUNT(*) FROM iup_player_locks"));}});
        test("retention.bad-terminal-flag-does-not-delete-active-state",()->{try(var e=new Env(true)){BUNDLE.journal().claim(e.c,plan(1),NOW);execute(e.c,"UPDATE iup_history SET terminal=1");eq(0,BUNDLE.progress().pruneHistory(e.c,NOW.plus(Duration.ofDays(91)),200));eq(1L,number(e.c,"SELECT COUNT(*) FROM iup_history"));}});
        test("retention.completed-counters-survive-history-expiry",()->{try(var e=new Env(true)){completed(e,1,false);eq(1,BUNDLE.progress().pruneHistory(e.c,NOW.plus(Duration.ofDays(91)),20));eq(1L,BUNDLE.progress().statistics(e.c,plan(1).playerId()).completed());eq(1L,BUNDLE.progress().pity(e.c,plan(1).playerId(),ProgressFixtures.SCOPE).version());eq(1L,number(e.c,"SELECT COUNT(*) FROM iup_completions"));eq(0,BUNDLE.progress().pruneHistory(e.c,NOW.plus(Duration.ofDays(91)),20));}});
        test("retention.keeps-reconciliation-even-if-terminal-flag-poisoned",()->{try(var e=new Env(true)){BUNDLE.journal().claim(e.c,plan(1),NOW);execute(e.c,"UPDATE iup_history SET state='RECONCILIATION_REQUIRED',terminal=1");eq(0,BUNDLE.progress().pruneHistory(e.c,NOW.plus(Duration.ofDays(91)),20));eq(1L,number(e.c,"SELECT COUNT(*) FROM iup_history"));}});
    }
}
