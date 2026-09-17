package vn.ledat.itemupgrader.output.storage;

import java.io.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import vn.ledat.itemupgrader.transaction.storage.JournalCodec;
import vn.ledat.itemupgrader.transaction.storage.JournalSql;

/** No retained connections or raw thread pools. Caller runs JDBC off the owner thread.
 * READY + fresh identity registry is one SQL transaction. Duplicate/failed identities roll back the READY update.
 * Ambiguous COMMIT/connection acknowledgements are propagated, never converted to a successful creation receipt.
 */
public final class JdbcOutputRepository {
    private final OutputSql sql;
    public JdbcOutputRepository(OutputSql sql){this.sql=Objects.requireNonNull(sql);}
    public void initialize(Connection c,JournalSql.Dialect dialect)throws SQLException {
        unowned(c);
        var ddl=sql.ddl(dialect);execute(c,ddl.getFirst());Integer version=null;
        try(var s=prepare(c,sql.version())){s.setString(1,"outputs-v1");try(var r=s.executeQuery()){if(r.next())version=r.getInt(1);}}
        if(version!=null&&version!=1)throw new SQLException("unsupported output schema version");
        for(int i=1;i<ddl.size();i++)execute(c,ddl.get(i));
        if(version==null)try(var s=prepare(c,sql.insertVersion())){s.setString(1,"outputs-v1");s.setInt(2,1);one(s.executeUpdate());}
        // Single owner initializer required. MySQL DDL is NOT advertised as transactionally atomic.
    }
    public OutputStore.Claim claim(Connection c,OutputStore.Row pending)throws SQLException {
        unowned(c);if(pending.state()!=OutputStore.State.PREPARING)throw new IllegalArgumentException("expected pending output claim");
        var existing=find(c,pending.attemptId());if(existing.isPresent())return new OutputStore.Claim(false,existing.orElseThrow());
        try(var s=prepare(c,sql.insert())) {
            s.setString(1,pending.attemptId().toString());s.setString(2,pending.playerId().toString());s.setString(3,pending.planDigest());s.setLong(4,pending.createdAt().toEpochMilli());one(s.executeUpdate());
            return new OutputStore.Claim(true,pending);
        }catch(SQLException e) {
            if(unique(e)){var row=find(c,pending.attemptId());if(row.isPresent())return new OutputStore.Claim(false,row.orElseThrow());}
            throw e;
        }
    }
    public Optional<OutputStore.Row> find(Connection c,UUID attempt)throws SQLException {
        try(var s=prepare(c,sql.find())){
            s.setString(1,attempt.toString());try(var r=s.executeQuery()){
                if(!r.next())return Optional.empty();
                try{
                    var state=OutputStore.State.valueOf(r.getString("state"));Optional<vn.ledat.itemupgrader.output.PreparedOutput> payload=Optional.empty();
                    try(InputStream in=r.getBinaryStream("payload")){
                        if(in!=null){byte[] bytes=in.readNBytes(OutputCodec.MAX_BYTES+1);if(bytes.length>OutputCodec.MAX_BYTES)throw new SQLException("output payload exceeds bound");
                            if(!JournalCodec.digest(bytes).equals(r.getString("payload_digest")))throw new SQLException("output payload checksum mismatch");
                            payload=Optional.of(OutputCodec.decode(bytes));}
                        else if(r.getString("payload_digest")!=null)throw new SQLException("output digest without payload");
                    }
                    return Optional.of(new OutputStore.Row(UUID.fromString(r.getString("attempt_id")),UUID.fromString(r.getString("player_uuid")),r.getString("plan_digest"),state,payload,r.getString("reason"),Instant.ofEpochMilli(r.getLong("created_at"))));
                }catch(IOException|IllegalArgumentException error){throw new SQLException("invalid output row",error);}
            }
        }
    }
    public boolean finish(Connection c,OutputStore.Row expected,OutputStore.Row completed)throws SQLException {
        unowned(c);
        if(expected.state()!=OutputStore.State.PREPARING||completed.state()==OutputStore.State.PREPARING
                ||!expected.attemptId().equals(completed.attemptId())||!expected.playerId().equals(completed.playerId())
                ||!expected.planDigest().equals(completed.planDigest())||expected.createdAt().toEpochMilli()!=completed.createdAt().toEpochMilli())
            throw new IllegalArgumentException("invalid output transition");
        byte[] payload=completed.output().map(OutputCodec::encode).orElse(null);
        c.setAutoCommit(false);boolean changed;
        try {
            try(var s=prepare(c,sql.finish())) {
                s.setString(1,completed.state().name());
                if(payload==null){s.setNull(2,Types.BLOB);s.setNull(3,Types.VARCHAR);}else{s.setBytes(2,payload);s.setString(3,JournalCodec.digest(payload));}
                s.setString(4,completed.reason());s.setString(5,expected.attemptId().toString());s.setString(6,expected.playerId().toString());s.setString(7,expected.planDigest());s.setLong(8,expected.createdAt().toEpochMilli());
                int count=s.executeUpdate();if(count<0||count>1)throw new SQLException("output update affected unexpected row count");changed=count==1;
            }
            if(changed&&completed.output().isPresent()){
                var out=completed.output().orElseThrow();
                for(String token:new TreeSet<>(out.successIdentities()))identity(c,token,expected.attemptId(),"SUCCESS");
                for(String token:new TreeSet<>(out.failureIdentities()))identity(c,token,expected.attemptId(),"DOWNGRADE");
            }
            c.commit();
        }catch(SQLException|RuntimeException error){
            boolean rollback=false;try{c.rollback();rollback=true;}catch(SQLException e){error.addSuppressed(e);}
            if(rollback)try{c.setAutoCommit(true);}catch(SQLException e){error.addSuppressed(e);}
            throw error;
        }
        c.setAutoCommit(true);return changed;
    }
    private void identity(Connection c,String token,UUID attempt,String role)throws SQLException{
        try(var s=prepare(c,sql.identity())){s.setString(1,token);s.setString(2,attempt.toString());s.setString(3,role);one(s.executeUpdate());}
    }
    private static void unowned(Connection c)throws SQLException{if(!c.getAutoCommit())throw new SQLException("output repository requires auto-commit connection");}
    private static void one(int count)throws SQLException{if(count!=1)throw new SQLException("expected one output row");}
    private static PreparedStatement prepare(Connection c,String sql)throws SQLException{
        var s=c.prepareStatement(sql);try{s.setQueryTimeout(10);return s;}catch(SQLException error){try{s.close();}catch(SQLException e){error.addSuppressed(e);}throw error;}
    }
    private static void execute(Connection c,String sql)throws SQLException{try(var s=c.createStatement()){s.setQueryTimeout(10);s.executeUpdate(sql);}}
    private static boolean unique(SQLException e){return e.getErrorCode()==1062||e.getErrorCode()==19||e.getErrorCode()==1555||e.getErrorCode()==2067||"23505".equals(e.getSQLState());}
}
