package vn.ledat.itemupgrader.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Caller supplies Platform-owned async connection; this class never closes that connection. */
public final class SchemaRepository {
    public int initialize(Connection connection, String table, long now) throws SQLException {
        SchemaPlan.identifier(table);
        if (!connection.getAutoCommit()) throw new SQLException("Schema initialization requires an unowned auto-commit connection");
        // MySQL DDL may implicitly commit. DDL is idempotent; it is NOT represented as transactional.
        for (String ddl : SchemaPlan.initialDdl(table)) {
            try (Statement statement = connection.createStatement()) { statement.executeUpdate(ddl); }
        }
        connection.setAutoCommit(false);
        try {
            Integer version = null;
            try (PreparedStatement query = connection.prepareStatement("SELECT version FROM " + table + " WHERE component=?")) {
                query.setString(1, "item-upgrader");
                try (ResultSet rows = query.executeQuery()) { if (rows.next()) version = rows.getInt(1); }
            }
            if (version != null && version != SchemaPlan.VERSION)
                throw new SQLException("Unsupported schema version: " + version + "; expected " + SchemaPlan.VERSION);
            if (version == null) {
                try (PreparedStatement insert = connection.prepareStatement("INSERT INTO " + table + " (component,version,updated_at) VALUES (?,?,?)")) {
                    insert.setString(1, "item-upgrader"); insert.setInt(2, SchemaPlan.VERSION); insert.setLong(3, now);
                    insert.executeUpdate();
                }
            }
            connection.commit();
        } catch (SQLException | RuntimeException error) {
            boolean rolledBack = false;
            try { connection.rollback(); rolledBack = true; }
            catch (SQLException rollback) { error.addSuppressed(rollback); }
            // Do not set autoCommit(true) after a failed rollback: JDBC may commit pending work.
            if (rolledBack) {
                try { connection.setAutoCommit(true); }
                catch (SQLException restore) { error.addSuppressed(restore); }
            }
            throw error;
        }
        connection.setAutoCommit(true);
        return SchemaPlan.VERSION;
    }
}
