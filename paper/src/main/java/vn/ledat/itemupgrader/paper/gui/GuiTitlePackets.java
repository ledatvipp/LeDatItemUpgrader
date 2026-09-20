package vn.ledat.itemupgrader.paper.gui;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenWindow;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.java.JavaPlugin;
import vn.ledat.itemupgrader.animation.TitlePacketFence;
import vn.ledat.itemupgrader.gui.GuiTitleToken;

/** Exact-container title bridge for the main upgrader GUI; never sees or rewrites inventory contents. */
final class GuiTitlePackets implements AutoCloseable {
    private static final class Expected {
        final GuiTitleToken token;
        final Inventory inventory;
        volatile boolean armed;
        int candidateId = -1;
        int candidateType = -1;

        Expected(GuiTitleToken token, Inventory inventory) {
            this.token = token;
            this.inventory = inventory;
        }
    }
    private final TitlePacketFence<GuiTitleToken> fence = new TitlePacketFence<>(GuiTitleToken::viewer);
    private final Map<UUID, Expected> expected = new ConcurrentHashMap<>();
    private final PacketListenerCommon probeListener;
    private final PacketListenerCommon captureListener;
    private volatile boolean closed;

    GuiTitlePackets(JavaPlugin owner) {
        var dependency = owner.getServer().getPluginManager().getPlugin("packetevents");
        if (dependency == null || !dependency.isEnabled()) throw new IllegalStateException("PacketEvents 2.13+ is required for inventory titles");
        probeListener = PacketEvents.getAPI().getEventManager().registerListener(new PacketListener() {
            @Override public void onPacketSend(PacketSendEvent event) {
                if (closed || event.isCancelled() || event.getPacketType() != PacketType.Play.Server.OPEN_WINDOW) return;
                Player player = event.getPlayer(); if (player == null) return;
                Expected entry = expected.get(player.getUniqueId()); if (entry == null) return;
                // Capture is armed only around our synchronous Player#openInventory call. This is
                // deliberately independent of the title Component: VietHUD/Nexo may legitimately
                // transform it in another packet listener. Packets before/after that exact call,
                // including every normal foreign GUI open, are ignored.
                if (!entry.armed) return;
                var packet = new WrapperPlayServerOpenWindow(event);
                synchronized (entry) {
                    // Keep the first packet emitted by Player#openInventory. A packet recursively
                    // sent by another listener cannot replace this candidate.
                    if (entry.armed && entry.candidateId < 0) {
                        entry.candidateId = packet.getContainerId();
                        entry.candidateType = packet.getType();
                    }
                }
            }
        }, PacketListenerPriority.LOWEST);
        captureListener = PacketEvents.getAPI().getEventManager().registerListener(new PacketListener() {
            @Override public void onPacketSend(PacketSendEvent event) {
                if (closed || event.isCancelled() || event.getPacketType() != PacketType.Play.Server.OPEN_WINDOW) return;
                Player player = event.getPlayer(); if (player == null) return;
                Expected entry = expected.get(player.getUniqueId()); if (entry == null || !entry.armed) return;
                var packet = new WrapperPlayServerOpenWindow(event);
                synchronized (entry) {
                    // MONITOR confirms that the original candidate survived every other listener
                    // without cancellation or container identity replacement.
                    if (entry.armed && entry.candidateId == packet.getContainerId()
                            && entry.candidateType == packet.getType())
                        fence.capture(player.getUniqueId(), packet.getContainerId(), packet.getType());
                }
            }
        }, PacketListenerPriority.MONITOR);
    }

    boolean expect(GuiTitleToken token, Inventory inventory) {
        Expected entry = new Expected(token, inventory);
        if (closed || expected.putIfAbsent(token.viewer(), entry) != null) return false;
        if (fence.begin(token)) return true;
        expected.remove(token.viewer(), entry); return false;
    }
    boolean arm(Player player, GuiTitleToken token, Inventory inventory) {
        Expected entry = expected.get(token.viewer());
        if (closed || entry == null || !entry.token.equals(token) || entry.inventory != inventory
                || !player.getUniqueId().equals(token.viewer())) return false;
        synchronized (entry) {
            entry.candidateId = -1;
            entry.candidateType = -1;
            entry.armed = true;
        }
        return true;
    }
    void disarm(GuiTitleToken token) {
        Expected entry = expected.get(token.viewer());
        if (entry != null && entry.token.equals(token)) synchronized (entry) {
            entry.armed = false;
            entry.candidateId = -1;
            entry.candidateType = -1;
        }
    }
    boolean activate(Player player, GuiTitleToken token, Inventory inventory) {
        Expected entry = expected.get(token.viewer());
        return !closed && entry != null && entry.token.equals(token) && entry.inventory == inventory
                && player.getOpenInventory().getTopInventory() == inventory && fence.activate(token).isPresent();
    }
    boolean send(Player player, GuiTitleToken token, Inventory inventory, Component title) {
        Expected entry = expected.get(token.viewer());
        if (closed || entry == null || !entry.token.equals(token) || entry.inventory != inventory
                || !player.getUniqueId().equals(token.viewer()) || player.getOpenInventory().getTopInventory() != inventory) return false;
        var container = fence.current(token); if (container.isEmpty()) return false;
        var id = container.orElseThrow();
        // This must pass through the normal PacketEvents pipeline. A silent send bypasses outbound
        // resource-pack integrations and leaves only content expanded before this point visible.
        PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                new WrapperPlayServerOpenWindow(id.id(), id.type(), title));
        // OPEN_WINDOW recreates the client-side container and clears its slots. Keep the same
        // server menu, then immediately resend its authoritative contents so a title frame can
        // never make the GUI appear empty. This is scoped to the exact owned top inventory above.
        if (player.getOpenInventory().getTopInventory() != inventory) return false;
        player.updateInventory();
        return player.getOpenInventory().getTopInventory() == inventory;
    }
    void release(GuiTitleToken token) {
        Expected entry = expected.get(token.viewer());
        if (entry == null || !entry.token.equals(token)) return;
        expected.remove(token.viewer(), entry); fence.release(token);
    }
    @Override public void close() {
        if (closed) return; closed = true;
        PacketEvents.getAPI().getEventManager().unregisterListener(probeListener);
        PacketEvents.getAPI().getEventManager().unregisterListener(captureListener);
        expected.clear(); fence.clear();
    }
}
