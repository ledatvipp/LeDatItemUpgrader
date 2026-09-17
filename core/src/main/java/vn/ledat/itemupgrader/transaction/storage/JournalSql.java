package vn.ledat.itemupgrader.transaction.storage;

import java.util.*;
import vn.ledat.itemupgrader.storage.SchemaPlan;

/** All SQL identifiers are admin/provider-supplied validated identifiers; every value is a bound parameter. */
public final class JournalSql {
    public enum Dialect { SQLITE, MYSQL }
    public record Tables(String attempts,String locks,String events,String metadata) {
        public Tables {
            List<String> names=List.of(attempts,locks,events,metadata);
            names.forEach(SchemaPlan::identifier);
            if(new HashSet<>(names).size()!=names.size() || names.stream().anyMatch(n->n.length()>44))
                throw new IllegalArgumentException("table names must be distinct and <=44 characters");
        }
        public static Tables prefixed(String prefix){return new Tables(prefix+"attempts",prefix+"player_locks",prefix+"tx_events",prefix+"tx_schema");}
    }
    private final Tables t;
    public JournalSql(Tables tables){this.t=Objects.requireNonNull(tables);}
    public Tables tables(){return t;}
    public Map<String,String> indexStatements(){
        Map<String,String> indexes=new LinkedHashMap<>();
        indexes.put(t.attempts()+"_player_time","CREATE INDEX "+t.attempts()+"_player_time ON "+t.attempts()+" (player_uuid,created_at,tx_id)");
        indexes.put(t.attempts()+"_state_time","CREATE INDEX "+t.attempts()+"_state_time ON "+t.attempts()+" (state,updated_at,tx_id)");
        return Collections.unmodifiableMap(indexes);
    }
    public List<String> ddl(Dialect dialect){
        String blob=dialect==Dialect.MYSQL?"LONGBLOB":"BLOB",suffix=dialect==Dialect.MYSQL?" ENGINE=InnoDB":"";
        return List.of(
            "CREATE TABLE IF NOT EXISTS "+t.metadata()+" (component VARCHAR(32) PRIMARY KEY,version INTEGER NOT NULL,updated_at BIGINT NOT NULL)"+suffix,
            "CREATE TABLE IF NOT EXISTS "+t.attempts()+" (tx_id VARCHAR(36) PRIMARY KEY,idempotency_key VARCHAR(128) NOT NULL UNIQUE,player_uuid VARCHAR(36) NOT NULL,plan_digest VARCHAR(64) NOT NULL,plan_payload "+blob+" NOT NULL,state VARCHAR(32) NOT NULL,version BIGINT NOT NULL,created_at BIGINT NOT NULL,updated_at BIGINT NOT NULL,sample BIGINT,payload "+blob+" NOT NULL,payload_digest VARCHAR(64) NOT NULL)"+suffix,
            "CREATE TABLE IF NOT EXISTS "+t.locks()+" (player_uuid VARCHAR(36) PRIMARY KEY,tx_id VARCHAR(36) NOT NULL UNIQUE)"+suffix,
            "CREATE TABLE IF NOT EXISTS "+t.events()+" (tx_id VARCHAR(36) NOT NULL,version BIGINT NOT NULL,state VARCHAR(32) NOT NULL,created_at BIGINT NOT NULL,payload_digest VARCHAR(64) NOT NULL,PRIMARY KEY(tx_id,version))"+suffix);
    }
    public String insertAttempt(){return "INSERT INTO "+t.attempts()+" (tx_id,idempotency_key,player_uuid,plan_digest,plan_payload,state,version,created_at,updated_at,sample,payload,payload_digest) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)";}
    public String insertLock(){return "INSERT INTO "+t.locks()+" (player_uuid,tx_id) VALUES (?,?)";}
    public String insertEvent(){return "INSERT INTO "+t.events()+" (tx_id,version,state,created_at,payload_digest) VALUES (?,?,?,?,?)";}
    public String update(){return "UPDATE "+t.attempts()+" SET state=?,version=?,updated_at=?,sample=?,payload=?,payload_digest=? WHERE tx_id=? AND version=? AND payload_digest=? AND plan_digest=?";}
    public String unlock(){return "DELETE FROM "+t.locks()+" WHERE player_uuid=? AND tx_id=?";}
    public String active(){return "SELECT tx_id FROM "+t.locks()+" WHERE player_uuid=?";}
    public String find(){return "SELECT * FROM "+t.attempts()+" WHERE tx_id=?";}
    public String findByKey(){return "SELECT * FROM "+t.attempts()+" WHERE idempotency_key=?";}
    public String unfinished(){return "SELECT * FROM "+t.attempts()+" WHERE state NOT IN ('COMPLETED','ABORTED') AND tx_id>? ORDER BY tx_id ASC LIMIT ?";}
    public String schemaVersion(){return "SELECT version FROM "+t.metadata()+" WHERE component=?";}
    public String insertVersion(){return "INSERT INTO "+t.metadata()+" (component,version,updated_at) VALUES (?,?,?)";}
}
