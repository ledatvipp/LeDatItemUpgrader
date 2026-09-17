package vn.ledat.itemupgrader.test;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import vn.ledat.itemupgrader.storage.management.*;
import vn.ledat.itemupgrader.transaction.model.AttemptRecord;
import vn.ledat.itemupgrader.transaction.storage.JournalSql;

/** Dependency-free lifecycle/contracts only. Fake clock/futures are not JDBC or server integration. */
public final class Phase09SelfTest {
    @FunctionalInterface interface Checked { void run() throws Exception; }
    private static final Map<String,Checked> TESTS = new LinkedHashMap<>();
    private static int assertions;
    private static final Instant NOW = Instant.parse("2026-09-17T00:00:00Z");
    private static final SchemaReport REPORT = new SchemaReport(JournalSql.Dialect.SQLITE, 12, 4, false);
    private Phase09SelfTest() {}
    public static void main(String[] args) throws Exception {
        tests(); int failures = 0; var xml = new StringBuilder();
        for (var test : TESTS.entrySet()) {
            Throwable failure = null;
            try { test.getValue().run(); } catch (Exception | AssertionError error) { failure = error; failures++; error.printStackTrace(System.err); }
            System.out.println((failure == null ? "PASS " : "FAIL ") + test.getKey());
            xml.append("<testcase classname=\"Phase09SelfTest\" name=\"").append(escape(test.getKey())).append("\">");
            if (failure != null) xml.append("<failure message=\"").append(escape(failure.toString())).append("\"/>");
            xml.append("</testcase>\n");
        }
        Path output = Path.of(args.length == 0 ? "core/build/reports/phase09-self-test.xml" : args[0]);
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuite name=\"Phase09\" tests=\"" + TESTS.size()
                + "\" failures=\"" + failures + "\" errors=\"0\">\n" + xml + "<system-out>assertions=" + assertions + "</system-out></testsuite>\n", StandardCharsets.UTF_8);
        System.out.println("RESULT tests=" + TESTS.size() + " assertions=" + assertions + " failures=" + failures);
        if (failures != 0) throw new AssertionError("Phase 9 failures=" + failures);
    }
    private static void test(String name, Checked body) { if (TESTS.put(name, body) != null) throw new IllegalArgumentException("duplicate test"); }
    private static void check(boolean value) { assertions++; if (!value) throw new AssertionError("expected true"); }
    private static void eq(Object expected, Object actual) { assertions++; if (!Objects.equals(expected, actual)) throw new AssertionError("expected " + expected + ", got " + actual); }
    private static void rejects(Checked body) throws Exception { assertions++; try { body.run(); } catch (IllegalArgumentException | SQLException expected) { return; } throw new AssertionError("expected rejection"); }
    private static String escape(String text) { return text.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;"); }
    private static StorageSettings settings(StorageSettings.Mode mode, boolean prune) {
        return new StorageSettings(mode, Duration.ofSeconds(10), prune, Duration.ofMinutes(1), 90, 20);
    }
    private static final class Time extends Clock {
        volatile Instant now = NOW;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { if (!zone.equals(ZoneOffset.UTC)) throw new IllegalArgumentException("test zone"); return this; }
        @Override public Instant instant() { return now; }
    }
    private static final class Port implements StorageController.Port {
        final List<CompletableFuture<SchemaReport>> schemas = Collections.synchronizedList(new ArrayList<>());
        final List<CompletableFuture<Integer>> prunes = Collections.synchronizedList(new ArrayList<>());
        Instant before; int maximum;
        @Override public CompletionStage<SchemaReport> prepare(StorageSettings.Mode mode, Instant now) {
            var future = new CompletableFuture<SchemaReport>(); schemas.add(future); return future;
        }
        @Override public CompletionStage<Integer> prune(Instant deadline, int limit) {
            before = deadline; maximum = limit; var future = new CompletableFuture<Integer>(); prunes.add(future); return future;
        }
    }
    private static final class Env implements AutoCloseable {
        final Port port = new Port(); final Time time = new Time(); final List<Throwable> errors = new CopyOnWriteArrayList<>();
        final StorageController controller = new StorageController(port, time, errors::add);
        Env() { controller.configure(1, settings(StorageSettings.Mode.VERIFY, false), time.now); }
        void tick() { controller.tick(time.now); }
        void ready(boolean prune) {
            if (prune) controller.configure(2, settings(StorageSettings.Mode.VERIFY, true), time.now);
            tick(); port.schemas.getLast().complete(REPORT);
        }
        void after(long seconds) { time.now = NOW.plusSeconds(seconds); tick(); }
        @Override public void close() { controller.close(); }
    }
    private static RecoveryPage.Row row(long id) {
        return new RecoveryPage.Row(new UUID(0,id),new UUID(0,99),AttemptRecord.State.RESERVING,1,NOW,NOW,RecoveryPage.LockStatus.OWNED);
    }
    private static void tests() {
        test("config.off-is-default-and-no-retention", () -> { var s = StorageSettings.off(); eq(StorageSettings.Mode.OFF,s.mode());check(!s.pruneEnabled()); });
        test("config.off-cannot-delete-history", () -> rejects(() -> settings(StorageSettings.Mode.OFF,true)));
        test("config.duration-and-batch-bounds", () -> {
            for (int timeout : List.of(0,4,121)) rejects(() -> new StorageSettings(StorageSettings.Mode.VERIFY,Duration.ofSeconds(timeout),false,Duration.ofMinutes(1),90,20));
            for (int batch : List.of(0,1001)) rejects(() -> new StorageSettings(StorageSettings.Mode.VERIFY,Duration.ofSeconds(10),true,Duration.ofMinutes(1),90,batch));
            for (int days : List.of(0,3651)) rejects(() -> new StorageSettings(StorageSettings.Mode.VERIFY,Duration.ofSeconds(10),true,Duration.ofMinutes(1),days,20));
            for (int interval : List.of(0,59,86401)) rejects(() -> new StorageSettings(StorageSettings.Mode.VERIFY,Duration.ofSeconds(10),false,Duration.ofSeconds(interval),90,20));
        });
        test("config.report-cannot-be-partial", () -> {rejects(() -> new SchemaReport(JournalSql.Dialect.SQLITE,11,4,false));rejects(() -> new SchemaReport(JournalSql.Dialect.SQLITE,12,3,false));});
        test("lifecycle.only-tick-dispatches", () -> {try(var e=new Env()){eq(0,e.port.schemas.size());eq(StorageController.State.WAITING,e.controller.view().state());e.tick();eq(1,e.port.schemas.size());check(!e.controller.view().readable(1));}});
        test("lifecycle.off-no-sql", () -> {try(var e=new Env()){e.controller.configure(2,StorageSettings.off(),NOW);for(int i=0;i<50;i++)e.after(i);eq(0,e.port.schemas.size());eq(0,e.port.prunes.size());eq(StorageController.State.OFF,e.controller.view().state());}});
        test("lifecycle.ready-only-after-result", () -> {try(var e=new Env()){e.ready(false);check(e.controller.view().readable(1));check(!e.controller.view().readable(2));eq(REPORT,e.controller.view().schema().orElseThrow());}});
        test("lifecycle.same-revision-idempotent", () -> {try(var e=new Env()){e.ready(false);check(!e.controller.configure(1,settings(StorageSettings.Mode.VERIFY,false),NOW));e.tick();eq(1,e.port.schemas.size());}});
        test("lifecycle.same-revision-different-settings-rejected", () -> {try(var e=new Env()){rejects(() -> e.controller.configure(1,settings(StorageSettings.Mode.INITIALIZE,false),NOW));}});
        test("lifecycle.old-revision-ignored", () -> {try(var e=new Env()){e.ready(true);check(!e.controller.configure(1,StorageSettings.off(),NOW));check(e.controller.view().readable(2));}});
        test("lifecycle.replace-waits-real-flight", () -> {try(var e=new Env()){e.tick();var old=e.port.schemas.getFirst();e.controller.configure(2,settings(StorageSettings.Mode.INITIALIZE,false),NOW);e.tick();eq(1,e.port.schemas.size());old.complete(REPORT);check(!e.controller.view().readable(2));e.tick();eq(2,e.port.schemas.size());e.port.schemas.getLast().complete(REPORT);check(e.controller.view().readable(2));}});
        test("lifecycle.disable-does-not-cancel-sql", () -> {try(var e=new Env()){e.tick();var f=e.port.schemas.getFirst();e.controller.configure(2,StorageSettings.off(),NOW);check(e.controller.view().busy());check(!f.isCancelled());f.complete(REPORT);eq(StorageController.State.OFF,e.controller.view().state());check(!e.controller.view().busy());}});
        test("lifecycle.error-is-failed-not-empty-ready", () -> {try(var e=new Env()){e.tick();e.port.schemas.getFirst().completeExceptionally(new SQLException("synthetic"));eq(StorageController.State.FAILED,e.controller.view().state());eq(StorageController.Reason.DATABASE_ERROR,e.controller.view().reason());eq(1,e.errors.size());check(e.controller.view().schema().isEmpty());}});
        test("lifecycle.no-automatic-retry-loop", () -> {try(var e=new Env()){e.tick();e.port.schemas.getFirst().completeExceptionally(new SQLException("synthetic"));for(int i=0;i<50;i++)e.after(i);eq(1,e.port.schemas.size());}});
        test("lifecycle.explicit-recheck-after-failure", () -> {try(var e=new Env()){e.tick();e.port.schemas.getFirst().completeExceptionally(new SQLException("synthetic"));check(e.controller.recheck(1));e.tick();e.port.schemas.getLast().complete(REPORT);check(e.controller.view().readable(1));}});
        test("lifecycle.recheck-cannot-overlap", () -> {try(var e=new Env()){check(!e.controller.recheck(1));e.tick();check(!e.controller.recheck(1));check(!e.controller.recheck(2));}});
        test("lifecycle.deadline-retains-capacity", () -> {try(var e=new Env()){e.tick();e.after(10);eq(StorageController.Reason.DEADLINE,e.controller.view().reason());check(e.controller.view().busy());check(!e.controller.recheck(1));check(!e.port.schemas.getFirst().isCancelled());e.port.schemas.getFirst().complete(REPORT);check(!e.controller.view().busy());eq(StorageController.State.FAILED,e.controller.view().state());}});
        test("lifecycle.late-completion-no-tick-still-fenced", () -> {try(var e=new Env()){e.tick();e.time.now=NOW.plusSeconds(11);e.port.schemas.getFirst().complete(REPORT);eq(StorageController.Reason.DEADLINE,e.controller.view().reason());check(!e.controller.view().readable(1));}});
        test("lifecycle.close-ignores-late-success", () -> {var e=new Env();e.tick();e.close();check(e.controller.view().busy());e.port.schemas.getFirst().complete(REPORT);eq(StorageController.State.STOPPED,e.controller.view().state());check(!e.controller.view().busy());check(!e.controller.configure(2,settings(StorageSettings.Mode.VERIFY,false),NOW));e.tick();eq(1,e.port.schemas.size());});
        test("lifecycle.close-before-tick-dispatches-nothing", () -> {var e=new Env();e.close();e.tick();eq(0,e.port.schemas.size());check(!e.controller.recheck(1));});
        test("lifecycle.null-result-failclosed", () -> {try(var e=new Env()){e.tick();e.port.schemas.getFirst().complete(null);eq(StorageController.State.FAILED,e.controller.view().state());eq(1,e.errors.size());}});
        test("lifecycle.synchronous-rejection-frees-real-flight", () -> {
            var time=new Time();List<Throwable> errors=new ArrayList<>();
            var port=new StorageController.Port(){public CompletionStage<SchemaReport> prepare(StorageSettings.Mode m,Instant n){throw new RejectedExecutionException("test");}public CompletionStage<Integer> prune(Instant t,int n){throw new AssertionError();}};
            try(var c=new StorageController(port,time,errors::add)){c.configure(1,settings(StorageSettings.Mode.VERIFY,false),NOW);c.tick(NOW);eq(StorageController.State.FAILED,c.view().state());check(!c.view().busy());eq(1,errors.size());}
        });
        test("lifecycle.concurrent-polls-are-single-flight", () -> {
            try(var e=new Env();var workers=Executors.newFixedThreadPool(8)) {
                List<Future<?>> tasks=new ArrayList<>();for(int i=0;i<128;i++)tasks.add(workers.submit(e::tick));
                for(var task:tasks)task.get(5,TimeUnit.SECONDS);eq(1,e.port.schemas.size());check(e.controller.view().busy());
                e.port.schemas.getFirst().complete(REPORT);check(e.controller.view().readable(1));
            }
        });
        test("maintenance.waits-ready-and-interval", () -> {try(var e=new Env()){e.ready(true);e.after(59);eq(0,e.port.prunes.size());e.after(60);eq(1,e.port.prunes.size());eq(20,e.port.maximum);eq(NOW.plusSeconds(60).minus(Duration.ofDays(90)),e.port.before);check(e.controller.view().readable(2));}});
        test("maintenance.one-batch-no-drain-loop", () -> {try(var e=new Env()){e.ready(true);e.after(60);e.port.prunes.getFirst().complete(20);e.tick();eq(1,e.port.prunes.size());eq(20,e.controller.view().lastPruned());e.after(119);eq(1,e.port.prunes.size());e.after(120);eq(2,e.port.prunes.size());}});
        test("maintenance.disabled-never-prunes", () -> {try(var e=new Env()){e.ready(false);e.after(86400);eq(0,e.port.prunes.size());}});
        test("lifecycle.stale-failure-is-reported-without-changing-new-state", () -> {try(var e=new Env()){
            e.tick(); e.controller.configure(2,StorageSettings.off(),NOW);
            e.port.schemas.getFirst().completeExceptionally(new SQLException("late old revision"));
            eq(1,e.errors.size()); eq(StorageController.State.OFF,e.controller.view().state()); check(!e.controller.view().busy());
        }});
        test("lifecycle.timed-out-failure-keeps-deadline-reason", () -> {try(var e=new Env()){
            e.tick(); e.after(11); e.port.schemas.getFirst().completeExceptionally(new SQLException("late timeout"));
            eq(1,e.errors.size()); eq(StorageController.Reason.DEADLINE,e.controller.view().reason()); check(!e.controller.view().busy());
        }});
        test("lifecycle.stop-does-not-swallow-physical-database-error", () -> {var e=new Env();
            e.tick(); e.close(); e.port.schemas.getFirst().completeExceptionally(new SQLException("late shutdown"));
            eq(1,e.errors.size()); eq(StorageController.State.STOPPED,e.controller.view().state()); check(!e.controller.view().busy());
        });
        test("maintenance.slow-prune-not-overlapped", () -> {try(var e=new Env()){e.ready(true);e.after(60);e.after(80);eq(1,e.port.prunes.size());check(e.controller.view().busy());check(!e.controller.view().readable(2));e.port.prunes.getFirst().complete(10);eq(StorageController.State.FAILED,e.controller.view().state());eq(0,e.controller.view().lastPruned());}});
        test("maintenance.error-closes-readiness", () -> {try(var e=new Env()){e.ready(true);e.after(60);e.port.prunes.getFirst().completeExceptionally(new SQLException("test"));eq(StorageController.State.FAILED,e.controller.view().state());eq(1,e.errors.size());}});
        test("maintenance.invalid-count-not-accepted", () -> {for(int n:List.of(-1,21)){try(var e=new Env()){e.ready(true);e.after(60);e.port.prunes.getFirst().complete(n);eq(StorageController.State.FAILED,e.controller.view().state());}}});
        test("maintenance.negative-retention-cutoff-clamped", () -> {try(var e=new Env()){e.time.now=Instant.EPOCH;e.ready(true);e.time.now=Instant.EPOCH.plusSeconds(60);e.tick();eq(Instant.EPOCH,e.port.before);}});
        test("maintenance.reload-disposes-old-result-not-physical-sql", () -> {try(var e=new Env()){e.ready(true);e.after(60);e.controller.configure(3,StorageSettings.off(),e.time.now);e.port.prunes.getFirst().complete(10);eq(0,e.controller.view().lastPruned());eq(StorageController.State.OFF,e.controller.view().state());}});
        test("arguments.default-status-and-case", () -> {eq(StorageArguments.Action.STATUS,StorageArguments.parse(List.of()).action());eq(StorageArguments.Action.RECHECK,StorageArguments.parse(List.of("RECHECK")).action());});
        test("arguments.recovery-canonical-cursor", () -> {var id=UUID.randomUUID();eq(id,StorageArguments.parse(List.of("recovery",id.toString().toUpperCase(Locale.ROOT))).after().orElseThrow());});
        test("arguments.no-cursor-on-status", () -> rejects(() -> StorageArguments.parse(List.of("status",UUID.randomUUID().toString()))));
        test("arguments.no-short-uuid-control-or-overlong", () -> {for(String text:List.of("1-1-1-1-1","x".repeat(37),"<red>","\n"))rejects(() -> StorageArguments.parse(List.of("recovery",text)));rejects(() -> StorageArguments.parse(List.of("status","x","x")));rejects(() -> StorageArguments.parse(List.of("unlock")));});
        test("schema.only-supported-dialects", () -> {eq(JournalSql.Dialect.SQLITE,JdbcStorageBootstrap.dialect("SQLite"));eq(JournalSql.Dialect.MYSQL,JdbcStorageBootstrap.dialect("MariaDB"));eq(JournalSql.Dialect.MYSQL,JdbcStorageBootstrap.dialect("MySQL"));rejects(() -> JdbcStorageBootstrap.dialect("PostgreSQL"));});
        test("schema.prefix-not-player-input", () -> {for(String p:List.of("x;DROP_","x-","x".repeat(60)))rejects(() -> JdbcStorageBootstrap.prefixed(p));check(JdbcStorageBootstrap.prefixed("iup_").journal()!=null);});
        test("recovery.query-only-small-columns", () -> {String sql=new JdbcRecoveryInspector(JournalSql.Tables.prefixed("iup_")).query();for(String bad:List.of("plan_payload","payload","sample","UPDATE","DELETE","INSERT","SELECT *"))check(!sql.contains(bad));check(sql.contains("LIMIT ?"));check(sql.contains("a.tx_id>?"));check(sql.contains("LEFT JOIN"));});
        test("recovery.page-order-and-cursor", () -> {var p=new RecoveryPage(List.of(row(1),row(2)),true);eq(new UUID(0,2),p.nextCursor().orElseThrow());check(new RecoveryPage(p.rows(),false).nextCursor().isEmpty());rejects(() -> new RecoveryPage(List.of(row(2),row(1)),false));rejects(() -> new RecoveryPage(List.of(row(1),row(1)),false));});
        test("recovery.page-bounds", () -> {rejects(() -> new RecoveryPage(List.of(),true));List<RecoveryPage.Row> rows=new ArrayList<>();for(int i=1;i<=51;i++)rows.add(row(i));rejects(() -> new RecoveryPage(rows,true));});
        test("recovery.terminal-is-not-candidate", () -> {var r=row(1);rejects(() -> new RecoveryPage.Row(r.transactionId(),r.playerId(),AttemptRecord.State.COMPLETED,1,NOW,NOW,r.lock()));rejects(() -> new RecoveryPage.Row(r.transactionId(),r.playerId(),AttemptRecord.State.ABORTED,1,NOW,NOW,r.lock()));});
        test("recovery.version-time-bounds", () -> {var r=row(1);rejects(() -> new RecoveryPage.Row(r.transactionId(),r.playerId(),r.state(),-1,NOW,NOW,r.lock()));rejects(() -> new RecoveryPage.Row(r.transactionId(),r.playerId(),r.state(),1,NOW,NOW.minusSeconds(1),r.lock()));});
        test("logging.never-emits-jdbc-secret-or-driver-message", () -> {
            var secret=new SQLException("jdbc:mysql://host/db?password=HIDDEN", "42000", 9);var wrapped=new CompletionException(secret);
            String safe=SafeFailure.describe(wrapped);check(!safe.contains("HIDDEN"));check(!safe.contains("host"));check(safe.contains("42000"));check(safe.contains("9"));
            check(!SafeFailure.describe(new SQLException("secret","invalid\nstate",5)).contains("invalid"));
        });
        test("logging.schema-reason-is-stable", () -> {eq("schemaCode=MISSING_TABLE",SafeFailure.describe(new CompletionException(new SchemaProblem(SchemaProblem.Code.MISSING_TABLE))));eq("UNKNOWN",SafeFailure.describe(null));});
    }
}
