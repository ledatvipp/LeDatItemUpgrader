package vn.ledat.itemupgrader.storage.management;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import vn.ledat.itemupgrader.transaction.model.AttemptRecord;
import vn.ledat.itemupgrader.transaction.storage.JournalSql;

/** Bounded administrative candidate query. No plan_payload, payload, sample or receipt read/deserialization.
 * Does not promise a point-in-time global scan: new UUIDs can fall before an existing cursor; refresh to rescan. */
public final class JdbcRecoveryInspector {
    private final String sql;
    public JdbcRecoveryInspector(JournalSql.Tables tables) {
        Objects.requireNonNull(tables);
        sql = "SELECT a.tx_id,a.player_uuid,a.state,a.version,a.created_at,a.updated_at,l.tx_id AS lock_tx FROM "
                + tables.attempts() + " a LEFT JOIN " + tables.locks() + " l ON l.player_uuid=a.player_uuid"
                + " WHERE a.state NOT IN ('COMPLETED','ABORTED') AND a.tx_id>? ORDER BY a.tx_id ASC LIMIT ?";
    }
    public String query() { return sql; }
    public RecoveryPage page(Connection connection, Optional<UUID> after, int maximum) throws SQLException {
        Objects.requireNonNull(connection); Objects.requireNonNull(after);
        if (maximum < 1 || maximum > 50) throw new IllegalArgumentException("recovery page must be 1..50");
        List<RecoveryPage.Row> rows = new ArrayList<>();
        try (var s = connection.prepareStatement(sql)) {
            s.setQueryTimeout(10); s.setString(1, after.map(UUID::toString).orElse("")); s.setInt(2, maximum + 1);
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    if (rows.size() == maximum + 1) throw new SQLException("recovery query exceeded bound");
                    UUID id = uuid(r.getString("tx_id")), player = uuid(r.getString("player_uuid"));
                    String lock = r.getString("lock_tx");
                    RecoveryPage.LockStatus status = lock == null ? RecoveryPage.LockStatus.MISSING
                            : uuid(lock).equals(id) ? RecoveryPage.LockStatus.OWNED : RecoveryPage.LockStatus.CONFLICT;
                    var state = AttemptRecord.State.valueOf(Objects.requireNonNull(r.getString("state")));
                    rows.add(new RecoveryPage.Row(id, player, state, requiredLong(r, "version"),
                            Instant.ofEpochMilli(requiredLong(r, "created_at")), Instant.ofEpochMilli(requiredLong(r, "updated_at")), status));
                }
            }
        } catch (IllegalArgumentException | NullPointerException corrupt) { throw new SQLException("invalid recovery summary", corrupt); }
        boolean more = rows.size() > maximum;
        if (more) rows.removeLast();
        try { return new RecoveryPage(rows, more); } catch (IllegalArgumentException corrupt) { throw new SQLException("invalid recovery page", corrupt); }
    }
    private static long requiredLong(ResultSet r, String column) throws SQLException {
        long result = r.getLong(column); if (r.wasNull()) throw new SQLException("null recovery number"); return result;
    }
    private static UUID uuid(String input) throws SQLException {
        if (input == null || !input.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
            throw new SQLException("invalid canonical recovery UUID");
        return UUID.fromString(input);
    }
}
