package vn.ledat.itemupgrader.paper.platform;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import vn.ledat.platform.api.LeDatPlatformApi;
import vn.ledat.platform.api.LeDatPlatformProvider;

/**
 * Direct API-only binding to the supplied 2.10.0 guide. No reflection or embedded API stubs.
 * Compilation/linkage against the REAL API artifact remains a release gate for this delivery.
 */
public final class PlatformAccess {
    @FunctionalInterface public interface SqlWork<T> { T apply(Connection connection) throws SQLException; }
    private final JavaPlugin owner;
    private final LeDatPlatformApi api;
    public PlatformAccess(JavaPlugin owner) {
        this.owner = owner;
        this.api = LeDatPlatformProvider.get();
    }
    public String apiVersion() { return String.valueOf(api.apiVersion()); }
    public void async(String name, Runnable task) { api.scheduler().scoped(owner).runAsync(name, task); }
    public void player(UUID id, Consumer<Player> task) { api.scheduler().scoped(owner).runOnlinePlayer(id, task); }
    public boolean acquire(UUID player, String action, Duration duration) {
        return api.cooldowns().tryAcquire(owner.getName(), player, action, duration);
    }
    public Optional<Object> identity(ItemStack item) { return api.items().identify(item).map(key -> (Object) key); }
    public Optional<ItemStack> create(String key) { return api.items().create(key, 1); }
    /** Call only on the Paper owner thread. Availability/number validation belongs to the snapshot adapter. */
    public Optional<java.math.BigDecimal> balance(Player player, String providerId) {
        return api.economy().provider(providerId).map(provider -> provider.balance(player));
    }
    public void ensureFiles() {
        api.configs().ensureMainConfig(owner, 1);
        for (String path : new String[]{"storage-management.yml", "messages.yml", "upgrades/values.yml", "upgrades/recipes.yml", "menus/upgrader.yml", "upgrades/catalog.yml", "upgrades/paths.yml", "upgrades/chance.yml", "upgrades/profiles.yml", "upgrades/boosts.yml", "upgrades/conditions.yml", "upgrades/outputs.yml", "menus/settings.yml", "menus/catalog.yml", "menus/profiles.yml", "menus/boosts.yml", "menus/animation.yml", "history.yml", "upgrades/pity.yml", "menus/history.yml"})
            api.configs().ensureBundledYaml(owner, path);
    }
    public <T> CompletableFuture<T> query(String label, SqlWork<T> work) {
        return api.database().query(label, work::apply);
    }
    public String tableName(String suffix) { return api.database().tableName("itemupgrader", suffix); }
    /** Route callback must only read bounded display caches; never starts I/O. */
    public void registerHistoryPlaceholders(java.util.function.BiFunction<UUID,String,String> resolver) {
        api.placeholders().registerRoute(owner,"itemupgrader",(player,parameter)->player==null?"":resolver.apply(player.getUniqueId(),parameter),"ItemUpgrader cached completed statistics");
    }
    public void unregisterPlaceholders(){api.placeholders().unregisterPlugin(owner);}
    public void shutdown() {
        // Bounded shutdown wait only. No live command/event path ever joins a database future.
        try {
            api.database().flush(owner).get(3, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            owner.getLogger().log(Level.WARNING, "Interrupted while flushing plugin storage", interrupted);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException error) {
            owner.getLogger().warning("Plugin storage flush did not complete within shutdown budget; " + vn.ledat.itemupgrader.storage.management.SafeFailure.describe(error));
        } finally {
            api.placeholders().unregisterPlugin(owner);
            api.scheduler().cancelAll(owner);
            api.database().closePlugin(owner.getName());
        }
    }
}
