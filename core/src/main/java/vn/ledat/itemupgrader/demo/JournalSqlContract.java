package vn.ledat.itemupgrader.demo;

import java.util.*;
import vn.ledat.itemupgrader.transaction.storage.*;
import vn.ledat.itemupgrader.transaction.model.*;
import vn.ledat.itemupgrader.demo.support.SimulationJournal;

/** Exports the EXACT SQL strings used by JdbcTransactionRepository, plus actual codec fixtures, for SQLite SQL tests. */
public final class JournalSqlContract {
    private JournalSqlContract(){}
    public static void main(String[] args)throws Exception{
        var sql=new JournalSql(JournalSql.Tables.prefixed("iup_"));var plan=TransactionDemo.samplePlan(UUID.fromString("00000000-0000-0000-0000-000000000003"));
        var journal=new SimulationJournal();TransactionDemo.engine(journal,TransactionDemo.successfulPort(),()->12_345_678).submit(plan).toCompletableFuture().get();
        Map<String,String> statements=new LinkedHashMap<>();
        statements.put("insert-attempt",sql.insertAttempt());statements.put("insert-lock",sql.insertLock());statements.put("insert-event",sql.insertEvent());
        statements.put("update",sql.update());statements.put("unlock",sql.unlock());statements.put("active",sql.active());statements.put("find",sql.find());
        statements.put("find-by-key",sql.findByKey());statements.put("unfinished",sql.unfinished());statements.put("schema-version",sql.schemaVersion());statements.put("insert-version",sql.insertVersion());
        System.out.println("{\"sqlite-ddl\":"+array(sql.ddl(JournalSql.Dialect.SQLITE))+",\"mysql-ddl\":"+array(sql.ddl(JournalSql.Dialect.MYSQL))+
                ",\"indexes\":"+array(new ArrayList<>(sql.indexStatements().values()))+",\"queries\":"+map(statements)+",\"records\":["+
                String.join(",",journal.history().stream().map(JournalCodec::decodeRecord).map(JournalSqlContract::record).toList())+
                "],\"competitor\":"+record(AttemptRecord.initial(TransactionDemo.samplePlan(UUID.fromString("00000000-0000-0000-0000-000000000099")),TransactionDemo.NOW))+"}");
    }
    private static String record(AttemptRecord r){
        byte[] bytes=JournalCodec.encodeState(r);Map<String,String> fields=new LinkedHashMap<>();
        fields.put("id",r.id().toString());fields.put("key",r.plan().idempotencyKey());fields.put("player",r.plan().playerId().toString());fields.put("planDigest",JournalCodec.planDigest(r.plan()));
        fields.put("planPayload",Base64.getEncoder().encodeToString(JournalCodec.encodePlan(r.plan())));
        fields.put("state",r.state().name());fields.put("version",Long.toString(r.version()));fields.put("created",Long.toString(r.createdAt().toEpochMilli()));fields.put("updated",Long.toString(r.updatedAt().toEpochMilli()));
        fields.put("sample",r.sample()==null?"":r.sample().toString());fields.put("payload",Base64.getEncoder().encodeToString(bytes));fields.put("payloadDigest",JournalCodec.digest(bytes));return map(fields);
    }
    private static String map(Map<String,String> values){List<String> parts=new ArrayList<>();values.forEach((k,v)->parts.add(quote(k)+":"+quote(v)));return "{"+String.join(",",parts)+"}";}
    private static String array(List<String> values){return "["+String.join(",",values.stream().map(JournalSqlContract::quote).toList())+"]";}
    private static String quote(String value){return "\""+value.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n")+"\"";}
}
