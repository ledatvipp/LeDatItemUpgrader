package vn.ledat.itemupgrader.transaction.storage;
import java.sql.*;
import vn.ledat.itemupgrader.transaction.model.AttemptRecord;
/** Only SQL on the supplied connection; MUST NOT commit/rollback, schedule work, mutate items or call providers. */
public interface JournalCommitHook {
    void claimed(Connection connection,AttemptRecord record)throws SQLException;
    void transitioned(Connection connection,AttemptRecord previous,AttemptRecord next)throws SQLException;
    JournalCommitHook NONE=new JournalCommitHook() {
        public void claimed(Connection c,AttemptRecord r)throws SQLException {
            if(r.plan().terms().pity().isPresent())throw new SQLException("Pity plan requires an atomic progression hook");
        }
        public void transitioned(Connection c,AttemptRecord old,AttemptRecord next)throws SQLException {
            if(next.plan().terms().pity().isPresent())throw new SQLException("Pity transition requires an atomic progression hook");
        }
    };
}
