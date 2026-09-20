package vn.ledat.itemupgrader.paper.animation;

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
import vn.ledat.itemupgrader.animation.AnimationSessionStore;
import vn.ledat.itemupgrader.animation.TitlePacketFence;

/**
 * PacketEvents bridge derived from zMenu's OPEN_WINDOW title technique, with a deliberately much
 * narrower capture scope. It never cancels a packet, never observes WINDOW_ITEMS, and never stores
 * another plugin's container after our exact session has activated.
 */
final class PacketEventsTitlePackets implements AutoCloseable {
    private static final class Expected {
        final AnimationSessionStore.Token token;
        final Inventory inventory;
        volatile boolean armed;
        int candidateId = -1;
        int candidateType = -1;

        Expected(AnimationSessionStore.Token token, Inventory inventory) {
            this.token = token;
            this.inventory = inventory;
        }
    }

    private final TitlePacketFence<AnimationSessionStore.Token> fence = new TitlePacketFence<>(AnimationSessionStore.Token::viewer);
    private final Map<UUID, Expected> expected = new ConcurrentHashMap<>();
    private final PacketListenerCommon probeListener;
    private final PacketListenerCommon captureListener;
    private volatile boolean closed;

    PacketEventsTitlePackets(JavaPlugin owner) {
        var dependency = owner.getServer().getPluginManager().getPlugin("packetevents");
        if (dependency == null || !dependency.isEnabled())
            throw new IllegalStateException("PacketEvents 2.13+ is required for animated inventory titles");
        this.probeListener = PacketEvents.getAPI().getEventManager().registerListener(new PacketListener() {
            @Override public void onPacketSend(PacketSendEvent event) {
                if (closed || event.isCancelled() || event.getPacketType() != PacketType.Play.Server.OPEN_WINDOW) return;
                Player player = event.getPlayer();
                if (player == null) return;
                Expected entry = expected.get(player.getUniqueId());
                if (entry == null || !entry.armed) return;
                var packet = new WrapperPlayServerOpenWindow(event);
                synchronized (entry) {
                    if (entry.armed && entry.candidateId < 0) {
                        entry.candidateId = packet.getContainerId();
                        entry.candidateType = packet.getType();
                    }
                }
            }
        }, PacketListenerPriority.LOWEST);
        this.captureListener = PacketEvents.getAPI().getEventManager().registerListener(new PacketListener() {
            @Override public void onPacketSend(PacketSendEvent event) {
                if (closed || event.isCancelled() || event.getPacketType() != PacketType.Play.Server.OPEN_WINDOW) return;
                Player player = event.getPlayer();
                if (player == null) return;
                Expected entry = expected.get(player.getUniqueId());
                if (entry == null || !entry.armed) return;
                var packet = new WrapperPlayServerOpenWindow(event);
                synchronized (entry) {
                    if (entry.armed && entry.candidateId == packet.getContainerId()
                            && entry.candidateType == packet.getType())
                        fence.capture(player.getUniqueId(), packet.getContainerId(), packet.getType());
                }
            }
        }, PacketListenerPriority.MONITOR);
    }

    boolean expect(AnimationSessionStore.Token token, Inventory inventory) {
        Expected entry = new Expected(token, inventory);
        if (closed || expected.putIfAbsent(token.viewer(), entry) != null) return false;
        if (fence.begin(token)) return true;
        expected.remove(token.viewer(), entry);
        return false;
    }

    boolean arm(Player player, AnimationSessionStore.Token token, Inventory inventory) {
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

    void disarm(AnimationSessionStore.Token token) {
        Expected entry = expected.get(token.viewer());
        if (entry != null && entry.token.equals(token)) synchronized (entry) {
            entry.armed = false;
            entry.candidateId = -1;
            entry.candidateType = -1;
        }
    }

    boolean activate(Player player, AnimationSessionStore.Token token, Inventory inventory) {
        Expected entry = expected.get(token.viewer());
        if (closed || entry == null || !entry.token.equals(token) || entry.inventory != inventory
                || player.getOpenInventory().getTopInventory() != inventory) return false;
        return fence.activate(token).isPresent();
    }

    boolean send(Player player, AnimationSessionStore.Token token, Inventory inventory, Component title) {
        Expected entry = expected.get(token.viewer());
        if (closed || entry == null || !entry.token.equals(token) || entry.inventory != inventory
                || player.getUniqueId().equals(token.viewer()) == false
                || player.getOpenInventory().getTopInventory() != inventory) return false;
        var container = fence.current(token);
        if (container.isEmpty()) return false;
        var identity = container.orElseThrow();
        var packet = new WrapperPlayServerOpenWindow(identity.id(), identity.type(), title);
        // Resource-pack integrations render their <shift>/<image> tags in the ordinary outbound
        // packet pipeline, so a silent send would bypass them. Our capture fence is no longer armed
        // here and therefore ignores this cosmetic refresh.
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
        // Re-sending OPEN_WINDOW clears all client-side slots. Restore the authoritative contents
        // of this exact owned container immediately, matching the title-update contract used by
        // mature menu implementations without observing or caching foreign WINDOW_ITEMS packets.
        if (player.getOpenInventory().getTopInventory() != inventory) return false;
        player.updateInventory();
        return player.getOpenInventory().getTopInventory() == inventory;
    }

    void release(AnimationSessionStore.Token token) {
        Expected entry = expected.get(token.viewer());
        if (entry == null || !entry.token.equals(token)) return;
        expected.remove(token.viewer(), entry);
        fence.release(token);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        PacketEvents.getAPI().getEventManager().unregisterListener(probeListener);
        PacketEvents.getAPI().getEventManager().unregisterListener(captureListener);
        expected.clear();
        fence.clear();
    }
}
