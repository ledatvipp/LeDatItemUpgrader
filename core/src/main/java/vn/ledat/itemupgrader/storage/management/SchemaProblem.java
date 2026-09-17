package vn.ledat.itemupgrader.storage.management;

import java.sql.SQLException;

/** Stable diagnosis code, deliberately excludes driver message, JDBC URL and credentials. */
public final class SchemaProblem extends SQLException {
    private static final long serialVersionUID = 1L;
    public enum Code { BORROWED_TRANSACTION, UNSUPPORTED_DATABASE, MISSING_TABLE, WRONG_TABLE_TYPE,
        UNSUPPORTED_VERSION, UNVERSIONED_DATA, COLUMNS, PRIMARY_KEY, UNIQUE_KEY, INDEX, ENGINE }
    private final Code code;
    public SchemaProblem(Code code) { super("upgrader schema: " + code.name()); this.code = code; }
    public Code code() { return code; }
}
