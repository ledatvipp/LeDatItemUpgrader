package vn.ledat.itemupgrader.storage.management;

import java.time.Duration;
import java.util.Objects;

/** Explicit schema lifecycle policy, independent from the upgrade feature gate.
 * Retention affects terminal display history only, never journal/output/receipts. */
public record StorageSettings(Mode mode, Duration operationTimeout, boolean pruneEnabled,
        Duration pruneInterval, int retentionDays, int pruneBatch) {
    public enum Mode { OFF, VERIFY, INITIALIZE }
    public StorageSettings {
        Objects.requireNonNull(mode); Objects.requireNonNull(operationTimeout); Objects.requireNonNull(pruneInterval);
        if (operationTimeout.compareTo(Duration.ofSeconds(5)) < 0 || operationTimeout.compareTo(Duration.ofMinutes(2)) > 0
                || pruneInterval.compareTo(Duration.ofMinutes(1)) < 0 || pruneInterval.compareTo(Duration.ofDays(1)) > 0
                || retentionDays < 1 || retentionDays > 3650 || pruneBatch < 1 || pruneBatch > 1000)
            throw new IllegalArgumentException("storage-management bounds");
        if (mode == Mode.OFF && pruneEnabled) throw new IllegalArgumentException("OFF storage cannot prune");
    }
    public static StorageSettings off() {
        return new StorageSettings(Mode.OFF, Duration.ofSeconds(30), false, Duration.ofHours(1), 90, 200);
    }
}
