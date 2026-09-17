package vn.ledat.itemupgrader.paper;

import java.util.logging.Level;
import org.bukkit.plugin.java.JavaPlugin;
import vn.ledat.itemupgrader.paper.bootstrap.PluginBootstrap;

public final class LeDatItemUpgraderPlugin extends JavaPlugin {
    private PluginBootstrap bootstrap;
    @Override public void onEnable() {
        try {
            bootstrap = new PluginBootstrap(this);
            bootstrap.start();
        } catch (RuntimeException | LinkageError error) {
            getLogger().log(Level.SEVERE, "ItemUpgrader bootstrap failed; check Paper/LeDatPlatform API compatibility", error);
            getServer().getPluginManager().disablePlugin(this);
        }
    }
    @Override public void onDisable() { if (bootstrap != null) bootstrap.stop(); }
}
