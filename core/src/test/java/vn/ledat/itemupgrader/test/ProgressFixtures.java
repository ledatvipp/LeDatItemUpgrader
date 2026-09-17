package vn.ledat.itemupgrader.test;
import java.time.*;
import java.util.*;
import java.math.BigDecimal;
import vn.ledat.itemupgrader.demo.OutputDemo;
import vn.ledat.itemupgrader.failure.FailurePolicy;
import vn.ledat.itemupgrader.transfer.TransferPolicy;
import vn.ledat.itemupgrader.quote.UpgradeQuote;
import vn.ledat.itemupgrader.pity.*;
import vn.ledat.itemupgrader.history.*;
import vn.ledat.itemupgrader.transaction.*;
import vn.ledat.itemupgrader.transaction.model.*;
import vn.ledat.itemupgrader.transaction.storage.JournalCodec;

/** Synthetic item payloads and receipts only. Used by both pure tests and the explicitly test-only SQLite bridge. */
final class ProgressFixtures {
    static final Instant NOW=OutputDemo.NOW;
    static final String SCOPE="a".repeat(64);
    private ProgressFixtures(){}
    static AttemptPlan plan(long quote,long version,long failures) {
        var p=OutputDemo.plan(new FailurePolicy.Destroy(),TransferPolicy.clean());var t=p.terms();
        var stamp=new PityStamp("diamond_progress",1,SCOPE,version,failures,new BigDecimal("1.25"),new BigDecimal("10"));
        return withTerms(p,new UUID(0,quote),new UpgradeQuote.Terms(t.profileId(),t.boosts(),t.permissionBonuses(),t.probability(),t.failure(),t.costs(),t.output(),Optional.of(stamp)));
    }
    static AttemptPlan withTerms(AttemptPlan p,UUID quote,UpgradeQuote.Terms terms) {
        return new AttemptPlan(p.playerId(),p.sessionId(),quote,p.configRevision(),p.catalogGeneration(),p.sourceSlot(),p.source(),p.targetId(),p.target(),
                p.sourceTotal(),p.targetTotal(),terms,p.feeItems(),p.expiresAt());
    }
    static List<AttemptRecord> history(AttemptPlan p,boolean success) {return history(p,success,NOW);}
    static List<AttemptRecord> history(AttemptPlan p,boolean success,Instant now) {
        var m=new TransactionMachine();var r=AttemptRecord.initial(p,now);var rows=new ArrayList<AttemptRecord>();rows.add(r);
        for(int i=0;i<200&&!r.terminal();i++) {
            var decision=m.next(r,now.plusMillis(i));if(decision.next()==r)throw new IllegalStateException("unexpected pause");
            var next=decision.next();JournalCodec.checkTransition(r,next);rows.add(next);r=next;
            if(decision.work()==TransactionMachine.Work.EFFECT)next=m.receipt(r,r.steps().size()-1,new EffectReceipt(EffectReceipt.Status.APPLIED,"synthetic:test"),now.plusMillis(i));
            else if(decision.work()==TransactionMachine.Work.DRAW)next=m.commitDraw(r,success?0:999_999_999,now.plusMillis(i));
            if(next!=r){JournalCodec.checkTransition(r,next);rows.add(next);r=next;}
        }
        if(!r.terminal())throw new IllegalStateException("iteration bound");return List.copyOf(rows);
    }
    static HistoryEntry row(UUID player,long id,long millis,HistoryEntry.Outcome outcome,boolean completed) {
        var p=plan(id,0,0);
        var state=outcome==HistoryEntry.Outcome.NOT_ROLLED?AttemptRecord.State.RESERVING:completed?AttemptRecord.State.COMPLETED:AttemptRecord.State.SETTLING;
        return new HistoryEntry(new UUID(0,id),player,10,state,outcome,p.source().facts().key(),1,p.targetId(),p.target().facts().key(),1,
                p.sourceTotal(),p.targetTotal(),p.terms().probability(),"standard","DESTROY",Instant.ofEpochMilli(millis),Instant.ofEpochMilli(millis));
    }
}
