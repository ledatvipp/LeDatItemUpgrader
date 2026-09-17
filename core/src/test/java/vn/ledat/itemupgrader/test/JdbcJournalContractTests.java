package vn.ledat.itemupgrader.test;

import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.function.*;
import vn.ledat.itemupgrader.transaction.*;
import vn.ledat.itemupgrader.transaction.model.*;
import vn.ledat.itemupgrader.transaction.storage.*;
import static vn.ledat.itemupgrader.test.TransactionFixtures.*;

/** Explicit MOCK JDBC call-contract tests. They do not claim driver/dialect/server database integration. */
final class JdbcJournalContractTests {
    private JdbcJournalContractTests(){}
    static void register(BiConsumer<String,Phase04SelfTest.Checked> tests,Consumer<Boolean> check){
        tests.accept("jdbc-mock.claim-binds-and-commits-all-three-writes",()->{
            var db=new Db();var repo=repository();var claim=repo.claim(db.connection,plan(),NOW);
            check.accept(claim.status()==TransactionJournal.ClaimStatus.CREATED);check.accept(db.commits==1);check.accept(db.rollbacks==0);
            check.accept(db.executed.stream().filter(s->s.startsWith("INSERT")).count()==3);check.accept(db.auto);check.accept(db.closed==0);
            var insert=db.prepared.stream().filter(p->p.sql.contains("INSERT INTO iup_attempts")).findFirst().orElseThrow();
            check.accept(insert.args.size()==12);check.accept(insert.args.get(1).equals(plan().attemptId().toString()));
            var saved=JournalCodec.decodeState(JournalCodec.decodePlan((byte[])insert.args.get(5)),(byte[])insert.args.get(11));check.accept(JournalCodec.planDigest(saved.plan()).equals(JournalCodec.planDigest(plan())));
            check.accept(db.prepared.stream().allMatch(p->p.closed&&p.timeout==10));
        });
        tests.accept("jdbc-mock.owner-lock-insert-failure-rolls-back",()->{
            var db=new Db();db.failSql="INSERT INTO iup_player_locks";expectSql(()->repository().claim(db.connection,plan(),NOW),check);
            check.accept(db.rollbacks==1);check.accept(db.commits==0);check.accept(db.auto);check.accept(db.closed==0);
        });
        tests.accept("jdbc-mock.failed-rollback-never-restores-autocommit",()->{
            var db=new Db();db.failSql="INSERT INTO iup_player_locks";db.failRollback=true;
            expectSql(()->repository().claim(db.connection,plan(),NOW),check);check.accept(!db.auto);check.accept(db.setTrue==0);check.accept(db.rollbacks==1);
        });
        tests.accept("jdbc-mock.commit-exception-is-not-success",()->{
            var db=new Db();db.failCommit=true;expectSql(()->repository().claim(db.connection,plan(),NOW),check);check.accept(db.rollbacks==1);
        });
        tests.accept("jdbc-mock.autocommit-restore-failure-surfaces-uncertainty",()->{
            var db=new Db();db.failRestore=true;expectSql(()->repository().claim(db.connection,plan(),NOW),check);
            check.accept(db.commits==1);check.accept(db.rollbacks==0);check.accept(db.closed==0);
        });
        tests.accept("jdbc-mock.rejects-nested-provider-transaction",()->{
            var db=new Db();db.auto=false;expectSql(()->repository().claim(db.connection,plan(),NOW),check);check.accept(db.executed.isEmpty());check.accept(db.commits==0);
        });
        tests.accept("jdbc-mock.cas-checks-payload-digest-and-owner",()->{
            var db=new Db();var old=AttemptRecord.initial(plan(),NOW);var next=new TransactionMachine().next(old,NOW).next();
            check.accept(repository().compareAndSet(db.connection,old,next));
            var update=db.prepared.stream().filter(p->p.sql.startsWith("UPDATE")).findFirst().orElseThrow();check.accept(update.args.size()==10);
            check.accept(update.args.get(9).equals(JournalCodec.digest(JournalCodec.encodeState(old))));check.accept(db.commits==1);
        });
        tests.accept("jdbc-mock.stale-cas-does-not-write-event",()->{
            var db=new Db();db.updateCount=0;var old=AttemptRecord.initial(plan(),NOW);var next=new TransactionMachine().next(old,NOW).next();
            check.accept(!repository().compareAndSet(db.connection,old,next));check.accept(db.executed.stream().noneMatch(s->s.contains("INSERT INTO iup_tx_events")));
        });
        tests.accept("jdbc-mock.cas-owner-mismatch-rolls-back",()->{
            var db=new Db();db.activeId=UUID.randomUUID().toString();var old=AttemptRecord.initial(plan(),NOW);
            expectSql(()->repository().compareAndSet(db.connection,old,new TransactionMachine().next(old,NOW).next()),check);check.accept(db.rollbacks==1);check.accept(db.commits==0);
        });
        tests.accept("jdbc-mock.event-failure-does-not-commit-state",()->{
            var db=new Db();db.failSql="INSERT INTO iup_tx_events";var old=AttemptRecord.initial(plan(),NOW);
            expectSql(()->repository().compareAndSet(db.connection,old,new TransactionMachine().next(old,NOW).next()),check);check.accept(db.rollbacks==1);check.accept(db.commits==0);
        });
        tests.accept("jdbc-mock.reject-future-schema-before-other-ddl",()->{
            var db=new Db();db.schema=9;expectSql(()->repository().initialize(db.connection,JournalSql.Dialect.SQLITE,NOW),check);
            check.accept(db.executed.size()==1);check.accept(db.executed.getFirst().contains("iup_tx_schema"));
        });
        tests.accept("jdbc-mock.schema-initializer-is-not-shown-as-acid-ddl",()->{
            var db=new Db();check.accept(repository().initialize(db.connection,JournalSql.Dialect.MYSQL,NOW)==1);
            check.accept(db.executed.stream().filter(s->s.startsWith("CREATE TABLE")).allMatch(s->s.endsWith("ENGINE=InnoDB")));
            check.accept(db.commits==0);check.accept(db.closed==0);
        });
        tests.accept("jdbc-mock.sql-identifier-validation",()->{
            for(String name:List.of("x;DROP_TABLE","x y","`table`","1bad")){
                try{new JournalSql.Tables(name,"locks","events","metadata");throw new AssertionError("accepted unsafe identifier");}catch(IllegalArgumentException expected){check.accept(true);}
            }
        });
    }
    @FunctionalInterface interface SqlTask{void run()throws Exception;}
    private static void expectSql(SqlTask task,Consumer<Boolean> check)throws Exception{
        try{task.run();throw new AssertionError("expected SQLException");}catch(SQLException expected){check.accept(true);}
    }
    private static JdbcTransactionRepository repository(){return new JdbcTransactionRepository(new JournalSql(JournalSql.Tables.prefixed("iup_")));}
    private static final class Prepared {
        final String sql;final Map<Integer,Object> args=new HashMap<>();boolean closed;int timeout;
        Prepared(String sql){this.sql=sql;}
    }
    private static final class Db {
        boolean auto=true,failRollback,failCommit,failRestore;int commits,rollbacks,closed,setTrue,updateCount=1;Integer schema;
        String failSql="",activeId=plan().attemptId().toString();final List<String> executed=new ArrayList<>();final List<Prepared> prepared=new ArrayList<>();
        final Connection connection=proxy(Connection.class,(p,m,a)->switch(m.getName()){
            case "getAutoCommit"->auto;
            case "setAutoCommit"->{boolean value=(Boolean)a[0];if(value){setTrue++;if(failRestore)throw new SQLException("restore failure");}auto=value;yield null;}
            case "commit"->{commits++;if(failCommit)throw new SQLException("commit ack failure");yield null;}
            case "rollback"->{rollbacks++;if(failRollback)throw new SQLException("rollback failure");yield null;}
            case "close"->{closed++;yield null;}
            case "prepareStatement"->statement((String)a[0]);
            case "createStatement"->proxy(Statement.class,(q,n,b)->switch(n.getName()){
                case "setQueryTimeout","close"->null;case "executeUpdate"->{executed.add((String)b[0]);yield 1;}default->unsupported(n);
            });
            case "getCatalog"->null;
            case "getMetaData"->proxy(DatabaseMetaData.class,(q,n,b)->switch(n.getName()){
                case "getIndexInfo"->rows("empty");default->unsupported(n);
            });
            default->unsupported(m);
        });
        PreparedStatement statement(String sql){
            Prepared state=new Prepared(sql);prepared.add(state);
            return proxy(PreparedStatement.class,(p,m,a)->{
                String name=m.getName();
                if(name.equals("setQueryTimeout")){state.timeout=(Integer)a[0];return null;}
                if(name.startsWith("set")){state.args.put((Integer)a[0],name.equals("setNull")?null:a[1]);return null;}
                return switch(name){
                    case "close"->{state.closed=true;yield null;}
                    case "executeUpdate"->{executed.add(sql);if(!failSql.isEmpty()&&sql.contains(failSql))throw new SQLException("injected SQL failure");yield sql.startsWith("UPDATE")?updateCount:1;}
                    case "executeQuery"->rows(sql);
                    default->unsupported(m);
                };
            });
        }
        ResultSet rows(String sql){
            boolean has=sql.startsWith("SELECT tx_id")||sql.startsWith("SELECT version")&&schema!=null;boolean[] read={false};
            return proxy(ResultSet.class,(p,m,a)->switch(m.getName()){
                case "next"->{boolean result=has&&!read[0];read[0]=true;yield result;}
                case "getString"->activeId;case "getInt"->schema;case "close"->null;default->unsupported(m);
            });
        }
    }
    private static Object unsupported(Method method){throw new UnsupportedOperationException("unmocked JDBC method "+method.getName());}
    private static <T>T proxy(Class<T> type,InvocationHandler handler){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler));}
}
