package vn.ledat.itemupgrader.paper.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import vn.ledat.itemupgrader.gui.GuiClickPolicy;
import vn.ledat.itemupgrader.gui.MenuDefinition;

/** Cancel first; raw-slot/cursor/click allow-list; business and deferred navigation belong to GUI service. */
public final class GuiInventoryListener implements Listener {
    private final InventoryGuiService guis;
    private final GuiClickPolicy clicks=new GuiClickPolicy();
    public GuiInventoryListener(InventoryGuiService guis) { this.guis=guis; }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)
    public void click(InventoryClickEvent event) {
        var top=event.getView().getTopInventory();
        if(!guis.owns(top)) return;
        boolean previouslyCancelled=event.isCancelled(); event.setCancelled(true);
        if(!(event.getWhoClicked() instanceof Player player)) return;
        var state=guis.state(player,top);
        GuiClickPolicy.Click type;
        try {type=GuiClickPolicy.Click.valueOf(event.getClick().name());} catch(IllegalArgumentException unknown) {type=GuiClickPolicy.Click.UNKNOWN;}
        GuiClickPolicy.Effect effect=switch(event.getAction()) {
            case PICKUP_ALL -> GuiClickPolicy.Effect.PICKUP_ALL;
            case PICKUP_HALF -> GuiClickPolicy.Effect.PICKUP_HALF;
            case NOTHING -> GuiClickPolicy.Effect.NOTHING;
            default -> GuiClickPolicy.Effect.OTHER;
        };
        var route=clicks.decide(new GuiClickPolicy.Input(true,state.isPresent(),state.map(s->s.busy()).orElse(true),previouslyCancelled,
                event.getCursor().getType().isAir(),event instanceof InventoryCreativeEvent,top.getSize(),event.getRawSlot(),event.getSlot(),type,effect));
        if(state.isEmpty()) return;
        if(route.route()==GuiClickPolicy.Route.SOURCE_REFERENCE) {
            if(event.getClickedInventory()!=player.getInventory()) return;
            guis.click(player,state.orElseThrow(),new GuiRenderer.Binding(MenuDefinition.Action.SOURCE_INPUT,""),false,route.slot());
        } else if(route.route()==GuiClickPolicy.Route.TOP) {
            var button=guis.binding(player,route.slot());
            boolean right=event.getClick()==ClickType.RIGHT;
            button.ifPresent(b->guis.click(player,state.orElseThrow(),b,right,-1));
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)
    public void drag(InventoryDragEvent event) { if(clicks.cancelDrag(guis.owns(event.getView().getTopInventory()))) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)
    public void open(InventoryOpenEvent event) {
        if(!guis.owns(event.getInventory())) return;
        if(!(event.getPlayer() instanceof Player player)||guis.state(player,event.getInventory()).isEmpty()) event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void close(InventoryCloseEvent event) {
        if(guis.owns(event.getInventory())) guis.closed(event.getPlayer().getUniqueId(),(UpgraderGuiHolder)event.getInventory().getHolder(false));
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) { guis.forget(event.getPlayer().getUniqueId()); }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void kick(PlayerKickEvent event) { guis.exit(event.getPlayer().getUniqueId(),null); }
    @EventHandler(priority=EventPriority.MONITOR)
    public void death(PlayerDeathEvent event) { guis.exit(event.getEntity().getUniqueId(),null); }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void teleport(PlayerTeleportEvent event) { guis.exit(event.getPlayer().getUniqueId(),null); }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void mode(PlayerGameModeChangeEvent event) { guis.exit(event.getPlayer().getUniqueId(),null); }
}
