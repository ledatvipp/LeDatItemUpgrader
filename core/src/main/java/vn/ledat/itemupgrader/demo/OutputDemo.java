package vn.ledat.itemupgrader.demo;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import vn.ledat.itemupgrader.demo.support.*;
import vn.ledat.itemupgrader.output.*;
import vn.ledat.itemupgrader.output.storage.*;
import vn.ledat.itemupgrader.item.*;
import vn.ledat.itemupgrader.transfer.*;
import vn.ledat.itemupgrader.failure.*;
import vn.ledat.itemupgrader.value.*;
import vn.ledat.itemupgrader.quote.*;
import vn.ledat.itemupgrader.chance.*;
import vn.ledat.itemupgrader.cost.*;
import vn.ledat.itemupgrader.transaction.*;
import vn.ledat.itemupgrader.transaction.model.*;
import vn.ledat.itemupgrader.transaction.storage.*;

/** Offline end-to-end simulation. No real inventory, money, provider API or JDBC is used. */
public final class OutputDemo {
    public static final Instant NOW=Instant.parse("2026-09-17T00:00:00Z");
    public static final Clock CLOCK=Clock.fixed(NOW,ZoneOffset.UTC);
    private OutputDemo(){}
    public static ValueDefinitions values(){return ValueDefinitions.manualOnly(Map.of(ItemKey.of("minecraft:iron_sword"),new BigDecimal("1000"),ItemKey.of("minecraft:diamond_sword"),new BigDecimal("2000"),ItemKey.of("minecraft:stone_sword"),new BigDecimal("500")));}
    public static SyntheticItems items(){
        var items=new SyntheticItems();items.template(SyntheticItems.vanilla("minecraft:iron_sword",1,0,250));items.template(SyntheticItems.vanilla("minecraft:diamond_sword",1,0,1561));items.template(SyntheticItems.vanilla("minecraft:stone_sword",1,0,131));
        items.remember(SyntheticItems.vanilla("minecraft:iron_sword",1,40,250));return items;
    }
    public static AttemptPlan plan(FailurePolicy failure,TransferPolicy transfer){
        return new AttemptPlan(new UUID(0,101),new UUID(0,102),new UUID(0,103),7,new UUID(0,104),0,
                SyntheticItems.vanilla("minecraft:iron_sword",1,40,250).snapshot(),"diamond_sword",SyntheticItems.vanilla("minecraft:diamond_sword",1,0,1561).snapshot(),
                new BigDecimal("1000"),new BigDecimal("2000"),new UpgradeQuote.Terms("standard",List.of(),List.of(),new Probability(450_000_000),failure.mode(),new CostPlan(List.of()),Optional.of(new OutputSpec(transfer,failure))),List.of(),NOW.plusSeconds(30));
    }
    public static void main(String[] args)throws Exception{
        System.out.println("OFFLINE SIMULATION ONLY -- synthetic item payloads; no player inventory/economy calls.");
        for(FailurePolicy loss:List.of(new FailurePolicy.Destroy(),new FailurePolicy.Keep(),new FailurePolicy.Damage(2000,FailurePolicy.BreakBehavior.DESTROY),new FailurePolicy.Downgrade(ItemKey.of("minecraft:stone_sword"),1,TransferPolicy.clean()))){
            for(boolean win:List.of(true,false)){
                var items=items();var store=new SimulationOutputStore();var materializer=new OutputMaterializer(items,values(),7,Runnable::run,CLOCK);
                var outputs=new OutputPreparationService(store,materializer::materialize,Runnable::run,CLOCK,(operation,error)->{System.err.println(operation);error.printStackTrace(System.err);});var journal=new SimulationJournal();var draws=new AtomicInteger();var delivered=new ArrayList<PreparedDeliveryPort.Delivery>();
                EffectPort nativeEffects=call->CompletableFuture.completedFuture(new EffectReceipt(EffectReceipt.Status.APPLIED,"simulation:"+call.ordinal()));
                var port=new OutputBoundEffectPort(outputs,nativeEffects,d->{delivered.add(d);return CompletableFuture.completedFuture(new EffectReceipt(EffectReceipt.Status.APPLIED,"simulation:delivery"));},Runnable::run);
                var engine=new UpgradeTransactionEngine(AsyncTransactionJournal.offload(journal,Runnable::run),port,()->{draws.incrementAndGet();return win?0:999_999_999;},Runnable::run,CLOCK,8,(tag,error)->error.printStackTrace(System.err));
                var plan=plan(loss,TransferPolicy.clean());var result=engine.submit(plan).toCompletableFuture().get();
                var repeated=engine.submit(plan).toCompletableFuture().get();
                System.out.println(loss.mode()+" / "+(win?"WIN":"LOSS")+" -> "+result.status()+"; retry="+repeated.status()+"; creations="+items.creations+"; draws="+draws+"; prepared-deliveries="+delivered.size()
                        +(delivered.isEmpty()?"":"; item="+delivered.getFirst().item().facts().key()+"; damage="+delivered.getFirst().item().facts().damage()));
            }
        }
    }
}
