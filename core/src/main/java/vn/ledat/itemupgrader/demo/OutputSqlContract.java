package vn.ledat.itemupgrader.demo;

import java.util.*;
import vn.ledat.itemupgrader.output.*;
import vn.ledat.itemupgrader.output.storage.*;
import vn.ledat.itemupgrader.transaction.storage.*;
import vn.ledat.itemupgrader.failure.*;
import vn.ledat.itemupgrader.transfer.*;

/** EXACT repository SQL + genuine core-codec fixtures. Synthetic payloads, not Minecraft NBT. */
public final class OutputSqlContract {
    private OutputSqlContract(){}
    public static void main(String[] args)throws Exception{
        var sql=new OutputSql("iup_outputs","iup_output_ids","iup_schema");
        var plan=OutputDemo.plan(new FailurePolicy.Damage(2000,FailurePolicy.BreakBehavior.DESTROY),TransferPolicy.clean());
        var prepared=new OutputMaterializer(OutputDemo.items(),OutputDemo.values(),7,Runnable::run,OutputDemo.CLOCK).materialize(plan).toCompletableFuture().get();
        // Identity hashes in this fixture exercise SQL atomic uniqueness only, not a native provider claim.
        var fixture=new PreparedOutput(prepared.attemptId(),prepared.playerId(),prepared.planDigest(),prepared.success(),prepared.failure(),Set.of(vn.ledat.itemupgrader.demo.support.SyntheticItems.hash("synthetic-unique-id")),Set.of(),prepared.createdAt());
        Map<String,String> queries=new LinkedHashMap<>();queries.put("version",sql.version());queries.put("insert-version",sql.insertVersion());queries.put("insert",sql.insert());queries.put("find",sql.find());queries.put("finish",sql.finish());queries.put("identity",sql.identity());
        Map<String,String> f=new LinkedHashMap<>();f.put("attempt",plan.attemptId().toString());f.put("player",plan.playerId().toString());f.put("planDigest",JournalCodec.planDigest(plan));f.put("planPayload",Base64.getEncoder().encodeToString(JournalCodec.encodePlan(plan)));f.put("created",Long.toString(OutputDemo.NOW.toEpochMilli()));f.put("payload",Base64.getEncoder().encodeToString(OutputCodec.encode(fixture)));f.put("payloadDigest",OutputCodec.digest(fixture));f.put("identity",fixture.successIdentities().iterator().next());
        System.out.println("{\"sqlite-ddl\":"+array(sql.ddl(JournalSql.Dialect.SQLITE))+",\"mysql-ddl\":"+array(sql.ddl(JournalSql.Dialect.MYSQL))+",\"queries\":"+map(queries)+",\"fixture\":"+map(f)+"}");
    }
    private static String map(Map<String,String> values){List<String> parts=new ArrayList<>();values.forEach((k,v)->parts.add(quote(k)+":"+quote(v)));return "{"+String.join(",",parts)+"}";}
    private static String array(List<String> values){return "["+String.join(",",values.stream().map(OutputSqlContract::quote).toList())+"]";}
    private static String quote(String value){return "\""+value.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n")+"\"";}
}
