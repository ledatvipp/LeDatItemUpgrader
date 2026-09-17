package vn.ledat.itemupgrader.storage;

import java.util.List;

/** Initial schema only. Economy/item transaction tables intentionally belong to Phase 4. */
public final class SchemaPlan {
    public static final int VERSION = 1;
    private SchemaPlan() {}
    public static String identifier(String name) {
        if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_]{0,62}"))
            throw new IllegalArgumentException("unsafe SQL identifier");
        return name;
    }
    public static List<String> initialDdl(String metadataTable) {
        String table = identifier(metadataTable);
        return List.of("CREATE TABLE IF NOT EXISTS " + table
                + " (component VARCHAR(64) PRIMARY KEY, version INTEGER NOT NULL, updated_at BIGINT NOT NULL)");
    }
}
