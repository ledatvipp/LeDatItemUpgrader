package vn.ledat.itemupgrader.history.storage;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import vn.ledat.itemupgrader.history.*;
import vn.ledat.itemupgrader.pity.*;
import vn.ledat.itemupgrader.item.ItemKey;
import vn.ledat.itemupgrader.chance.Probability;
import vn.ledat.itemupgrader.transaction.model.AttemptRecord;
import vn.ledat.itemupgrader.transaction.storage.*;
/** SQL-only journal hook. Projection + statistics + pity + completion receipt commit BEFORE terminal unlock,
 * on the same Connection and in the same transaction as the authoritative journal CAS. No provider calls here.
 * The writer requires journal's durable per-player lock. Single initializer; no inferred transaction ownership. */
public final class JdbcProgressRepository implements JournalCommitHook {
    private static final String COMPONENT="progress-v1";
    private final ProgressSql sql;
    public JdbcProgressRepository(ProgressSql sql){this.sql=Objects.requireNonNull(sql);}
    public void initialize(Connection c,JournalSql.Dialect dialect)throws SQLException {
        if(!c.getAutoCommit())throw new SQLException("progress migration needs unowned auto-commit connection");
        var ddl=sql.ddl(dialect);execute(c,ddl.getFirst());
        Integer version=null;
        try(var s=prepare(c,sql.version())) {s.setString(1,COMPONENT);try(var r=s.executeQuery()){if(r.next())version=r.getInt(1);}}
        if(version!=null&&version!=1)throw new SQLException("unsupported progress schema version");
        for(int i=1;i<ddl.size();i++)execute(c,ddl.get(i));
        Set<String> indexes=new HashSet<>();
        try(var r=c.getMetaData().getIndexInfo(c.getCatalog(),null,sql.tables().history(),false,false)) {
            while(r.next()){String name=r.getString("INDEX_NAME");if(name!=null)indexes.add(name.toLowerCase(Locale.ROOT));}
        }
        for(var e:sql.indexes().entrySet())if(!indexes.contains(e.getKey().toLowerCase(Locale.ROOT)))execute(c,e.getValue());
        if(version==null)try(var s=prepare(c,sql.insertVersion())) {s.setString(1,COMPONENT);s.setInt(2,1);one(s.executeUpdate());}
    }
    @Override public void claimed(Connection c,AttemptRecord record)throws SQLException {
        owned(c);if(record.version()!=0)throw new SQLException("claim hook needs initial attempt");
        if(ensureStatistics(c,record.plan().playerId()).completed()>=PitySnapshot.MAX_COUNTER)throw new SQLException("statistics counter capacity reached");
        if(record.plan().terms().pity().isPresent()) {
            var stamp=record.plan().terms().pity().orElseThrow();
            var found=readPity(c,record.plan().playerId(),stamp.scope());
            var actual=found.orElseGet(()->PitySnapshot.empty(record.plan().playerId(),stamp.scope()));
            if(actual.version()>=PitySnapshot.MAX_COUNTER)throw new SQLException("pity counter capacity reached");
            if(!stamp.matches(actual))throw new SQLException("stale pity snapshot: confirm a fresh quote");
            if(found.isEmpty())try(var s=prepare(c,sql.insertPity())) {s.setString(1,actual.playerId().toString());s.setString(2,actual.scope());one(s.executeUpdate());}
        }
        insertHistory(c,record);
    }
    @Override public void transitioned(Connection c,AttemptRecord previous,AttemptRecord next)throws SQLException {
        owned(c);JournalCodec.checkTransition(previous,next);
        var old=identity(c,next.id());
        if(old.isEmpty())insertHistory(c,next); // Legacy journal gains a projection at its next verified transition.
        else {
            var identity=old.orElseThrow();
            if(identity.version()!=previous.version()||!identity.plan().equals(JournalCodec.planDigest(previous.plan()))
                    ||!identity.state().equals(JournalCodec.digest(JournalCodec.encodeState(previous))))throw new SQLException("history/journal predecessor mismatch");
            var h=HistoryEntry.from(next);
            try(var s=prepare(c,sql.updateHistory())) {
                s.setLong(1,h.version());s.setString(2,h.state().name());s.setString(3,h.outcome().name());s.setLong(4,h.updatedAt().toEpochMilli());
                s.setInt(5,h.terminal()?1:0);s.setString(6,JournalCodec.digest(JournalCodec.encodeState(next)));s.setString(7,h.transactionId().toString());
                s.setLong(8,identity.version());s.setString(9,identity.plan());s.setString(10,identity.state());one(s.executeUpdate());
            }
        }
        if(next.state()==AttemptRecord.State.COMPLETED)complete(c,next);
    }
    private void complete(Connection c,AttemptRecord record)throws SQLException {
        String plan=JournalCodec.planDigest(record.plan()),state=JournalCodec.digest(JournalCodec.encodeState(record));
        try(var s=prepare(c,sql.receipt())) {
            s.setString(1,record.id().toString());try(var r=s.executeQuery()) {
                if(r.next()) {
                    if(!r.getString(1).equals(record.plan().playerId().toString())||!r.getString(2).equals(plan)||!r.getString(3).equals(state))
                        throw new SQLException("completion receipt conflict");
                    return;
                }
            }
        }
        var old=ensureStatistics(c,record.plan().playerId());var next=old.complete(record.successfulRoll());
        try(var s=prepare(c,sql.updateStats())) {
            s.setLong(1,next.completed());s.setLong(2,next.wins());s.setLong(3,next.losses());s.setString(4,next.playerId().toString());
            s.setLong(5,old.completed());s.setLong(6,old.wins());s.setLong(7,old.losses());one(s.executeUpdate());
        }
        if(record.plan().terms().pity().isPresent()) {
            var stamp=record.plan().terms().pity().orElseThrow();
            var before=readPity(c,record.plan().playerId(),stamp.scope()).orElseThrow(()->new SQLException("missing reserved pity row"));
            if(!stamp.matches(before))throw new SQLException("pity changed during active attempt");
            var after=before.complete(record.successfulRoll());
            try(var s=prepare(c,sql.updatePity())) {
                s.setLong(1,after.version());s.setLong(2,after.failures());s.setString(3,after.playerId().toString());s.setString(4,after.scope());
                s.setLong(5,before.version());s.setLong(6,before.failures());one(s.executeUpdate());
            }
        }
        try(var s=prepare(c,sql.insertReceipt())) {
            s.setString(1,record.id().toString());s.setString(2,record.plan().playerId().toString());s.setString(3,plan);s.setString(4,state);
            s.setLong(5,record.updatedAt().toEpochMilli());one(s.executeUpdate());
        }
    }
    public PlayerStatistics statistics(Connection c,UUID player)throws SQLException {return readStatistics(c,player).orElseGet(()->PlayerStatistics.empty(player));}
    public PitySnapshot pity(Connection c,UUID player,String scope)throws SQLException {return readPity(c,player,scope).orElseGet(()->PitySnapshot.empty(player,scope));}
    private Optional<PlayerStatistics> readStatistics(Connection c,UUID player)throws SQLException {
        try(var s=prepare(c,sql.stats())) {s.setString(1,player.toString());try(var r=s.executeQuery()) {
            return r.next()?Optional.of(new PlayerStatistics(player,r.getLong(1),r.getLong(2),r.getLong(3))):Optional.empty();
        }}catch(IllegalArgumentException bad){throw new SQLException("invalid statistics row",bad);}
    }
    private PlayerStatistics ensureStatistics(Connection c,UUID player)throws SQLException {
        var row=readStatistics(c,player);if(row.isPresent())return row.orElseThrow();
        try(var s=prepare(c,sql.insertStats())) {s.setString(1,player.toString());one(s.executeUpdate());}
        return PlayerStatistics.empty(player);
    }
    private Optional<PitySnapshot> readPity(Connection c,UUID player,String scope)throws SQLException {
        PitySnapshot.validateScope(scope);
        try(var s=prepare(c,sql.pity())) {s.setString(1,player.toString());s.setString(2,scope);try(var r=s.executeQuery()) {
            return r.next()?Optional.of(new PitySnapshot(player,scope,r.getLong(1),r.getLong(2))):Optional.empty();
        }}catch(IllegalArgumentException bad){throw new SQLException("invalid pity row",bad);}
    }
    public HistoryPage page(Connection c,HistoryQuery query)throws SQLException {
        List<HistoryEntry> rows=new ArrayList<>();
        try(var s=prepare(c,sql.page(query.filter()))) {
            s.setString(1,query.playerId().toString());s.setLong(2,query.upperCreatedMillis());
            long before=query.after().map(HistoryQuery.Cursor::createdMillis).orElse(query.upperCreatedMillis());
            s.setLong(3,before);s.setLong(4,before);s.setString(5,query.after().map(v->v.transactionId().toString()).orElse("~"));
            s.setInt(6,query.limit()+1);try(var r=s.executeQuery()){while(r.next())rows.add(readHistory(r));}
        }
        boolean more=rows.size()>query.limit();if(more)rows.removeLast();
        try{return new HistoryPage(query,rows,more);}catch(IllegalArgumentException bad){throw new SQLException("invalid history page",bad);}
    }
    /** Explicit bounded maintenance. Only terminal UI history is pruned; tombstones/stats/pity/journal stay intact. */
    public int pruneHistory(Connection c,Instant terminalBefore,int maximum)throws SQLException {
        if(maximum<1||maximum>1000||terminalBefore.toEpochMilli()<0)throw new IllegalArgumentException("retention bounds");
        return transaction(c,()->{
            List<String> ids=new ArrayList<>();
            try(var s=prepare(c,sql.retentionCandidates())) {s.setLong(1,terminalBefore.toEpochMilli());s.setInt(2,maximum);
                try(var r=s.executeQuery()){while(r.next())ids.add(r.getString(1));}}
            int deleted=0;
            for(String id:ids)try(var s=prepare(c,sql.pruneOne())){s.setString(1,id);s.setLong(2,terminalBefore.toEpochMilli());deleted+=s.executeUpdate();}
            return deleted;
        });
    }
    /** Safe migration seam: adds history only. It deliberately does NOT backfill ordered pity or lifetime statistics. */
    public boolean backfillHistoryOnly(Connection c,AttemptRecord record)throws SQLException {
        return transaction(c,()->{
            var found=identity(c,record.id());
            if(found.isPresent()) {
                var prior=found.orElseThrow();
                if(!prior.plan().equals(JournalCodec.planDigest(record.plan())) || prior.version()==record.version()
                        && !prior.state().equals(JournalCodec.digest(JournalCodec.encodeState(record))))
                    throw new SQLException("backfill projection conflict");
                return false; // Never overwrite a live projection from an out-of-order import.
            }
            insertHistory(c,record);return true;
        });
    }
    private record Identity(long version,String plan,String state){}
    private Optional<Identity> identity(Connection c,UUID id)throws SQLException {
        try(var s=prepare(c,sql.historyIdentity())){s.setString(1,id.toString());try(var r=s.executeQuery()){
            return r.next()?Optional.of(new Identity(r.getLong(1),r.getString(2),r.getString(3))):Optional.empty();}}
    }
    private void insertHistory(Connection c,AttemptRecord record)throws SQLException {
        var h=HistoryEntry.from(record);
        try(var s=prepare(c,sql.insertHistory())) {
            s.setString(1,h.transactionId().toString());s.setString(2,h.playerId().toString());s.setLong(3,h.version());s.setString(4,h.state().name());s.setString(5,h.outcome().name());
            s.setString(6,h.sourceKey().value());s.setInt(7,h.sourceAmount());s.setString(8,h.targetId());s.setString(9,h.targetKey().value());s.setInt(10,h.targetAmount());
            s.setString(11,h.sourceValue().toPlainString());s.setString(12,h.targetValue().toPlainString());s.setLong(13,h.probability().winningTickets());s.setString(14,h.profile());s.setString(15,h.failure());
            s.setLong(16,h.createdAt().toEpochMilli());s.setLong(17,h.updatedAt().toEpochMilli());s.setInt(18,h.terminal()?1:0);
            s.setString(19,JournalCodec.planDigest(record.plan()));s.setString(20,JournalCodec.digest(JournalCodec.encodeState(record)));one(s.executeUpdate());
        }
    }
    private static HistoryEntry readHistory(ResultSet r)throws SQLException {
        try{return new HistoryEntry(UUID.fromString(r.getString("tx_id")),UUID.fromString(r.getString("player_uuid")),r.getLong("version"),
            AttemptRecord.State.valueOf(r.getString("state")),HistoryEntry.Outcome.valueOf(r.getString("outcome")),ItemKey.of(r.getString("source_key")),r.getInt("source_amount"),
            r.getString("target_id"),ItemKey.of(r.getString("target_key")),r.getInt("target_amount"),decimal(r.getString("source_value")),decimal(r.getString("target_value")),
            new Probability(r.getLong("tickets")),r.getString("profile"),r.getString("failure"),Instant.ofEpochMilli(r.getLong("created_at")),Instant.ofEpochMilli(r.getLong("updated_at")));}
        catch(IllegalArgumentException bad){throw new SQLException("invalid history projection row",bad);}
    }
    private static java.math.BigDecimal decimal(String text)throws SQLException {
        if(text==null||text.length()>64||!text.matches("[0-9]+(?:\\.[0-9]+)?"))throw new SQLException("invalid history decimal");return new java.math.BigDecimal(text);
    }
    private static void owned(Connection c)throws SQLException{if(c.getAutoCommit())throw new SQLException("progress hook needs journal-owned transaction");}
    private static void one(int n)throws SQLException{if(n!=1)throw new SQLException("progress CAS affected unexpected row count");}
    private static PreparedStatement prepare(Connection c,String text)throws SQLException{
        var s=c.prepareStatement(text);try{s.setQueryTimeout(10);return s;}catch(SQLException e){try{s.close();}catch(SQLException close){e.addSuppressed(close);}throw e;}}
    private static void execute(Connection c,String text)throws SQLException{try(var s=c.createStatement()){s.setQueryTimeout(10);s.executeUpdate(text);}}
    @FunctionalInterface private interface Work<T>{T run()throws SQLException;}
    private static <T>T transaction(Connection c,Work<T> work)throws SQLException {
        if(!c.getAutoCommit())throw new SQLException("maintenance needs unowned auto-commit connection");c.setAutoCommit(false);
        T result;
        try{result=work.run();c.commit();}
        catch(SQLException|RuntimeException e){boolean ok=false;try{c.rollback();ok=true;}catch(SQLException rb){e.addSuppressed(rb);}
            if(ok)try{c.setAutoCommit(true);}catch(SQLException restore){e.addSuppressed(restore);}throw e;}
        c.setAutoCommit(true);return result;
    }
}
