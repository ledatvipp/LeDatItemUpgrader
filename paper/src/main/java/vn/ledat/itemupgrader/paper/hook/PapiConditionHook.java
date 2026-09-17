package vn.ledat.itemupgrader.paper.hook;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import vn.ledat.itemupgrader.condition.ConditionDefinition;
import vn.ledat.itemupgrader.condition.ConditionEvaluator;

/** Optional PAPI adapter. Called only on the Paper player owner/main thread, never in a worker. */
public final class PapiConditionHook {
    private static final long CAPTURE_BUDGET_NANOS = 5_000_000L;
    private final JavaPlugin owner;
    private long lastWarning;
    private boolean warned;
    public PapiConditionHook(JavaPlugin owner) { this.owner = owner; }
    public Map<String, ConditionEvaluator.Resolution> capture(Player player, List<ConditionDefinition> definitions) {
        if (definitions.isEmpty()) return Map.of();
        if (!owner.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) return Map.of();
        var values = new HashMap<String, ConditionEvaluator.Resolution>(); long started = System.nanoTime();
        for (String placeholder : definitions.stream().map(ConditionDefinition::placeholder).distinct().sorted().toList()) {
            if (System.nanoTime() - started >= CAPTURE_BUDGET_NANOS) {
                values.put(placeholder, new ConditionEvaluator.Resolution(ConditionEvaluator.Status.BUDGET_EXCEEDED, "")); continue;
            }
            try {
                String result = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, placeholder);
                if (result == null) values.put(placeholder, ConditionEvaluator.Resolution.missing());
                else if (result.length() > 256) values.put(placeholder, new ConditionEvaluator.Resolution(ConditionEvaluator.Status.INVALID_VALUE, ""));
                else values.put(placeholder, ConditionEvaluator.Resolution.value(result));
            } catch (RuntimeException | LinkageError error) {
                values.put(placeholder, new ConditionEvaluator.Resolution(ConditionEvaluator.Status.ERROR, ""));
                long now = System.nanoTime();
                if (!warned || now - lastWarning >= 30_000_000_000L) {
                    warned = true; lastWarning = now;
                    owner.getLogger().warning("PAPI condition resolution failed; denying affected conditions; error-type="
                            + error.getClass().getName()); // Do not log third-party messages that could contain placeholder output/secrets.
                }
            }
        }
        // This is a between-call budget: it cannot interrupt an expansion that blocks inside a single call.
        return Map.copyOf(values);
    }
}
