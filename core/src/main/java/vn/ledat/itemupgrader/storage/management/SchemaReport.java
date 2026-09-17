package vn.ledat.itemupgrader.storage.management;

import vn.ledat.itemupgrader.transaction.storage.JournalSql;
import java.util.Objects;

/** Structural schema verification, NOT a row-integrity scan or proof of external-effect durability. */
public record SchemaReport(JournalSql.Dialect dialect, int tables, int indexes, boolean initializeRequested) {
    public SchemaReport {
        Objects.requireNonNull(dialect);
        if (tables != 12 || indexes != 4) throw new IllegalArgumentException("incomplete upgrader schema report");
    }
}
