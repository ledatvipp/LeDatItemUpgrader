package vn.ledat.itemupgrader.paper.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import vn.ledat.itemupgrader.paper.service.CatalogPreviewService;
import vn.ledat.itemupgrader.paper.service.InspectionService;
import vn.ledat.itemupgrader.paper.service.QuotePreviewService;

public final class PlayerLifecycleListener implements Listener {
    private final InspectionService inspections;
    private final CatalogPreviewService catalogs;
    private final QuotePreviewService quotes;
    public PlayerLifecycleListener(InspectionService inspections, CatalogPreviewService catalogs, QuotePreviewService quotes) {
        this.inspections = inspections; this.catalogs = catalogs; this.quotes = quotes;
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) {
        var id = event.getPlayer().getUniqueId(); inspections.forget(id); quotes.forget(id); catalogs.forget(id);
    }
    // Conservative invalidation also handles newly enabled optional item providers.
    @EventHandler public void onPluginEnable(PluginEnableEvent event) { catalogs.invalidate(); }
    @EventHandler public void onPluginDisable(PluginDisableEvent event) { catalogs.invalidate(); }
}
