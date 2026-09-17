package vn.ledat.itemupgrader.storage.management;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import vn.ledat.itemupgrader.history.storage.*;
import vn.ledat.itemupgrader.output.storage.*;
import vn.ledat.itemupgrader.transaction.storage.*;
import static vn.ledat.itemupgrader.storage.management.SchemaProblem.Code.*;

/** One namespace/connection for all SQL components. Does NOT start a live writer or perform provider effects.
 * VERIFY is read-only. INITIALIZE is explicit and may leave partial DDL (especially MySQL); never drop/repair
 * existing data or downgrade a schema. Single server initializer, not a distributed migration lock.
 * Verification checks version, named columns, PK/unique keys, required index order and InnoDB on MySQL.
 * It is NOT a type/nullability/collation audit or exhaustive row-integrity check. */
public final class JdbcStorageBootstrap {
    private record Table(String name, List<String> columns, List<String> primary, List<List<String>> unique) {}
    private record Component(String metadata, String id, List<String> dataTables) {}
    private record Index(String table, String name, List<String> columns) {}
    private record SeenIndex(boolean unique, boolean filtered, List<String> columns) {}
    private final JournalSql journalSql;
    private final OutputSql outputSql;
    private final ProgressSql progressSql;
    private final List<Table> tables;
    private final List<Component> components;
    private final List<Index> indexes;
    private final JdbcProgressRepository progress;
    private final JdbcTransactionRepository journal;
    private final JdbcOutputRepository outputs;

    public JdbcStorageBootstrap(JournalSql journalSql, OutputSql outputSql, ProgressSql progressSql) {
        this.journalSql = Objects.requireNonNull(journalSql); this.outputSql = Objects.requireNonNull(outputSql);
        this.progressSql = Objects.requireNonNull(progressSql);
        var j = journalSql.tables(); var p = progressSql.tables(); var o = outputSql;
        tables = List.of(
            table(j.metadata(), "component,version,updated_at", "component"),
            table(j.attempts(), "tx_id,idempotency_key,player_uuid,plan_digest,plan_payload,state,version,created_at,updated_at,sample,payload,payload_digest", "tx_id", "idempotency_key"),
            table(j.locks(), "player_uuid,tx_id", "player_uuid", "tx_id"),
            table(j.events(), "tx_id,version,state,created_at,payload_digest", "tx_id,version"),
            table(o.schema(), "component,version", "component"),
            table(o.outputs(), "attempt_id,player_uuid,plan_digest,state,payload,payload_digest,reason,created_at", "attempt_id"),
            table(o.identities(), "token_digest,attempt_id,role", "token_digest"),
            table(p.metadata(), "component,version", "component"),
            table(p.history(), "tx_id,player_uuid,version,state,outcome,source_key,source_amount,target_id,target_key,target_amount,source_value,target_value,tickets,profile,failure,created_at,updated_at,terminal,plan_digest,state_digest", "tx_id"),
            table(p.statistics(), "player_uuid,completed,wins,losses", "player_uuid"),
            table(p.pity(), "player_uuid,scope,version,failures", "player_uuid,scope"),
            table(p.completions(), "tx_id,player_uuid,plan_digest,state_digest,completed_at", "tx_id"));
        if (tables.stream().map(t -> t.name().toLowerCase(Locale.ROOT)).distinct().count() != tables.size())
            throw new IllegalArgumentException("cross-component table namespace collision");
        components = List.of(new Component(j.metadata(), "transactions-v1", List.of(j.attempts(), j.locks(), j.events())),
            new Component(o.schema(), "outputs-v1", List.of(o.outputs(), o.identities())),
            new Component(p.metadata(), "progress-v1", List.of(p.history(), p.statistics(), p.pity(), p.completions())));
        indexes = List.of(new Index(j.attempts(), j.attempts() + "_player_time", split("player_uuid,created_at,tx_id")),
            new Index(j.attempts(), j.attempts() + "_state_time", split("state,updated_at,tx_id")),
            new Index(p.history(), p.history() + "_owner_time", split("player_uuid,created_at,tx_id")),
            new Index(p.history(), p.history() + "_terminal_time", split("terminal,updated_at,tx_id")));
        progress = new JdbcProgressRepository(progressSql);
        journal = new JdbcTransactionRepository(journalSql, progress); // Never an unhooked writer from this bundle.
        outputs = new JdbcOutputRepository(outputSql);
    }
    public static JdbcStorageBootstrap prefixed(String prefix) {
        return new JdbcStorageBootstrap(new JournalSql(JournalSql.Tables.prefixed(prefix)),
            new OutputSql(prefix + "outputs", prefix + "output_identities", prefix + "output_schema"),
            new ProgressSql(ProgressSql.Tables.prefixed(prefix)));
    }
    public JdbcProgressRepository progress() { return progress; }
    public JdbcTransactionRepository journal() { return journal; }
    public JdbcOutputRepository outputs() { return outputs; }
    public JournalSql journalSql() { return journalSql; }
    public ProgressSql progressSql() { return progressSql; }

    public SchemaReport prepare(Connection connection, StorageSettings.Mode mode, Instant now) throws SQLException {
        Objects.requireNonNull(connection); Objects.requireNonNull(mode); Objects.requireNonNull(now);
        if (mode == StorageSettings.Mode.OFF) throw new IllegalArgumentException("OFF mode performs no SQL");
        if (!connection.getAutoCommit()) throw new SchemaProblem(BORROWED_TRANSACTION);
        var dialect = dialect(connection.getMetaData().getDatabaseProductName());
        // Preflight ALL components before any CREATE. No partial upgrade of an unknown newer component.
        inspect(connection, dialect, mode == StorageSettings.Mode.INITIALIZE);
        if (mode == StorageSettings.Mode.INITIALIZE) {
            progress.initialize(connection, dialect);
            outputs.initialize(connection, dialect);
            journal.initialize(connection, dialect, now);
            inspect(connection, dialect, false);
        }
        return new SchemaReport(dialect, tables.size(), indexes.size(), mode == StorageSettings.Mode.INITIALIZE);
    }
    public static JournalSql.Dialect dialect(String product) throws SQLException {
        if(product==null)throw new SchemaProblem(UNSUPPORTED_DATABASE);
        return switch (product) {
            case "SQLite" -> JournalSql.Dialect.SQLITE;
            case "MySQL", "MariaDB" -> JournalSql.Dialect.MYSQL;
            default -> throw new SchemaProblem(UNSUPPORTED_DATABASE);
        };
    }
    private void inspect(Connection c, JournalSql.Dialect dialect, boolean allowMissing) throws SQLException {
        Set<String> existing = new HashSet<>();
        for (Table table : tables) {
            boolean exists = exists(c, table.name());
            if (!exists) { if (!allowMissing) throw new SchemaProblem(MISSING_TABLE); else continue; }
            existing.add(table.name());
        }
        // Validate versions before inspecting shape so a future schema is never mistaken for a repair target.
        for (Component component : components) {
            Integer version = existing.contains(component.metadata()) ? version(c, component) : null;
            if (version != null && version != 1) throw new SchemaProblem(UNSUPPORTED_VERSION);
            if (version != null && component.dataTables().stream().anyMatch(t -> !existing.contains(t)))
                throw new SchemaProblem(MISSING_TABLE); // Versioned table loss is not a fresh install; never bless an empty replacement.
            if (version == null) {
                if (!allowMissing) throw new SchemaProblem(UNSUPPORTED_VERSION);
                for (String table : component.dataTables()) if (existing.contains(table) && nonEmpty(c, table))
                    throw new SchemaProblem(UNVERSIONED_DATA);
            }
        }
        for (Table table : tables) if (existing.contains(table.name())) {
            if (dialect == JournalSql.Dialect.MYSQL) verifyEngine(c, table.name());
            try (var s = prepare(c, "SELECT " + String.join(",", table.columns()) + " FROM " + table.name() + " WHERE 1=0");
                 var rows = s.executeQuery()) {
                if (rows.next()) throw new SchemaProblem(COLUMNS);
            } catch (SQLException invalid) { throw problem(COLUMNS, invalid); }
            var primary = new TreeMap<Integer,String>();
            try (var r = c.getMetaData().getPrimaryKeys(c.getCatalog(), null, table.name())) {
                while (r.next()) {
                    if (!table.name().equalsIgnoreCase(r.getString("TABLE_NAME"))) continue;
                    if (primary.put(r.getInt("KEY_SEQ"), normalized(r.getString("COLUMN_NAME"))) != null) throw new SchemaProblem(PRIMARY_KEY);
                }
            }
            if (!new ArrayList<>(primary.values()).equals(table.primary())) throw new SchemaProblem(PRIMARY_KEY);
            Map<String,SeenIndex> actual = indexInfo(c, table.name());
            if (dialect == JournalSql.Dialect.SQLITE) markPartialSqliteIndexes(c, table.name(), actual);
            for (List<String> required : table.unique())
                if (actual.values().stream().noneMatch(v -> v.unique() && !v.filtered() && v.columns().equals(required))) throw new SchemaProblem(UNIQUE_KEY);
            for (Index required : indexes) if (required.table().equals(table.name())) {
                var found = actual.get(required.name().toLowerCase(Locale.ROOT));
                if (found == null) { if (!allowMissing) throw new SchemaProblem(INDEX); }
                else if (found.filtered() || !found.columns().equals(required.columns())) throw new SchemaProblem(INDEX);
            }
        }
    }
    private static boolean exists(Connection c, String table) throws SQLException {
        boolean found = false;
        var meta = c.getMetaData();
        String escape = meta.getSearchStringEscape();
        if (escape == null) escape = "";
        String pattern = escape.isEmpty() ? table : table.replace(escape, escape + escape).replace("_", escape + "_");
        // The metadata pattern can match other names when a driver ignores escaping; filter exact table names too.
        try (var rows = meta.getTables(c.getCatalog(), null, pattern, new String[]{"TABLE", "VIEW"})) {
            while (rows.next()) if (table.equalsIgnoreCase(rows.getString("TABLE_NAME"))) {
                if (found || !"TABLE".equalsIgnoreCase(rows.getString("TABLE_TYPE"))) throw new SchemaProblem(WRONG_TABLE_TYPE);
                found = true;
            }
        }
        return found;
    }
    private static Integer version(Connection c, Component component) throws SQLException {
        try (var s = prepare(c, "SELECT component,version FROM " + component.metadata() + " LIMIT 2"); var r = s.executeQuery()) {
            if (!r.next()) return null;
            if (!component.id().equals(r.getString(1))) throw new SchemaProblem(UNSUPPORTED_VERSION);
            // getInt can truncate a dynamically typed SQLite value such as 1.5 to 1.
            // Schema identity must match exactly, not through numeric coercion.
            String result = r.getString(2);
            if (!"1".equals(result) || r.next()) throw new SchemaProblem(UNSUPPORTED_VERSION);
            return 1;
        }
    }
    private static boolean nonEmpty(Connection c, String table) throws SQLException {
        try (var s = prepare(c, "SELECT 1 FROM " + table + " LIMIT 1"); var r = s.executeQuery()) { return r.next(); }
    }
    private static Map<String,SeenIndex> indexInfo(Connection c, String table) throws SQLException {
        Map<String,TreeMap<Integer,String>> keys = new HashMap<>(); Map<String,Boolean> unique = new HashMap<>(), filtered = new HashMap<>();
        try (var r = c.getMetaData().getIndexInfo(c.getCatalog(), null, table, false, false)) {
            while (r.next()) {
                String name = r.getString("INDEX_NAME"); if (name == null) continue;
                name = name.toLowerCase(Locale.ROOT);
                var columns = keys.computeIfAbsent(name, ignored -> new TreeMap<>());
                if (columns.put(r.getInt("ORDINAL_POSITION"), normalized(r.getString("COLUMN_NAME"))) != null) throw new SchemaProblem(INDEX);
                unique.put(name, !r.getBoolean("NON_UNIQUE"));
                filtered.put(name, r.getString("FILTER_CONDITION") != null);
            }
        }
        Map<String,SeenIndex> result = new HashMap<>();
        keys.forEach((name, columns) -> result.put(name, new SeenIndex(unique.get(name), filtered.get(name), List.copyOf(columns.values()))));
        return result;
    }
    private static void markPartialSqliteIndexes(Connection c, String table, Map<String,SeenIndex> indexes) throws SQLException {
        // Some JDBC metadata implementations omit FILTER_CONDITION; inspect SQLite's actual partial flag too.
        try (var s = prepare(c, "SELECT name FROM pragma_index_list(?) WHERE partial<>0")) {
            s.setString(1, table);
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    String name = normalized(r.getString(1)); var prior = indexes.get(name);
                    if (prior != null) indexes.put(name, new SeenIndex(prior.unique(), true, prior.columns()));
                }
            }
        }
    }
    private static void verifyEngine(Connection c, String table) throws SQLException {
        try (var s = prepare(c, "SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?")) {
            s.setString(1, table);
            try (var rows = s.executeQuery()) {
                if (!rows.next() || !"InnoDB".equalsIgnoreCase(rows.getString(1)) || rows.next()) throw new SchemaProblem(ENGINE);
            }
        }
    }
    private static String normalized(String value) { return value == null ? "<expression>" : value.toLowerCase(Locale.ROOT); }
    private static PreparedStatement prepare(Connection c, String text) throws SQLException {
        var s = c.prepareStatement(text);
        try { s.setQueryTimeout(10); return s; } catch (SQLException error) { try { s.close(); } catch (SQLException close) { error.addSuppressed(close); } throw error; }
    }
    private static SchemaProblem problem(SchemaProblem.Code code, SQLException cause) { var problem = new SchemaProblem(code); problem.initCause(cause); return problem; }
    private static List<String> split(String text) { return List.of(text.split(",")); }
    private static Table table(String name, String columns, String primary, String... unique) {
        return new Table(name, split(columns), split(primary), Arrays.stream(unique).map(JdbcStorageBootstrap::split).toList());
    }
}
