package vn.ledat.itemupgrader.test;

import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.io.ByteArrayInputStream;
import vn.ledat.itemupgrader.output.*;
import vn.ledat.itemupgrader.output.storage.*;
import vn.ledat.itemupgrader.failure.*;
import vn.ledat.itemupgrader.transaction.storage.*;
import vn.ledat.itemupgrader.demo.OutputDemo;
import vn.ledat.itemupgrader.demo.support.SyntheticItems;
import static vn.ledat.itemupgrader.test.Phase05SelfTest.*;

/** JDBC method-contract MOCKS only. Real SQLite SQL is tested separately through Python's driver. */
final class JdbcOutputContractTests {
    private JdbcOutputContractTests(){}
    static void register(){
        test("output-jdbc-mock.claim-autocommit-and-bound-identity",()->{
            var db=new Db();var claim=repo().claim(db.connection,pending());check(claim.created());eq(4,db.prepared.getLast().args.size());eq(pending().attemptId().toString(),db.prepared.getLast().args.get(1));eq(0,db.commits);check(db.prepared.stream().allMatch(s->s.closed&&s.timeout==10));
        });
        test("output-jdbc-mock.ready-and-identity-in-one-transaction",()->{
            var db=new Db();check(repo().finish(db.connection,pending(),readyRow()));eq(1,db.commits);eq(0,db.rollbacks);check(db.auto);
            eq(2,db.executed.size());eq(8,db.prepared.getFirst().args.size());
            var decoded=OutputCodec.decode((byte[])db.prepared.getFirst().args.get(2));decoded.checkPlan(plan(new FailurePolicy.Destroy()));eq(pending().planDigest(),decoded.planDigest());
        });
        test("output-jdbc-mock.identity-failure-rolls-back-ready",()->{
            var db=new Db();db.failSql="INSERT INTO iup_output_ids";expectSql(()->repo().finish(db.connection,pending(),readyRow()));eq(1,db.rollbacks);eq(0,db.commits);check(db.auto);
        });
        test("output-jdbc-mock.rollback-failure-never-autocommits",()->{
            var db=new Db();db.failSql="INSERT INTO iup_output_ids";db.failRollback=true;expectSql(()->repo().finish(db.connection,pending(),readyRow()));check(!db.auto);eq(0,db.restores);eq(1,db.rollbacks);
        });
        test("output-jdbc-mock.commit-ack-failure-is-not-ready-receipt",()->{
            var db=new Db();db.failCommit=true;expectSql(()->repo().finish(db.connection,pending(),readyRow()));eq(1,db.commits);eq(1,db.rollbacks);
        });
        test("output-jdbc-mock.stale-finish-does-not-insert-identities",()->{
            var db=new Db();db.updateCount=0;check(!repo().finish(db.connection,pending(),readyRow()));eq(1,db.executed.size());check(db.executed.getFirst().startsWith("UPDATE"));
        });
        test("output-jdbc-mock.reject-provider-owned-transaction",()->{
            var db=new Db();db.auto=false;expectSql(()->repo().claim(db.connection,pending()));expectSql(()->repo().finish(db.connection,pending(),readyRow()));check(db.executed.isEmpty());
        });
        test("output-jdbc-mock-read-validates-payload-checksum",()->{
            var db=new Db();db.row=readyRow();db.corrupt=true;expectSql(()->repo().find(db.connection,pending().attemptId()));
        });
        test("output-jdbc-mock-read-roundtrip-and-bound-owner",()->{
            var db=new Db();db.row=readyRow();var row=repo().find(db.connection,pending().attemptId()).orElseThrow();eq(OutputStore.State.READY,row.state());eq(OutputCodec.digest(db.row.output().orElseThrow()),OutputCodec.digest(row.output().orElseThrow()));check(db.prepared.getFirst().closed);
        });
        test("output-jdbc-mock-refuses-future-schema-before-other-ddl",()->{
            var db=new Db();db.version=2;expectSql(()->repo().initialize(db.connection,JournalSql.Dialect.SQLITE));eq(1,db.executed.size());
        });
    }
    private static OutputStore.Row pending(){var p=plan(new FailurePolicy.Destroy());return OutputStore.Row.pending(p.attemptId(),p.playerId(),JournalCodec.planDigest(p),OutputDemo.NOW);}
    private static OutputStore.Row readyRow()throws Exception{
        var p=prepared(new FailurePolicy.Destroy());var ids=new PreparedOutput(p.attemptId(),p.playerId(),p.planDigest(),p.success(),p.failure(),Set.of(SyntheticItems.hash("identity-sql-test")),Set.of(),p.createdAt());return ready(pending(),ids);
    }
    private static JdbcOutputRepository repo(){return new JdbcOutputRepository(new OutputSql("iup_outputs","iup_output_ids","iup_output_schema"));}
    private static void expectSql(Phase05SelfTest.Checked task)throws Exception{try{task.run();throw new AssertionError("expected SQLException");}catch(SQLException expected){check(true);}}
    private static final class StatementState {final String sql;final Map<Integer,Object> args=new HashMap<>();boolean closed;int timeout;StatementState(String sql){this.sql=sql;}}
    private static final class Db {
        boolean auto=true,failRollback,failCommit,corrupt;int restores,commits,rollbacks,updateCount=1;Integer version;String failSql="";OutputStore.Row row;
        final List<String> executed=new ArrayList<>();final List<StatementState> prepared=new ArrayList<>();
        final Connection connection=proxy(Connection.class,(p,m,a)->switch(m.getName()){
            case "getAutoCommit"->auto;
            case "setAutoCommit"->{auto=(Boolean)a[0];if(auto)restores++;yield null;}
            case "commit"->{commits++;if(failCommit)throw new SQLException("ack uncertain");yield null;}
            case "rollback"->{rollbacks++;if(failRollback)throw new SQLException("rollback uncertain");yield null;}
            case "prepareStatement"->statement((String)a[0]);
            case "createStatement"->proxy(Statement.class,(q,n,b)->switch(n.getName()){
                case "setQueryTimeout","close"->null;case "executeUpdate"->{executed.add((String)b[0]);yield 1;}default->unsupported(n);
            });
            default->unsupported(m);
        });
        PreparedStatement statement(String sql){
            var state=new StatementState(sql);prepared.add(state);
            return proxy(PreparedStatement.class,(p,m,a)->{
                String method=m.getName();if(method.equals("setQueryTimeout")){state.timeout=(Integer)a[0];return null;}
                if(method.startsWith("set")){state.args.put((Integer)a[0],method.equals("setNull")?null:a[1]);return null;}
                return switch(method){case "close"->{state.closed=true;yield null;}
                    case "executeUpdate"->{executed.add(sql);if(!failSql.isEmpty()&&sql.contains(failSql))throw new SQLException("injected write failure");yield sql.startsWith("UPDATE")?updateCount:1;}
                    case "executeQuery"->rows(sql);default->unsupported(m);};
            });
        }
        ResultSet rows(String sql){
            boolean[] consumed={false};boolean has=sql.startsWith("SELECT version")?version!=null:row!=null;
            return proxy(ResultSet.class,(p,m,a)->switch(m.getName()){
                case "next"->{boolean next=has&&!consumed[0];consumed[0]=true;yield next;}
                case "getInt"->version;
                case "getLong"->row.createdAt().toEpochMilli();
                case "getString"->switch((String)a[0]){
                    case "attempt_id"->row.attemptId().toString();case "player_uuid"->row.playerId().toString();case "plan_digest"->row.planDigest();case "state"->row.state().name();case "reason"->row.reason();
                    case "payload_digest"->corrupt?"0".repeat(64):row.output().map(OutputCodec::digest).orElse(null);default->throw new AssertionError("unknown column");};
                case "getBinaryStream"->row.output().map(o->new ByteArrayInputStream(OutputCodec.encode(o))).orElse(null);
                case "close"->null;default->unsupported(m);
            });
        }
    }
    private static Object unsupported(Method m){throw new UnsupportedOperationException("unmocked JDBC "+m.getName());}
    private static <T>T proxy(Class<T> type,InvocationHandler handler){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler));}
}
