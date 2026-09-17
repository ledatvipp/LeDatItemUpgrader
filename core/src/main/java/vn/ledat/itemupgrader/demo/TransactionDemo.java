package vn.ledat.itemupgrader.demo;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import vn.ledat.itemupgrader.item.*;
import vn.ledat.itemupgrader.chance.Probability;
import vn.ledat.itemupgrader.cost.*;
import vn.ledat.itemupgrader.profile.RiskProfile;
import vn.ledat.itemupgrader.quote.UpgradeQuote;
import vn.ledat.itemupgrader.transaction.*;
import vn.ledat.itemupgrader.transaction.model.*;
import vn.ledat.itemupgrader.transaction.storage.*;
import vn.ledat.itemupgrader.demo.support.SimulationJournal;

/** OFFLINE synthetic transaction demo. No real money, items, Platform, SQL driver, server or GUI is touched. */
public final class TransactionDemo {
    public static final Instant NOW=Instant.parse("2026-09-16T00:00:00Z");
    public static final Clock CLOCK=Clock.fixed(NOW,ZoneOffset.UTC);
    private TransactionDemo(){}
    public static AttemptPlan samplePlan(UUID quote){
        var source=item("minecraft:iron_ingot",1);var target=item("minecraft:diamond",1);
        var fee=CostResource.currency(CostResource.Currency.VAULT);
        var costs=new CostPlan(List.of(new CostPlan.Line(fee,new BigDecimal("10"),new BigDecimal("20"),new BigDecimal("30"))));
        return new AttemptPlan(UUID.fromString("00000000-0000-0000-0000-000000000001"),UUID.fromString("00000000-0000-0000-0000-000000000002"),
                quote,7,UUID.fromString("00000000-0000-0000-0000-000000000004"),0,source,"diamond",target,new BigDecimal("90"),new BigDecimal("900"),
                new UpgradeQuote.Terms("standard",List.of(),List.of(),new Probability(90_000_000),RiskProfile.FailureMode.DESTROY,costs),List.of(),NOW.plusSeconds(30));
    }
    private static ItemSnapshot item(String key,int amount){return new ItemSnapshot(ItemFacts.clean(ItemKey.of(key),amount),("synthetic:"+key).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    public static EffectPort successfulPort(){return c->CompletableFuture.completedFuture(new EffectReceipt(EffectReceipt.Status.APPLIED,"simulation:"+c.ordinal()));}
    public static UpgradeTransactionEngine engine(SimulationJournal journal,EffectPort port,TicketSource random){
        return new UpgradeTransactionEngine(AsyncTransactionJournal.offload(journal,Runnable::run),port,random,Runnable::run,CLOCK,16,
                (label,error)->System.err.println("SIMULATED FAILURE "+label+": "+error.getClass().getSimpleName()));
    }
    public static void main(String[] args)throws Exception{
        var plan=samplePlan(UUID.fromString("00000000-0000-0000-0000-000000000003"));
        var journal=new SimulationJournal();AtomicInteger draws=new AtomicInteger(),deliveries=new AtomicInteger();
        EffectPort port=c->{if(c.effect().kind()==Effect.Kind.DELIVER_TARGET)deliveries.incrementAndGet();return successfulPort().execute(c);};
        var engine=engine(journal,port,()->{draws.incrementAndGet();return 12_345_678;});
        var result=engine.submit(plan).toCompletableFuture().get(10,TimeUnit.SECONDS);
        System.out.println("SIMULATION ONLY: all receipts and item payloads are synthetic.");
        System.out.println("ATTEMPT="+plan.attemptId()+" CHANCE=9% SAMPLE="+result.record().orElseThrow().sample()+" RESULT="+result.status());
        System.out.println("Vault reserve=40, success consumption=30, refund=10 (NOT real Vault calls)");
        System.out.println("DUPLICATE="+engine.submit(plan).toCompletableFuture().get().status()+" draws="+draws.get()+" deliveries="+deliveries.get());
        var pinned=journal.history().stream().map(JournalCodec::decodeRecord).filter(r->r.state()==AttemptRecord.State.OUTCOME_COMMITTED).findFirst().orElseThrow();
        AtomicInteger recoveryDraws=new AtomicInteger();var restored=SimulationJournal.restored(pinned);
        var recovered=engine(restored,successfulPort(),()->{recoveryDraws.incrementAndGet();return 999_999_999;}).resumeQuiescent(pinned).toCompletableFuture().get();
        System.out.println("RECOVERY="+recovered.status()+" sample="+recovered.record().orElseThrow().sample()+" additional-draws="+recoveryDraws.get());
        var uncertain=new SimulationJournal();EffectPort uncertainPort=c->CompletableFuture.completedFuture(c.effect().kind()==Effect.Kind.DEBIT_CURRENCY
                ?EffectReceipt.unknown("provider-no-durable-answer"):new EffectReceipt(EffectReceipt.Status.APPLIED,"simulation:"+c.ordinal()));
        var paused=engine(uncertain,uncertainPort,()->{throw new IllegalStateException("must not draw");}).submit(plan).toCompletableFuture().get();
        System.out.println("AMBIGUOUS_DEBIT="+paused.status()+" player-lock-retained="+uncertain.activeAttempt(plan.playerId()).isPresent());
        System.out.println("Native escrow, Platform Ledger adapter, durable mailbox and GUI are NOT implemented by this demo.");
    }
}
