package vn.ledat.itemupgrader.paper.history;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerQuitEvent;
/** Cancel first. No cursor/source transfer; every drag and bottom interaction is locked for this viewer. */
public final class HistoryInventoryListener implements Listener {
    private final HistoryUiService service;
    public HistoryInventoryListener(HistoryUiService service){this.service=service;}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)
    public void click(InventoryClickEvent event) {
        if(!(event.getView().getTopInventory().getHolder() instanceof HistoryHolder h))return;
        event.setCancelled(true);
        if(!(event.getWhoClicked() instanceof Player p)||event instanceof InventoryCreativeEvent||event.getClick()!=ClickType.LEFT
                ||event.getRawSlot()<0||event.getRawSlot()>=event.getView().getTopInventory().getSize()
                ||!p.getItemOnCursor().getType().isAir())return;
        service.click(p,h,event.getRawSlot());
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)
    public void drag(InventoryDragEvent event){if(event.getView().getTopInventory().getHolder() instanceof HistoryHolder)event.setCancelled(true);}
    @EventHandler public void close(InventoryCloseEvent event){if(event.getInventory().getHolder() instanceof HistoryHolder h&&event.getPlayer() instanceof Player p)service.closed(p,h);}
    @EventHandler public void quit(PlayerQuitEvent event){service.quit(event.getPlayer().getUniqueId());}
}
