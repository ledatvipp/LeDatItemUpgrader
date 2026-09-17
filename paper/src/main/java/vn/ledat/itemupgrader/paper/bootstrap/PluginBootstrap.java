package vn.ledat.itemupgrader.paper.bootstrap;

import java.util.Objects;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import vn.ledat.itemupgrader.paper.command.UpgraderCommand;
import vn.ledat.itemupgrader.paper.config.ConfigLoader;
import vn.ledat.itemupgrader.paper.config.RegistrySnapshot;
import vn.ledat.itemupgrader.paper.item.PlatformIdentityIndex;
import vn.ledat.itemupgrader.paper.listener.PlayerLifecycleListener;
import vn.ledat.itemupgrader.paper.message.Messages;
import vn.ledat.itemupgrader.paper.platform.PlatformAccess;
import vn.ledat.itemupgrader.paper.service.ConfigurationService;
import vn.ledat.itemupgrader.paper.service.InspectionService;
import vn.ledat.itemupgrader.paper.service.CatalogCacheService;
import vn.ledat.itemupgrader.paper.service.CatalogPreviewService;
import vn.ledat.itemupgrader.paper.service.QuotePreviewService;
import vn.ledat.itemupgrader.paper.service.AccessSnapshotService;
import vn.ledat.itemupgrader.runtime.RuntimeStore;
import vn.ledat.itemupgrader.runtime.UpgraderRuntime;

public final class PluginBootstrap {
    private final JavaPlugin owner;
    private final RuntimeStore<UpgraderRuntime> runtime = new RuntimeStore<>();
    private PlatformAccess platform;
    private InspectionService inspections;
    private CatalogPreviewService catalogs;
    private QuotePreviewService quotes;
    private boolean stopped;
    private vn.ledat.itemupgrader.paper.storage.PlatformStorageService storage;
    private vn.ledat.itemupgrader.paper.history.HistoryUiService history;
    private vn.ledat.itemupgrader.paper.animation.InventoryAnimationService animations;
    private vn.ledat.itemupgrader.paper.gui.InventoryGuiService guis;
    public PluginBootstrap(JavaPlugin owner) { this.owner = owner; }
    public void start() {
        platform = new PlatformAccess(owner);
        var registry = RegistrySnapshot.capture();
        var messages = new Messages(runtime);
        var configs = new ConfigurationService(owner, platform, runtime, new ConfigLoader(registry, owner.getLogger()::warning), messages);
        var identities = new PlatformIdentityIndex(platform);
        inspections = new InspectionService(owner, platform, runtime, identities, messages);
        var cache = new CatalogCacheService(owner, platform, runtime, identities);
        var accessSnapshots = new AccessSnapshotService(owner);
        catalogs = new CatalogPreviewService(owner, platform, runtime, messages, identities, cache, accessSnapshots);
        quotes = new QuotePreviewService(owner, platform, runtime, messages, cache, identities, accessSnapshots);
        var guiLoader = new vn.ledat.itemupgrader.paper.gui.GuiPreviewLoader(owner, platform, runtime, cache, identities, accessSnapshots);
        guis = new vn.ledat.itemupgrader.paper.gui.InventoryGuiService(owner, platform, runtime, messages, guiLoader, accessSnapshots);
        animations = new vn.ledat.itemupgrader.paper.animation.InventoryAnimationService(owner, platform, runtime, messages);
        storage = new vn.ledat.itemupgrader.paper.storage.PlatformStorageService(owner,platform,runtime,messages);
        history = new vn.ledat.itemupgrader.paper.history.HistoryUiService(owner,platform,runtime,messages,storage.historyStore());
        var command = new UpgraderCommand(runtime, messages, configs, inspections, catalogs, quotes, platform, guis, animations, history, storage);
        var registered = Objects.requireNonNull(owner.getCommand("upgrader"), "upgrader missing in plugin.yml");
        registered.setExecutor(command); registered.setTabCompleter(command);
        owner.getServer().getPluginManager().registerEvents(new PlayerLifecycleListener(inspections, catalogs, quotes), owner);
        owner.getServer().getPluginManager().registerEvents(new vn.ledat.itemupgrader.paper.gui.GuiInventoryListener(guis), owner);
        owner.getServer().getPluginManager().registerEvents(new vn.ledat.itemupgrader.paper.animation.AnimationInventoryListener(animations), owner);
        owner.getServer().getPluginManager().registerEvents(new vn.ledat.itemupgrader.paper.history.HistoryInventoryListener(history), owner);
        storage.start(); guis.start(); animations.start(); history.start();
        owner.getLogger().info("Bootstrap registered; API=" + platform.apiVersion() + "; waiting for async configuration. Inventory preview wired (REFERENCE_ONLY); admin animation preview wired; live transactions and packets remain disabled.");
        configs.reload(null);
    }
    public void stop() {
        if (stopped) return;
        stopped = true;
        if (history != null) history.close();
        if (animations != null) animations.close();
        if (guis != null) guis.close();
        if (storage != null) storage.close();
        runtime.stop();
        if (quotes != null) quotes.close();
        if (catalogs != null) catalogs.close();
        if (inspections != null) inspections.close();
        HandlerList.unregisterAll(owner);
        if (platform != null) platform.shutdown();
        owner.getLogger().info("ItemUpgrader disabled; runtime/session inspection state cleaned.");
    }
}
