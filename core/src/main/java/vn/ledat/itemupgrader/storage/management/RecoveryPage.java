package vn.ledat.itemupgrader.storage.management;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import vn.ledat.itemupgrader.transaction.model.AttemptRecord;

/** Compact read-only candidates. Not receipt evidence and never input to an automatic refund/resume operation. */
public record RecoveryPage(List<Row> rows, boolean hasMore) {
    public enum LockStatus { OWNED, MISSING, CONFLICT }
    public record Row(UUID transactionId, UUID playerId, AttemptRecord.State state, long version,
            Instant createdAt, Instant updatedAt, LockStatus lock) {
        public Row {
            Objects.requireNonNull(transactionId); Objects.requireNonNull(playerId); Objects.requireNonNull(state);
            Objects.requireNonNull(createdAt); Objects.requireNonNull(updatedAt); Objects.requireNonNull(lock);
            if (state == AttemptRecord.State.COMPLETED || state == AttemptRecord.State.ABORTED || version < 0 || version > 10000
                    || createdAt.isBefore(Instant.EPOCH) || updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("invalid recovery row");
        }
    }
    public RecoveryPage {
        rows = List.copyOf(rows);
        if (rows.size() > 50 || hasMore && rows.isEmpty()) throw new IllegalArgumentException("invalid recovery page");
        String previous = "";
        for (Row row : rows) {
            String key = row.transactionId().toString();
            if (key.compareTo(previous) <= 0) throw new IllegalArgumentException("unordered recovery rows");
            previous = key;
        }
    }
    public Optional<UUID> nextCursor() { return hasMore ? Optional.of(rows.getLast().transactionId()) : Optional.empty(); }
}
