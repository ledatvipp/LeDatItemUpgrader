package vn.ledat.itemupgrader.paper.animation;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import vn.ledat.itemupgrader.gui.GuiClickPolicy;

/** Own holder/session identity, cancel-before-dispatch; no bottom input, cursor mutation, shift/drag or rewards. */
public final class AnimationInventoryListener implements Listener {
    private final InventoryAnimationService animations;
    public AnimationInventoryListener(InventoryAnimationService animations) { this.animations = animations; }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void click(InventoryClickEvent event) {
        var top = event.getView().getTopInventory(); if (!animations.owns(top)) return;
        boolean previouslyCancelled = event.isCancelled(); event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        var identity = animations.identity(player, top); if (identity.isEmpty()) return;
        GuiClickPolicy.Click type;
        try { type = GuiClickPolicy.Click.valueOf(event.getClick().name()); }
        catch (IllegalArgumentException unknown) { type = GuiClickPolicy.Click.UNKNOWN; }
        var effect = switch (event.getAction()) {
            case PICKUP_ALL -> GuiClickPolicy.Effect.PICKUP_ALL;
            case PICKUP_HALF -> GuiClickPolicy.Effect.PICKUP_HALF;
            case NOTHING -> GuiClickPolicy.Effect.NOTHING;
            default -> GuiClickPolicy.Effect.OTHER;
        };
        var input = new GuiClickPolicy.Input(true, true, false, previouslyCancelled,
                event.getCursor().getType().isAir(), event instanceof InventoryCreativeEvent, top.getSize(),
                event.getRawSlot(), event.getSlot(), type, effect);
        var token = identity.orElseThrow();
        animations.action(token, input).ifPresent(role -> animations.click(player, token, role));
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void drag(InventoryDragEvent event) { if (animations.owns(event.getView().getTopInventory())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void open(InventoryOpenEvent event) {
        if (!animations.owns(event.getInventory())) return;
        if (!(event.getPlayer() instanceof Player p) || animations.identity(p, event.getInventory()).isEmpty()) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void close(InventoryCloseEvent event) {
        if (animations.owns(event.getInventory())) animations.closed(event.getPlayer().getUniqueId(), (AnimationHolder) event.getInventory().getHolder(false));
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) { animations.forget(event.getPlayer().getUniqueId()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void kick(PlayerKickEvent event) { animations.exit(event.getPlayer().getUniqueId()); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void death(PlayerDeathEvent event) { animations.exit(event.getEntity().getUniqueId()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent event) { animations.exit(event.getPlayer().getUniqueId()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void mode(PlayerGameModeChangeEvent event) { animations.exit(event.getPlayer().getUniqueId()); }
}
