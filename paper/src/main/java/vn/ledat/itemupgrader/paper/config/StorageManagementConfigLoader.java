package vn.ledat.itemupgrader.paper.config;

import java.time.Duration;
import vn.ledat.itemupgrader.history.HistorySettings;
import vn.ledat.itemupgrader.storage.management.StorageSettings;

final class StorageManagementConfigLoader {
    private StorageManagementConfigLoader() {}
    static StorageSettings load(YamlNode root, HistorySettings history) {
        root.allow("config-version", "schema-mode", "operation-timeout-seconds", "maintenance");
        if (root.integer("config-version", 1, 1) != 1) throw new IllegalArgumentException("storage-management.yml.config-version");
        final StorageSettings.Mode mode;
        try { mode = StorageSettings.Mode.valueOf(root.string("schema-mode")); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("storage-management.yml.schema-mode: expected OFF, VERIFY or INITIALIZE"); }
        var maintenance = root.section("maintenance");
        maintenance.allow("enabled", "interval-seconds");
        return new StorageSettings(mode, Duration.ofSeconds(root.integer("operation-timeout-seconds", 5, 120)),
            maintenance.bool("enabled"), Duration.ofSeconds(maintenance.integer("interval-seconds", 60, 86400)),
            history.retentionDays(), history.retentionBatch());
    }
}
