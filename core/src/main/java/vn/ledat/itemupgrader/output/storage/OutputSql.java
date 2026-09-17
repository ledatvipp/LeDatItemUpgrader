package vn.ledat.itemupgrader.output.storage;

import java.util.List;
import vn.ledat.itemupgrader.transaction.storage.JournalSql;

/** SQL is only defined here. Table names must come from the owner's Platform namespace. */
public record OutputSql(String outputs,String identities,String schema) {
    public OutputSql {
        for(String name:List.of(outputs,identities,schema))if(!name.matches("[A-Za-z][A-Za-z0-9_]{0,54}"))throw new IllegalArgumentException("unsafe output table identifier");
        if(java.util.Set.of(outputs,identities,schema).size()!=3)throw new IllegalArgumentException("output tables must differ");
    }
    public List<String> ddl(JournalSql.Dialect dialect) {
        String blob=dialect==JournalSql.Dialect.MYSQL?"LONGBLOB":"BLOB",suffix=dialect==JournalSql.Dialect.MYSQL?" ENGINE=InnoDB":"";
        return List.of(
            "CREATE TABLE IF NOT EXISTS "+schema+" (component VARCHAR(32) PRIMARY KEY, version INTEGER NOT NULL)"+suffix,
            "CREATE TABLE IF NOT EXISTS "+outputs+" (attempt_id VARCHAR(36) PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL, plan_digest VARCHAR(64) NOT NULL, state VARCHAR(16) NOT NULL, payload "+blob+", payload_digest VARCHAR(64), reason VARCHAR(64) NOT NULL, created_at BIGINT NOT NULL)"+suffix,
            "CREATE TABLE IF NOT EXISTS "+identities+" (token_digest VARCHAR(64) PRIMARY KEY, attempt_id VARCHAR(36) NOT NULL, role VARCHAR(16) NOT NULL)"+suffix);
    }
    public String version(){return "SELECT version FROM "+schema+" WHERE component=?";}
    public String insertVersion(){return "INSERT INTO "+schema+" (component,version) VALUES (?,?)";}
    public String insert(){return "INSERT INTO "+outputs+" (attempt_id,player_uuid,plan_digest,state,payload,payload_digest,reason,created_at) VALUES (?,?,?,'PREPARING',NULL,NULL,'',?)";}
    public String find(){return "SELECT attempt_id,player_uuid,plan_digest,state,payload,payload_digest,reason,created_at FROM "+outputs+" WHERE attempt_id=?";}
    public String finish(){return "UPDATE "+outputs+" SET state=?,payload=?,payload_digest=?,reason=? WHERE attempt_id=? AND player_uuid=? AND plan_digest=? AND state='PREPARING' AND created_at=?";}
    public String identity(){return "INSERT INTO "+identities+" (token_digest,attempt_id,role) VALUES (?,?,?)";}
}
