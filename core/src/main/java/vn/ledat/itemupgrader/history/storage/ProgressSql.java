package vn.ledat.itemupgrader.history.storage;
import java.util.*;
import vn.ledat.itemupgrader.storage.SchemaPlan;
import vn.ledat.itemupgrader.transaction.storage.JournalSql;
import vn.ledat.itemupgrader.history.HistoryQuery;
/** Compact projections/tombstones; NEVER deletes or edits authoritative journal/output/ledger tables. */
public final class ProgressSql {
    public record Tables(String history,String statistics,String pity,String completions,String metadata) {
        public Tables {
            var names=List.of(history,statistics,pity,completions,metadata);names.forEach(SchemaPlan::identifier);
            if(new HashSet<>(names).size()!=5||names.stream().anyMatch(n->n.length()>44))throw new IllegalArgumentException("invalid progress table names");
        }
        public static Tables prefixed(String p){return new Tables(p+"history",p+"statistics",p+"pity",p+"completions",p+"progress_schema");}
    }
    private final Tables t;
    public ProgressSql(Tables t){this.t=Objects.requireNonNull(t);}
    public Tables tables(){return t;}
    public List<String> ddl(JournalSql.Dialect dialect) {
        String suffix=dialect==JournalSql.Dialect.MYSQL?" ENGINE=InnoDB":"";
        return List.of(
            "CREATE TABLE IF NOT EXISTS "+t.metadata()+" (component VARCHAR(32) PRIMARY KEY,version INTEGER NOT NULL)"+suffix,
            "CREATE TABLE IF NOT EXISTS "+t.history()+" (tx_id VARCHAR(36) PRIMARY KEY,player_uuid VARCHAR(36) NOT NULL,version BIGINT NOT NULL,state VARCHAR(32) NOT NULL,outcome VARCHAR(16) NOT NULL,source_key VARCHAR(256) NOT NULL,source_amount INTEGER NOT NULL,target_id VARCHAR(64) NOT NULL,target_key VARCHAR(256) NOT NULL,target_amount INTEGER NOT NULL,source_value VARCHAR(64) NOT NULL,target_value VARCHAR(64) NOT NULL,tickets BIGINT NOT NULL,profile VARCHAR(64) NOT NULL,failure VARCHAR(24) NOT NULL,created_at BIGINT NOT NULL,updated_at BIGINT NOT NULL,terminal INTEGER NOT NULL,plan_digest VARCHAR(64) NOT NULL,state_digest VARCHAR(64) NOT NULL)"+suffix,
            "CREATE TABLE IF NOT EXISTS "+t.statistics()+" (player_uuid VARCHAR(36) PRIMARY KEY,completed BIGINT NOT NULL,wins BIGINT NOT NULL,losses BIGINT NOT NULL)"+suffix,
            "CREATE TABLE IF NOT EXISTS "+t.pity()+" (player_uuid VARCHAR(36) NOT NULL,scope VARCHAR(64) NOT NULL,version BIGINT NOT NULL,failures BIGINT NOT NULL,PRIMARY KEY(player_uuid,scope))"+suffix,
            "CREATE TABLE IF NOT EXISTS "+t.completions()+" (tx_id VARCHAR(36) PRIMARY KEY,player_uuid VARCHAR(36) NOT NULL,plan_digest VARCHAR(64) NOT NULL,state_digest VARCHAR(64) NOT NULL,completed_at BIGINT NOT NULL)"+suffix);
    }
    public Map<String,String> indexes() {
        return Map.of(t.history()+"_owner_time","CREATE INDEX "+t.history()+"_owner_time ON "+t.history()+" (player_uuid,created_at,tx_id)",
            t.history()+"_terminal_time","CREATE INDEX "+t.history()+"_terminal_time ON "+t.history()+" (terminal,updated_at,tx_id)");
    }
    public String version(){return "SELECT version FROM "+t.metadata()+" WHERE component=?";}
    public String insertVersion(){return "INSERT INTO "+t.metadata()+" (component,version) VALUES (?,?)";}
    public String insertHistory(){return "INSERT INTO "+t.history()+" (tx_id,player_uuid,version,state,outcome,source_key,source_amount,target_id,target_key,target_amount,source_value,target_value,tickets,profile,failure,created_at,updated_at,terminal,plan_digest,state_digest) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";}
    public String historyIdentity(){return "SELECT version,plan_digest,state_digest FROM "+t.history()+" WHERE tx_id=?";}
    public String updateHistory(){return "UPDATE "+t.history()+" SET version=?,state=?,outcome=?,updated_at=?,terminal=?,state_digest=? WHERE tx_id=? AND version=? AND plan_digest=? AND state_digest=?";}
    public String page(HistoryQuery.Filter filter) {
        String condition=switch(filter){case ALL->"";case WIN->" AND outcome='WIN'";case LOSS->" AND outcome='LOSS'";case UNFINISHED->" AND terminal=0";};
        return "SELECT * FROM "+t.history()+" WHERE player_uuid=? AND created_at<=? AND (created_at<? OR (created_at=? AND tx_id<?))"+condition+" ORDER BY created_at DESC,tx_id DESC LIMIT ?";
    }
    public String stats(){return "SELECT completed,wins,losses FROM "+t.statistics()+" WHERE player_uuid=?";}
    public String insertStats(){return "INSERT INTO "+t.statistics()+" (player_uuid,completed,wins,losses) VALUES (?,0,0,0)";}
    public String updateStats(){return "UPDATE "+t.statistics()+" SET completed=?,wins=?,losses=? WHERE player_uuid=? AND completed=? AND wins=? AND losses=?";}
    public String pity(){return "SELECT version,failures FROM "+t.pity()+" WHERE player_uuid=? AND scope=?";}
    public String insertPity(){return "INSERT INTO "+t.pity()+" (player_uuid,scope,version,failures) VALUES (?,?,0,0)";}
    public String updatePity(){return "UPDATE "+t.pity()+" SET version=?,failures=? WHERE player_uuid=? AND scope=? AND version=? AND failures=?";}
    public String receipt(){return "SELECT player_uuid,plan_digest,state_digest FROM "+t.completions()+" WHERE tx_id=?";}
    public String insertReceipt(){return "INSERT INTO "+t.completions()+" (tx_id,player_uuid,plan_digest,state_digest,completed_at) VALUES (?,?,?,?,?)";}
    public String retentionCandidates(){return "SELECT tx_id FROM "+t.history()+" WHERE terminal=1 AND state IN ('COMPLETED','ABORTED') AND updated_at<? ORDER BY updated_at ASC,tx_id ASC LIMIT ?";}
    public String pruneOne(){return "DELETE FROM "+t.history()+" WHERE tx_id=? AND terminal=1 AND state IN ('COMPLETED','ABORTED') AND updated_at<?";}
}
