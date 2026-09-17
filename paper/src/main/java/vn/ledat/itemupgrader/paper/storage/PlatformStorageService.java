package vn.ledat.itemupgrader.paper.storage;

import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import vn.ledat.itemupgrader.paper.gui.PaperGuiScheduler;
import vn.ledat.itemupgrader.paper.message.Messages;
import vn.ledat.itemupgrader.paper.platform.PlatformAccess;
import vn.ledat.itemupgrader.runtime.*;
import vn.ledat.itemupgrader.storage.management.*;
import vn.ledat.itemupgrader.history.storage.ProgressSql;
import vn.ledat.itemupgrader.output.storage.OutputSql;
import vn.ledat.itemupgrader.transaction.storage.JournalSql;

/** Native shared-SQL lifecycle source. No live writer/effect port is created or registered here. */
public final class PlatformStorageService implements AutoCloseable {
    private final JavaPlugin owner;
    private final PlatformAccess platform;
    private final RuntimeStore<UpgraderRuntime> runtime;
    private final Messages messages;
    private final PaperGuiScheduler scheduler;
    private final StorageController controller;
    private final JdbcStorageBootstrap repositories;
    private final JdbcRecoveryInspector inspector;
    private final PlatformHistoryStore history;
    private final AtomicBoolean recoveryBusy = new AtomicBoolean();
    private volatile boolean stopped;
    private Instant nextAdminCheck = Instant.EPOCH, nextRecovery = Instant.EPOCH;
    private StorageController.State loggedState;
    private long loggedRevision = -1;

    public PlatformStorageService(JavaPlugin owner, PlatformAccess platform, RuntimeStore<UpgraderRuntime> runtime, Messages messages) {
        this.owner = owner; this.platform = platform; this.runtime = runtime; this.messages = messages;
        scheduler = new PaperGuiScheduler(owner, platform);
        repositories = new JdbcStorageBootstrap(
            new JournalSql(new JournalSql.Tables(platform.tableName("attempts"), platform.tableName("player_locks"), platform.tableName("tx_events"), platform.tableName("tx_schema"))),
            new OutputSql(platform.tableName("outputs"), platform.tableName("output_identities"), platform.tableName("output_schema")),
            new ProgressSql(new ProgressSql.Tables(platform.tableName("history"), platform.tableName("statistics"), platform.tableName("pity"), platform.tableName("completions"), platform.tableName("progress_schema"))));
        inspector = new JdbcRecoveryInspector(repositories.journalSql().tables());
        controller = new StorageController(new StorageController.Port() {
            @Override public CompletionStage<SchemaReport> prepare(StorageSettings.Mode mode, Instant now) {
                return platform.query("iup-storage-prepare", c -> {
                    if (stopped) throw new java.sql.SQLException("storage owner stopped before dispatch");
                    return repositories.prepare(c, mode, now);
                });
            }
            @Override public CompletionStage<Integer> prune(Instant before, int maximum) {
                return platform.query("iup-history-prune", c -> {
                    if (stopped) throw new java.sql.SQLException("storage owner stopped before maintenance");
                    return repositories.progress().pruneHistory(c, before, maximum);
                });
            }
        }, error -> owner.getLogger().warning("Storage operation failed; no automatic repair/retry. " + SafeFailure.describe(error)));
        history = new PlatformHistoryStore(platform, repositories.progress(), this::readable);
    }
    public void start() { scheduler.sweep(this::poll); }
    /** Paper owner thread only; observes immutable config revision and dispatches no JDBC on this thread. */
    private void poll() {
        if (stopped) return;
        var current = runtime.snapshot();
        if (current.isPresent()) controller.configure(current.orElseThrow().revision(), current.orElseThrow().value().storageManagement(), Instant.now());
        controller.tick(Instant.now());
        var view = controller.view();
        if (view.revision() != loggedRevision || view.state() != loggedState) {
            loggedRevision = view.revision(); loggedState = view.state();
            owner.getLogger().info("Storage lifecycle=" + view.state() + "; revision=" + view.revision() + "; reason=" + view.reason()
                    + "; SQL read readiness only; live upgrade adapters remain disabled.");
        }
    }
    public boolean readable() {
        if (stopped || runtime.state() == RuntimeStore.State.STOPPED) return false;
        var current = runtime.snapshot();
        return current.isPresent() && controller.view().readable(current.orElseThrow().revision());
    }
    private boolean readable(long expected) {
        if(stopped||runtime.state()==RuntimeStore.State.STOPPED)return false;
        var current=runtime.snapshot();
        return current.isPresent()&&current.orElseThrow().revision()==expected&&controller.view().readable(expected);
    }
    public PlatformHistoryStore historyStore() { return history; }
    public StorageController.View status() { return controller.view(); }
    public boolean recheck() {
        poll(); Instant now = Instant.now();
        if (now.isBefore(nextAdminCheck)) return false;
        var current = runtime.snapshot();
        if (current.isEmpty() || !controller.recheck(current.orElseThrow().revision())) return false;
        nextAdminCheck = now.plusSeconds(10); controller.tick(now); return true;
    }
    /** Read-only bounded admin listing. UUID-only requester; null routes console output through thread-safe logging. */
    public void recovery(UUID requester, Optional<UUID> after) {
        poll(); Instant now = Instant.now();
        if (!readable()) { send(requester, "storage-not-ready", Map.of()); return; }
        if (now.isBefore(nextRecovery) || !recoveryBusy.compareAndSet(false, true)) { send(requester, "storage-busy", Map.of()); return; }
        nextRecovery = now.plusSeconds(2);
        long expected = runtime.snapshot().orElseThrow().revision(); Instant deadline = now.plusSeconds(10);
        CompletionStage<RecoveryPage> future;
        try { future = platform.query("iup-recovery-readonly", c -> {
            if (!readable(expected)) throw new java.sql.SQLException("storage revision changed");
            return inspector.page(c, after, 16);
        }); } catch (RuntimeException error) { future = CompletableFuture.failedFuture(error); }
        future.whenComplete((page, error) -> {
            recoveryBusy.set(false); // Capacity is held until the actual query completes, even after timeout/quit.
            if (stopped || !readable(expected)) return;
            if (!Instant.now().isBefore(deadline)) { send(requester, "storage-request-expired", Map.of()); return; }
            if (error != null) {
                owner.getLogger().warning("Recovery inspection failed; no transaction modified. " + SafeFailure.describe(error));
                send(requester, "storage-not-ready", Map.of()); return;
            }
            deliverPage(requester, page, expected, deadline);
        });
    }
    private void deliverPage(UUID requester, RecoveryPage page, long expected, Instant deadline) {
        if (requester == null) {
            pageMessages(page, (key, data) -> logMessage(key, data)); return;
        }
        platform.player(requester, player -> {
            if (stopped || !readable(expected) || !Instant.now().isBefore(deadline)) return;
            if (!player.hasPermission("ledatitemupgrader.use") || !player.hasPermission("ledatitemupgrader.admin.storage")) return;
            pageMessages(page, (key, data) -> messages.send(player, key, data));
        });
    }
    private void pageMessages(RecoveryPage page, java.util.function.BiConsumer<String,Map<String,String>> sink) {
        sink.accept("storage-recovery-header", Map.of("count", Integer.toString(page.rows().size())));
        for (var row : page.rows()) sink.accept("storage-recovery-row", Map.of("transaction", row.transactionId().toString(),
            "player", row.playerId().toString(), "state", row.state().name(), "version", Long.toString(row.version()), "lock", row.lock().name()));
        if (page.nextCursor().isPresent()) sink.accept("storage-recovery-next", Map.of("cursor", page.nextCursor().orElseThrow().toString()));
        else sink.accept("storage-recovery-end", Map.of());
    }
    private void send(UUID requester, String key, Map<String,String> data) {
        if (stopped) return;
        if (requester == null) logMessage(key, data);
        else platform.player(requester, p -> { if (!stopped && p.hasPermission("ledatitemupgrader.use") && p.hasPermission("ledatitemupgrader.admin.storage")) messages.send(p, key, data); });
    }
    private void logMessage(String key, Map<String,String> data) {
        owner.getLogger().info(PlainTextComponentSerializer.plainText().serialize(messages.component(key, data)));
    }
    @Override public void close() {
        if (stopped) return; stopped = true; controller.close(); history.close(); scheduler.close();
        // The caller flushes Platform storage with its shutdown budget. In-flight SQL is never labelled rolled back here.
    }
}
