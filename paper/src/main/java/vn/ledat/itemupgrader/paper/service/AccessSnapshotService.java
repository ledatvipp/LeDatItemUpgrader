package vn.ledat.itemupgrader.paper.service;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import vn.ledat.itemupgrader.catalog.CatalogAccess;
import vn.ledat.itemupgrader.condition.ConditionEvaluator;
import vn.ledat.itemupgrader.paper.hook.PapiConditionHook;
import vn.ledat.itemupgrader.runtime.UpgraderRuntime;

/** Only detached sets leave the owner thread. Raw PAPI text is not retained in a quote or sent to MiniMessage. */
public final class AccessSnapshotService {
    private final PapiConditionHook placeholders;
    private final ConditionEvaluator evaluator = new ConditionEvaluator();
    public AccessSnapshotService(JavaPlugin owner) { placeholders = new PapiConditionHook(owner); }
    public CatalogAccess capture(Player player, UpgraderRuntime runtime) {
        var rules = runtime.upgradeRules();
        var permissions = rules.permissionNodes().stream().filter(player::hasPermission).collect(java.util.stream.Collectors.toUnmodifiableSet());
        var conditions = rules.requiredConditionDefinitions();
        var evidence = evaluator.evaluate(conditions, placeholders.capture(player, conditions));
        return new CatalogAccess(player.getUniqueId(), permissions, evidence.satisfied());
    }
}
