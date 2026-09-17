package vn.ledat.itemupgrader.paper.service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;
import org.bukkit.plugin.java.JavaPlugin;
import vn.ledat.itemupgrader.paper.config.ConfigLoader;
import vn.ledat.itemupgrader.paper.message.Messages;
import vn.ledat.itemupgrader.paper.platform.PlatformAccess;
import vn.ledat.itemupgrader.runtime.RuntimeStore;
import vn.ledat.itemupgrader.runtime.UpgraderRuntime;
import vn.ledat.itemupgrader.storage.SchemaRepository;

/** No Player/CommandSender object crosses the async boundary; console results go through the logger. */
public final class ConfigurationService {
    private final JavaPlugin owner;
    private final PlatformAccess platform;
    private final RuntimeStore<UpgraderRuntime> runtime;
    private final ConfigLoader loader;
    private final Messages messages;
    private final java.nio.file.Path folder;
    public ConfigurationService(JavaPlugin owner, PlatformAccess platform, RuntimeStore<UpgraderRuntime> runtime,
                                ConfigLoader loader, Messages messages) {
        this.owner = owner; this.platform = platform; this.runtime = runtime; this.loader = loader; this.messages = messages;
        this.folder = owner.getDataFolder().toPath();
    }
    public void reload(UUID requester) {
        var acquired = runtime.beginReload();
        if (acquired.isEmpty()) { notify(requester, "reload-busy", Map.of()); return; }
        var ticket = acquired.get();
        notify(requester, "reload-start", Map.of());
        try {
            platform.async("itemupgrader-config", () -> {
                if (runtime.state() == RuntimeStore.State.STOPPED) return;
                try {
                    platform.ensureFiles();
                    UpgraderRuntime candidate = loader.load(folder);
                    if (!candidate.initializeSchema()) { commit(ticket, candidate, requester); return; }
                    if (runtime.state() == RuntimeStore.State.STOPPED) return;
                    String table = platform.tableName("schema_meta");
                    platform.query("itemupgrader-schema", connection -> new SchemaRepository().initialize(connection, table, System.currentTimeMillis()))
                            .whenComplete((version, error) -> {
                                if (error != null) reject(ticket, requester, error);
                                else commit(ticket, candidate, requester);
                            });
                } catch (java.io.IOException | RuntimeException error) { reject(ticket, requester, error); }
            });
        } catch (RuntimeException schedulingError) { reject(ticket, requester, schedulingError); }
    }
    private void commit(RuntimeStore.Ticket ticket, UpgraderRuntime candidate, UUID requester) {
        if (!runtime.commit(ticket, candidate)) return;
        var committed = runtime.snapshot();
        if (committed.isEmpty()) return; // Owner may have disabled between commit and notification.
        long revision = committed.get().revision();
        owner.getLogger().info("Runtime READY; revision=" + revision + "; manual-items=" + candidate.values().manual().size()
                + "; recipes=" + candidate.values().recipes().resultKeys().size()
                + "; targets=" + candidate.catalog().targets().size() + "; paths=" + candidate.catalog().paths().size() + "; schema-enabled=" + candidate.initializeSchema());
        notify(requester, "reload-success", Map.of("revision", String.valueOf(revision)));
    }
    private void reject(RuntimeStore.Ticket ticket, UUID requester, Throwable error) {
        if (!runtime.reject(ticket)) return;
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        owner.getLogger().log(Level.WARNING, "Config/storage candidate rejected; previous runtime preserved when available", cause);
        notify(requester, "reload-failed", Map.of());
    }
    private void notify(UUID requester, String key, Map<String, String> data) {
        if (runtime.state() == RuntimeStore.State.STOPPED) return;
        if (requester == null) {
            // Logger is thread-safe. Do not call Bukkit.getConsoleSender() from workers.
            String rendered = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(messages.component(key, data));
            owner.getLogger().info(rendered);
        } else platform.player(requester, player -> messages.send(player, key, data));
    }
}
