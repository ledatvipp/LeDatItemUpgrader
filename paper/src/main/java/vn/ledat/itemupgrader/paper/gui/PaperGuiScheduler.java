package vn.ledat.itemupgrader.paper.gui;

import java.util.*;
import java.util.function.Consumer;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import vn.ledat.itemupgrader.paper.platform.PlatformAccess;

/**
 * Paper-only next-tick bridge: the supplied Platform guide has no delayed scheduler signature.
 * Native task callbacks route Player operations through Platform. No raw executor or Folia claim.
 * Each bridge cancels ONLY its own handles; closing GUI must not cancel the animation ticker and vice versa.
 */
public final class PaperGuiScheduler {
    private final JavaPlugin owner;
    private final PlatformAccess platform;
    private final Set<ScheduledTask> tasks = new HashSet<>(); // Paper owner thread only
    private boolean stopped;
    public PaperGuiScheduler(JavaPlugin owner, PlatformAccess platform) { this.owner = owner; this.platform = platform; }
    public void next(UUID viewer, Consumer<Player> action) {
        if (stopped) throw new IllegalStateException("GUI scheduler stopped");
        var task = owner.getServer().getGlobalRegionScheduler().run(owner, scheduled -> {
            tasks.remove(scheduled);
            if (!stopped) platform.player(viewer, p -> { if (!stopped) action.accept(p); });
        });
        tasks.add(task);
    }
    public void sweep(Runnable sweep) { repeat(sweep, 20); }
    public void repeat(Runnable action, long periodTicks) {
        if (stopped || periodTicks < 1) throw new IllegalStateException("scheduler stopped or invalid period");
        tasks.add(owner.getServer().getGlobalRegionScheduler().runAtFixedRate(owner, task -> {
            if (!stopped) action.run();
        }, periodTicks, periodTicks));
    }
    public void close() {
        if (stopped) return; stopped = true;
        for (var task : List.copyOf(tasks)) task.cancel();
        tasks.clear();
    }
}
