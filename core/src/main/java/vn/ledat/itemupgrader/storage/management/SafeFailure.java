package vn.ledat.itemupgrader.storage.management;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Log codes/types, never Throwable.toString(), driver messages, suppressed messages or connection URLs. */
public final class SafeFailure {
    private SafeFailure() {}
    public static String describe(Throwable failure) {
        if (failure == null) return "UNKNOWN";
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        String description = "errorType=" + failure.getClass().getName();
        for (int n = 0; failure != null && n < 8 && visited.add(failure); n++, failure = failure.getCause()) {
            if (failure instanceof SchemaProblem schema) return "schemaCode=" + schema.code().name();
            if (failure instanceof SQLException sql) {
                String state = sql.getSQLState();
                description = "errorType=" + sql.getClass().getName() + " sqlState="
                        + (state != null && state.matches("[A-Za-z0-9]{1,8}") ? state : "unknown") + " vendorCode=" + sql.getErrorCode();
            }
        }
        return description;
    }
}
