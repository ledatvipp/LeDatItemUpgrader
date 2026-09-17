package vn.ledat.itemupgrader.demo;
import java.time.Instant;
import java.util.*;
import vn.ledat.itemupgrader.failure.FailurePolicy;
import vn.ledat.itemupgrader.transfer.TransferPolicy;
import vn.ledat.itemupgrader.item.ItemKey;
import vn.ledat.itemupgrader.transaction.TransactionMachine;
import vn.ledat.itemupgrader.transaction.model.*;
import vn.ledat.itemupgrader.transaction.storage.JournalCodec;
/** Synthetic v2 regression fixture, byte hashes only. Compiled once against the actual Phase07 ZIP for baseline. */
public final class LegacyOutputContract {
    private LegacyOutputContract(){}
    public static void main(String[] args) {
        var policies=List.of(new FailurePolicy.Destroy(),new FailurePolicy.Keep(),new FailurePolicy.Damage(2000,FailurePolicy.BreakBehavior.DESTROY),
                new FailurePolicy.Downgrade(ItemKey.of("minecraft:stone_sword"),1,TransferPolicy.clean()));
        for(FailurePolicy policy:policies) {
            var plan=OutputDemo.plan(policy,TransferPolicy.clean());String id=policy.mode().name();
            System.out.println(id+"/plan "+JournalCodec.planDigest(plan));
            for(boolean win:List.of(true,false)) {
                var machine=new TransactionMachine();Instant now=OutputDemo.NOW;var record=AttemptRecord.initial(plan,now);
                print(id,win,record);
                for(int tick=0;tick<200&&!record.terminal();tick++) {
                    var d=machine.next(record,now.plusMillis(tick));if(d.next()==record)throw new IllegalStateException("fixture paused");
                    record=d.next();print(id,win,record);
                    if(d.work()==TransactionMachine.Work.EFFECT){record=machine.receipt(record,record.steps().size()-1,new EffectReceipt(EffectReceipt.Status.APPLIED,"synthetic:v2"),now.plusMillis(tick));print(id,win,record);}
                    else if(d.work()==TransactionMachine.Work.DRAW){record=machine.commitDraw(record,win?0:999_999_999,now.plusMillis(tick));print(id,win,record);}
                }
                if(!record.terminal())throw new IllegalStateException("fixture bound");
            }
        }
    }
    private static void print(String id,boolean win,AttemptRecord record) {
        System.out.println(id+"/"+(win?"win":"loss")+"/"+record.version()+"/"+record.state()+" "+JournalCodec.digest(JournalCodec.encodeRecord(record))+" "+JournalCodec.digest(JournalCodec.encodeState(record)));
    }
}
