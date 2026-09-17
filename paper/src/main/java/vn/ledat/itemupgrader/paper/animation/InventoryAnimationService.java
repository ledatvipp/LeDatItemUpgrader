package vn.ledat.itemupgrader.paper.animation;

import java.util.*;
import java.util.logging.Level;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import vn.ledat.itemupgrader.animation.*;
import vn.ledat.itemupgrader.gui.SlotDiff;
import vn.ledat.itemupgrader.paper.gui.PaperGuiScheduler;
import vn.ledat.itemupgrader.paper.message.Messages;
import vn.ledat.itemupgrader.paper.platform.PlatformAccess;
import vn.ledat.itemupgrader.runtime.*;

/**
 * Phase 7 exposes ADMIN PREVIEW only. Live journal-origin wiring is intentionally NOT bootstrapped.
 * One owner-tracked ticker, bounded session visits, one outstanding player callback per session.
 * Closing, skipping, exceptions, reload or disable cannot settle a transaction: there is no effect dependency.
 */
public final class InventoryAnimationService {
    private record Pending(UUID id, long revision, long started, Inventory expectedTop, AnimationRequest request, String preset) {}
    private static final class View {
        final AnimationHolder holder; final AnimationRenderer renderer; final AnimationConfiguration config;
        Map<Integer, ItemStack> last = Map.of();
        View(AnimationHolder holder, AnimationRenderer renderer, AnimationConfiguration config) {
            this.holder = holder; this.renderer = renderer; this.config = config;
        }
    }
    private final JavaPlugin owner;
    private final PlatformAccess platform;
    private final RuntimeStore<UpgraderRuntime> runtime;
    private final Messages messages;
    private final UUID ownerToken = UUID.randomUUID();
    private final AnimationSessionStore sessions = new AnimationSessionStore(System::nanoTime);
    private final Map<UUID, View> views = new HashMap<>();
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Set<AnimationSessionStore.Token> pendingActions = new HashSet<>();
    private final PaperGuiScheduler scheduler;
    private boolean stopped, logged;
    private long lastLog, observedRevision = -1; private int suppressed;
    public InventoryAnimationService(JavaPlugin owner, PlatformAccess platform, RuntimeStore<UpgraderRuntime> runtime, Messages messages) {
        this.owner = owner; this.platform = platform; this.runtime = runtime; this.messages = messages;
        this.scheduler = new PaperGuiScheduler(owner, platform);
    }
    public void start() {
        scheduler.repeat(() -> { try { tick(); } catch (RuntimeException error) { report(error); } }, 1);
    }
    public boolean owns(Inventory top) {
        return top.getHolder(false) instanceof AnimationHolder h && h.owned(ownerToken) && h.getInventory() == top;
    }
    public Optional<AnimationSessionStore.Token> identity(Player player, Inventory top) {
        if (stopped || !owns(top)) return Optional.empty();
        var holder = (AnimationHolder) top.getHolder(false); var v = views.get(player.getUniqueId());
        return v != null && v.holder == holder && holder.token().viewer().equals(player.getUniqueId())
                && sessions.current(holder.token()) && runtime.isCurrent(holder.token().revision())
                ? Optional.of(holder.token()) : Optional.empty();
    }
    public Optional<AnimationMenu.Role> action(AnimationSessionStore.Token token, vn.ledat.itemupgrader.gui.GuiClickPolicy.Input input) {
        View view = view(token);
        return view == null ? Optional.empty() : new AnimationClickPolicy().decide(input, view.config.menu()).action();
    }
    /** Called by command after validation. The requested result is an explicit simulation, not a roll. */
    public void preview(Player player, AnimationRequest.Outcome outcome, String selectedPreset) {
        if (stopped) return;
        var current = runtime.snapshot();
        if (current.isEmpty() || current.orElseThrow().value().animation().isEmpty()) { messages.send(player, "not-ready"); return; }
        var snapshot = current.orElseThrow(); var config = snapshot.value().animation().orElseThrow();
        if (!allowed(player)) { messages.send(player, "animation-access-denied"); return; }
        if (!player.getItemOnCursor().getType().isAir()) { messages.send(player, "gui-cursor-not-empty"); return; }
        if (!config.previewEnabled()) { messages.send(player, "animation-disabled"); return; }
        String preset = selectedPreset.isEmpty() ? config.defaultPreset() : selectedPreset;
        if (!config.presets().containsKey(preset)) { messages.send(player, "animation-invalid-arguments"); return; }
        UUID viewer = player.getUniqueId();
        if (pending.containsKey(viewer) || views.containsKey(viewer) || pending.size() + sessions.size() >= config.maximumSessions()) {
            messages.send(player, "backend-busy"); return;
        }
        if (!platform.acquire(viewer, "animation-preview", config.openCooldown())) { messages.send(player, "cooldown"); return; }
        Pending job = new Pending(UUID.randomUUID(), snapshot.revision(), System.nanoTime(), player.getOpenInventory().getTopInventory(),
                AnimationRequest.preview(viewer, UUID.randomUUID(), outcome), preset);
        pending.put(viewer, job);
        try { scheduler.next(viewer, online -> open(online, job)); }
        catch (RuntimeException rejected) { pending.remove(viewer, job); report(rejected); messages.send(player, "animation-error"); }
    }
    private void open(Player player, Pending job) {
        UUID viewer = player.getUniqueId();
        if (stopped || !pending.remove(viewer, job) || !runtime.isCurrent(job.revision())) return;
        var current = runtime.snapshot(); if (current.isEmpty()) return;
        var config = current.orElseThrow().value().animation().orElseThrow();
        if (!config.previewEnabled() || !allowed(player) || !player.getItemOnCursor().getType().isAir()
                || player.getOpenInventory().getTopInventory() != job.expectedTop()
                || System.nanoTime() - job.started() >= config.callbackTimeout().toNanos()) return;
        var preset = config.presets().get(job.preset());
        var opened = sessions.open(job.id(), job.revision(), job.request(), preset, config.menu().trackOrder().size(),
                config.maximumSessions(), config.callbackTimeout(), config.pulseInterval());
        if (opened.isEmpty()) { messages.send(player, "backend-busy"); return; }
        var token = opened.orElseThrow();
        try {
            var renderer = new AnimationRenderer(messages, config, job.request(), job.preset());
            var holder = new AnimationHolder(ownerToken, token, config.menu().size(), renderer.title());
            var view = new View(holder, renderer, config); views.put(viewer, view);
            // Fill before open; first batch update acknowledges what the player actually sees.
            apply(view, new AnimationTimeline(preset, config.menu().trackOrder().size(), job.request()).sample(0));
            player.openInventory(holder.getInventory());
            if (player.getOpenInventory().getTopInventory() != holder.getInventory()) { remove(token); return; }
            messages.send(player, "animation-preview-start", Map.of("preset", preset.id()));
        } catch (RuntimeException error) { failure(token, error); }
    }
    private void tick() {
        if (stopped) return;
        var current = runtime.snapshot(); long revision = current.map(RuntimeStore.Snapshot::revision).orElse(0L);
        if (observedRevision != revision) {
            observedRevision = revision;
            for (var token : sessions.invalidateRevision(revision)) closeExact(token, "animation-stopped-reload");
        }
        long now = System.nanoTime();
        pending.entrySet().removeIf(entry -> entry.getValue().revision() != revision
                || now - entry.getValue().started() >= current.flatMap(s -> s.value().animation())
                .map(c -> c.callbackTimeout().toNanos()).orElse(1_000_000_000L));
        int budget = current.flatMap(s -> s.value().animation()).map(AnimationConfiguration::visitsPerTick).orElse(16);
        for (var update : sessions.poll(budget)) {
            if (update.end().isPresent()) {
                closeExact(update.token(), update.end().orElseThrow() == AnimationSessionStore.End.CALLBACK_TIMEOUT ? "animation-timeout" : null);
                continue;
            }
            try { platform.player(update.token().viewer(), player -> render(player, update)); }
            catch (RuntimeException error) { failure(update.token(), error); }
        }
    }
    private void render(Player player, AnimationSessionStore.Update update) {
        if (stopped || !sessions.current(update)) return;
        var v = view(update.token());
        if (v == null || !runtime.isCurrent(update.token().revision()) || !allowed(player)
                || player.getOpenInventory().getTopInventory() != v.holder.getInventory()
                || !player.getItemOnCursor().getType().isAir()) { closeExact(update.token(), null); return; }
        try {
            apply(v, update.frame().orElseThrow());
            sessions.acknowledge(update).ifPresent(cue -> playCue(player, v.config, cue));
        } catch (RuntimeException error) { failure(update.token(), error); }
    }
    private void apply(View v, AnimationTimeline.Frame frame) {
        Map<Integer, ItemStack> next = v.renderer.render(frame);
        for (var entry : SlotDiff.changed(v.last, next, v.holder.getInventory().getSize()).entrySet())
            v.holder.getInventory().setItem(entry.getKey(), entry.getValue().clone());
        v.last = next;
    }
    /** The listener cancels everything first. At most one deferred action per session. */
    public void click(Player player, AnimationSessionStore.Token token, AnimationMenu.Role role) {
        if (role != AnimationMenu.Role.CLOSE && role != AnimationMenu.Role.SKIP) return;
        if (!sessions.current(token) || !pendingActions.add(token)) return;
        try {
            scheduler.next(token.viewer(), p -> {
                if (!pendingActions.remove(token) || !sessions.current(token)) return;
                var view = view(token);
                if (view == null || identity(p, p.getOpenInventory().getTopInventory()).filter(token::equals).isEmpty() || !allowed(p)) return;
                if (role == AnimationMenu.Role.CLOSE) closeExact(token, null);
                else if (sessions.skip(token)) messages.send(p, "animation-skipped");
            });
        } catch (RuntimeException error) { pendingActions.remove(token); failure(token, error); }
    }
    public void closed(UUID viewer, AnimationHolder holder) {
        if (holder.owned(ownerToken) && holder.token().viewer().equals(viewer)) remove(holder.token());
    }
    public void forget(UUID viewer) {
        pending.remove(viewer); var v = views.get(viewer); if (v != null) remove(v.holder.token());
    }
    /** Lifecycle handler schedules closing; never closes a replacement or foreign container. */
    public void exit(UUID viewer) {
        pending.remove(viewer); var v = views.get(viewer); if (v == null) return;
        var token = v.holder.token(); sessions.close(token);
        try { scheduler.next(viewer, p -> closeExact(token, null)); }
        catch (RuntimeException error) { failure(token, error); }
    }
    private View view(AnimationSessionStore.Token token) {
        View v = views.get(token.viewer()); return v != null && v.holder.token().equals(token) ? v : null;
    }
    private void remove(AnimationSessionStore.Token token) {
        sessions.close(token); pendingActions.remove(token); var v = view(token);
        if (v != null) views.remove(token.viewer(), v);
    }
    private void closeExact(AnimationSessionStore.Token token, String message) {
        View v = view(token); remove(token); if (v == null) return;
        try {
            platform.player(token.viewer(), p -> {
                if (p.getOpenInventory().getTopInventory() == v.holder.getInventory()) {
                    p.closeInventory(); if (message != null) messages.send(p, message);
                }
            });
        } catch (RuntimeException error) { report(error); } // session already removed; no monetary effect is retried
    }
    private void failure(AnimationSessionStore.Token token, RuntimeException error) { report(error); closeExact(token, "animation-error"); }
    private void report(RuntimeException error) {
        long now = System.nanoTime();
        if (logged && now - lastLog < 5_000_000_000L) { suppressed++; return; }
        logged = true; lastLog = now;
        owner.getLogger().log(Level.WARNING, "Animation presentation failed; no transaction modified; suppressed=" + suppressed, error);
        suppressed = 0;
    }
    private static boolean allowed(Player player) {
        return player.isOnline() && !player.isDead() && player.hasPermission("ledatitemupgrader.use")
                && player.hasPermission("ledatitemupgrader.admin.animation")
                && (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE);
    }
    private static void playCue(Player player, AnimationConfiguration config, AnimationSessionStore.Cue role) {
        var cue = config.sounds().get(role);
        if (cue != null && !cue.key().isEmpty()) player.playSound(Sound.sound(Key.key(cue.key()), Sound.Source.MASTER, cue.volume(), cue.pitch()));
    }
    public void close() {
        if (stopped) return; stopped = true; scheduler.close(); sessions.stop(); pending.clear(); pendingActions.clear();
        // Paper onDisable owner thread, no retired player callback fallback.
        for (var entry : List.copyOf(views.entrySet())) {
            Player p = owner.getServer().getPlayer(entry.getKey());
            if (p != null && p.getOpenInventory().getTopInventory() == entry.getValue().holder.getInventory()) p.closeInventory();
        }
        views.clear();
    }
}
