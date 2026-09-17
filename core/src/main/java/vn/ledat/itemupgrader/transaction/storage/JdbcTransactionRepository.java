package vn.ledat.itemupgrader.transaction.storage;

import java.io.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import vn.ledat.itemupgrader.transaction.model.*;

/** Stateless JDBC repository. Caller owns async connection lifecycle; connection is never stored or closed here.
 * Supported SQL dialects: SQLite and MySQL/InnoDB. Actual JDBC/provider integration remains a staging gate.
 * No callbacks to native effects are permitted inside a SQL transaction. */
public final class JdbcTransactionRepository {
    private static final String COMPONENT="transactions-v1";
    private static final int SCHEMA_VERSION=1;
    private final JournalSql sql;
    private final JournalCommitHook hook;
    public JdbcTransactionRepository(JournalSql sql){this(sql,JournalCommitHook.NONE);}
    public JdbcTransactionRepository(JournalSql sql,JournalCommitHook hook){this.sql=Objects.requireNonNull(sql);this.hook=Objects.requireNonNull(hook);}
    public int initialize(Connection connection,JournalSql.Dialect dialect,Instant now)throws SQLException {
        requireUnowned(connection);
        // DDL is idempotent, NOT transactionally atomic on MySQL. Never nest inside a provider transaction.
        List<String> ddl=sql.ddl(dialect);
        execute(connection,ddl.getFirst());
        Integer existing=version(connection);
        if(existing!=null && existing!=SCHEMA_VERSION)throw new SQLException("Unsupported transaction schema version: "+existing);
        for(int i=1;i<ddl.size();i++)execute(connection,ddl.get(i));
        Set<String> indexes=new HashSet<>();
        try(ResultSet rows=connection.getMetaData().getIndexInfo(connection.getCatalog(),null,sql.tables().attempts(),false,false)) {
            while(rows.next()) {String name=rows.getString("INDEX_NAME");if(name!=null)indexes.add(name.toLowerCase(Locale.ROOT));}
        }
        for(var index:sql.indexStatements().entrySet())if(!indexes.contains(index.getKey().toLowerCase(Locale.ROOT)))execute(connection,index.getValue());
        if(existing==null)try(PreparedStatement s=prepare(connection,sql.insertVersion())) {
            s.setString(1,COMPONENT);s.setInt(2,SCHEMA_VERSION);s.setLong(3,now.toEpochMilli());s.executeUpdate();
        }
        // Single initializer required; do not race migrations between live server processes.
        return SCHEMA_VERSION;
    }
    public TransactionJournal.Claim claim(Connection c,AttemptPlan plan,Instant now)throws SQLException {
        requireUnowned(c);
        Optional<AttemptRecord> prior=find(c,plan.attemptId());
        if(prior.isPresent())return duplicate(plan,prior.orElseThrow());
        if(!now.isBefore(plan.expiresAt()))return new TransactionJournal.Claim(TransactionJournal.ClaimStatus.EXPIRED,Optional.empty(),Optional.empty());
        AttemptRecord record=AttemptRecord.initial(plan,now);byte[] bytes=JournalCodec.encodeState(record);
        try {
            return transaction(c,()->{
                try(PreparedStatement s=prepare(c,sql.insertAttempt())) {
                    s.setString(1,record.id().toString());s.setString(2,plan.idempotencyKey());s.setString(3,plan.playerId().toString());
                    s.setString(4,JournalCodec.planDigest(plan));s.setBytes(5,JournalCodec.encodePlan(plan));s.setString(6,record.state().name());s.setLong(7,0);
                    s.setLong(8,now.toEpochMilli());s.setLong(9,now.toEpochMilli());s.setNull(10,Types.BIGINT);s.setBytes(11,bytes);
                    s.setString(12,JournalCodec.digest(bytes));requireOne(s.executeUpdate());
                }
                try(PreparedStatement s=prepare(c,sql.insertLock())) {
                    s.setString(1,plan.playerId().toString());s.setString(2,record.id().toString());requireOne(s.executeUpdate());
                }
                hook.claimed(c,record);
                event(c,record,bytes);
                return new TransactionJournal.Claim(TransactionJournal.ClaimStatus.CREATED,Optional.of(record),Optional.empty());
            });
        }catch(SQLException collision) {
            if(!uniqueViolation(collision) || !c.getAutoCommit())throw collision;
            Optional<AttemptRecord> existing=find(c,record.id());
            if(existing.isEmpty())existing=single(c,sql.findByKey(),plan.idempotencyKey());
            if(existing.isPresent()) {
                var old=existing.orElseThrow();
                boolean same=JournalCodec.planDigest(old.plan()).equals(JournalCodec.planDigest(plan));
                return new TransactionJournal.Claim(same?TransactionJournal.ClaimStatus.DUPLICATE:TransactionJournal.ClaimStatus.IDEMPOTENCY_CONFLICT,existing,Optional.empty());
            }
            Optional<UUID> owner=activeAttempt(c,plan.playerId());
            if(owner.isPresent())return new TransactionJournal.Claim(TransactionJournal.ClaimStatus.PLAYER_BUSY,Optional.empty(),owner);
            throw collision; // No evidence of a duplicate; do not hide an unrelated SQL error.
        }
    }
    private static TransactionJournal.Claim duplicate(AttemptPlan plan,AttemptRecord old) {
        boolean same=JournalCodec.planDigest(old.plan()).equals(JournalCodec.planDigest(plan));
        return new TransactionJournal.Claim(same?TransactionJournal.ClaimStatus.DUPLICATE:TransactionJournal.ClaimStatus.IDEMPOTENCY_CONFLICT,Optional.of(old),Optional.empty());
    }
    public Optional<AttemptRecord> find(Connection c,UUID id)throws SQLException{return single(c,sql.find(),id.toString());}
    public Optional<UUID> activeAttempt(Connection c,UUID player)throws SQLException {
        try(PreparedStatement s=prepare(c,sql.active())) {
            s.setString(1,player.toString());
            try(ResultSet r=s.executeQuery()){return r.next()?Optional.of(UUID.fromString(r.getString(1))):Optional.empty();}
        }
    }
    public List<AttemptRecord> unfinished(Connection c,Optional<UUID> after,int limit)throws SQLException {
        if(limit<1 || limit>8)throw new IllegalArgumentException("recovery page limit 1..8");
        List<AttemptRecord> result=new ArrayList<>();
        try(PreparedStatement s=prepare(c,sql.unfinished())) {
            s.setString(1,after.map(UUID::toString).orElse(""));s.setInt(2,limit);
            try(ResultSet rows=s.executeQuery()){while(rows.next())result.add(read(rows));}
        }
        return List.copyOf(result);
    }
    public boolean compareAndSet(Connection c,AttemptRecord expected,AttemptRecord next)throws SQLException {
        JournalCodec.checkTransition(expected,next);byte[] previous=JournalCodec.encodeState(expected),bytes=JournalCodec.encodeState(next);
        return transaction(c,()->{
            try(PreparedStatement s=prepare(c,sql.update())) {
                s.setString(1,next.state().name());s.setLong(2,next.version());s.setLong(3,next.updatedAt().toEpochMilli());
                if(next.sample()==null)s.setNull(4,Types.BIGINT);else s.setLong(4,next.sample());
                s.setBytes(5,bytes);s.setString(6,JournalCodec.digest(bytes));s.setString(7,next.id().toString());
                s.setLong(8,expected.version());s.setString(9,JournalCodec.digest(previous));s.setString(10,JournalCodec.planDigest(expected.plan()));
                int changed=s.executeUpdate();if(changed==0)return false;requireOne(changed);
            }
            // Ensure ownership survived. This query is in the same transaction as the successful row CAS.
            if(!activeAttempt(c,next.plan().playerId()).filter(next.id()::equals).isPresent())throw new SQLException("Missing/mismatched durable player ownership");
            hook.transitioned(c,expected,next);
            event(c,next,bytes);
            if(next.terminal())try(PreparedStatement s=prepare(c,sql.unlock())) {
                s.setString(1,next.plan().playerId().toString());s.setString(2,next.id().toString());requireOne(s.executeUpdate());
            }
            return true;
        });
    }
    private Optional<AttemptRecord> single(Connection c,String query,String value)throws SQLException {
        try(PreparedStatement s=prepare(c,query)) {
            s.setString(1,value);try(ResultSet rows=s.executeQuery()){return rows.next()?Optional.of(read(rows)):Optional.empty();}
        }
    }
    private AttemptRecord read(ResultSet row)throws SQLException {
        byte[] planBytes=readPayload(row,"plan_payload",JournalCodec.MAX_BYTES);
        byte[] payload=readPayload(row,"payload",JournalCodec.MAX_STATE_BYTES);
        if(!JournalCodec.digest(planBytes).equals(row.getString("plan_digest")))throw new SQLException("Plan checksum mismatch");
        if(!JournalCodec.digest(payload).equals(row.getString("payload_digest")))throw new SQLException("Transaction checksum mismatch");
        final AttemptRecord record;
        try{record=JournalCodec.decodeState(JournalCodec.decodePlan(planBytes),payload);}catch(IllegalArgumentException e){throw new SQLException("Invalid journal payload",e);}
        long raw=row.getLong("sample");Long sample=row.wasNull()?null:raw;
        if(!record.id().toString().equals(row.getString("tx_id"))
                ||!record.plan().idempotencyKey().equals(row.getString("idempotency_key"))
                ||!record.plan().playerId().toString().equals(row.getString("player_uuid"))
                ||!JournalCodec.planDigest(record.plan()).equals(row.getString("plan_digest"))
                ||!record.state().name().equals(row.getString("state"))||record.version()!=row.getLong("version")
                ||record.createdAt().toEpochMilli()!=row.getLong("created_at")||record.updatedAt().toEpochMilli()!=row.getLong("updated_at")
                ||!Objects.equals(sample,record.sample()))throw new SQLException("Transaction indexed fields disagree with payload");
        return record;
    }
    private static byte[] readPayload(ResultSet row,String column,int maximum)throws SQLException {
        try(InputStream in=row.getBinaryStream(column)) {
            if(in==null)throw new SQLException("Missing transaction payload");
            byte[] bytes=in.readNBytes(maximum+1);if(bytes.length>maximum)throw new SQLException("Transaction payload exceeds hard bound");return bytes;
        }catch(IOException e){throw new SQLException("Cannot read transaction payload",e);}
    }
    private void event(Connection c,AttemptRecord record,byte[] bytes)throws SQLException {
        try(PreparedStatement s=prepare(c,sql.insertEvent())) {
            s.setString(1,record.id().toString());s.setLong(2,record.version());s.setString(3,record.state().name());
            s.setLong(4,record.updatedAt().toEpochMilli());s.setString(5,JournalCodec.digest(bytes));requireOne(s.executeUpdate());
        }
    }
    private Integer version(Connection c)throws SQLException {
        try(PreparedStatement s=prepare(c,sql.schemaVersion())){s.setString(1,COMPONENT);try(ResultSet rows=s.executeQuery()){return rows.next()?rows.getInt(1):null;}}
    }
    private static PreparedStatement prepare(Connection c,String sql)throws SQLException {
        PreparedStatement s=c.prepareStatement(sql);
        try{s.setQueryTimeout(10);return s;}catch(SQLException e){try{s.close();}catch(SQLException close){e.addSuppressed(close);}throw e;}
    }
    private static void execute(Connection c,String sql)throws SQLException{try(Statement s=c.createStatement()){s.setQueryTimeout(10);s.executeUpdate(sql);}}
    private static void requireOne(int count)throws SQLException{if(count!=1)throw new SQLException("Expected exactly one affected journal row");}
    private static void requireUnowned(Connection c)throws SQLException{if(!c.getAutoCommit())throw new SQLException("Journal requires an unowned auto-commit connection");}
    @FunctionalInterface private interface Work<T>{T run()throws SQLException;}
    private static <T>T transaction(Connection c,Work<T> work)throws SQLException {
        requireUnowned(c);c.setAutoCommit(false);T result;
        try{result=work.run();c.commit();}
        catch(SQLException|RuntimeException error){
            boolean rolledBack=false;
            try{c.rollback();rolledBack=true;}catch(SQLException rollback){error.addSuppressed(rollback);}
            if(rolledBack)try{c.setAutoCommit(true);}catch(SQLException restore){error.addSuppressed(restore);}
            // A failed rollback never causes setAutoCommit(true), which could commit pending work.
            throw error;
        }
        // A restore failure after COMMIT is an uncertain acknowledgement to the caller; do not execute more effects.
        c.setAutoCommit(true);return result;
    }
    private static boolean uniqueViolation(SQLException e){
        return e.getErrorCode()==1062 || e.getErrorCode()==19 || e.getErrorCode()==1555 || e.getErrorCode()==2067
                || "23505".equals(e.getSQLState());
    }
}
