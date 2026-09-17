package vn.ledat.itemupgrader.test;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import vn.ledat.itemupgrader.item.*;
import vn.ledat.itemupgrader.cost.*;
import vn.ledat.itemupgrader.chance.*;
import vn.ledat.itemupgrader.catalog.*;
import vn.ledat.itemupgrader.quote.*;
import vn.ledat.itemupgrader.transaction.*;
import vn.ledat.itemupgrader.transaction.model.*;
import vn.ledat.itemupgrader.transaction.storage.*;
import vn.ledat.itemupgrader.demo.support.SimulationJournal;
import static vn.ledat.itemupgrader.test.TransactionFixtures.*;

/** No live Bukkit, provider, JDBC driver, Minecraft persistence or operating-system power-loss test is claimed. */
public final class Phase04SelfTest {
    @FunctionalInterface interface Checked{void run()throws Exception;}
    record Test(String name,Checked run){}
    private static final List<Test> TESTS=new ArrayList<>();
    private static int assertions;
    private Phase04SelfTest(){}
    public static void main(String[] args)throws Exception{
        plannerTests();codecTests();scriptTests();machineTests();driverTests();recoveryTests();gateTests();propertyTests();hardeningTests();
        JdbcJournalContractTests.register(Phase04SelfTest::test,Phase04SelfTest::check);
        int failures=0;StringBuilder xml=new StringBuilder();
        for(var test:TESTS){long start=System.nanoTime();String error=null;
            try{test.run().run();}catch(Exception|AssertionError e){failures++;error=e.toString();e.printStackTrace(System.err);}
            System.out.println((error==null?"PASS ":"FAIL ")+test.name());
            xml.append("  <testcase classname=\"Phase04\" name=\"").append(escape(test.name())).append("\" time=\"")
                .append(String.format(Locale.ROOT,"%.6f",(System.nanoTime()-start)/1e9)).append("\">");
            if(error!=null)xml.append("<failure message=\"").append(escape(error)).append("\"/>");xml.append("</testcase>\n");
        }
        String report="<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuite name=\"ItemUpgraderPhase04\" tests=\""+TESTS.size()+"\" failures=\""+failures+"\" errors=\"0\">\n"+xml+
                "<system-out>assertions="+assertions+"; Core/simulation/JDBC-mock contracts only.</system-out>\n</testsuite>\n";
        Path path=Path.of(args.length>0?args[0]:"core/build/reports/phase04-self-test.xml");Files.createDirectories(path.toAbsolutePath().getParent());Files.writeString(path,report,StandardCharsets.UTF_8);
        System.out.println("RESULT tests="+TESTS.size()+" assertions="+assertions+" failures="+failures);
        if(failures>0)throw new AssertionError(failures+" test groups failed");
    }
    static void test(String name,Checked test){TESTS.add(new Test(name,test));}
    static void check(boolean ok){assertions++;if(!ok)throw new AssertionError("condition failed");}
    static void eq(Object a,Object b){assertions++;if(!Objects.equals(a,b))throw new AssertionError("expected "+a+", got "+b);}
    static void rejects(Checked work)throws Exception{assertions++;try{work.run();}catch(IllegalArgumentException|IllegalStateException expected){return;}throw new AssertionError("expected rejection");}
    static String escape(String s){return s.replace("&","&amp;").replace("<","&lt;").replace("\"","&quot;");}
    private static void plannerTests(){
        for(var status:UpgradeQuoteService.ValidationStatus.values())if(status!=UpgradeQuoteService.ValidationStatus.VALID_PREVIEW)
            test("planner.reject-"+status,()->rejects(()->new AttemptPlanner().prepare(new UpgradeQuoteService.Validation(status,Optional.empty()),plan().source(),plan().target(),Map.of(),NOW)));
        test("planner.valid-revalidated-quote-binds-payload",()->{
            var p=plan(true,false);var q=quoteFrom(p);var snapshots=new HashMap<Integer,ItemSnapshot>();p.feeItems().forEach(h->snapshots.put(h.slot(),h.snapshot()));
            var result=new AttemptPlanner().prepare(new UpgradeQuoteService.Validation(UpgradeQuoteService.ValidationStatus.VALID_PREVIEW,Optional.of(q)),p.source(),p.target(),snapshots,NOW);
            eq(JournalCodec.planDigest(p),JournalCodec.planDigest(result));
        });
        test("planner.reject-missing-fee-snapshot",()->rejects(()->new AttemptPlanner().prepare(valid(plan(true,false)),plan().source(),plan().target(),Map.of(),NOW)));
        test("planner.reject-source-changed",()->rejects(()->new AttemptPlanner().prepare(valid(plan()),item("minecraft:gold_ingot",1),plan().target(),Map.of(),NOW)));
        test("planner.reject-target-changed",()->rejects(()->new AttemptPlanner().prepare(valid(plan()),plan().source(),item("minecraft:emerald",1),Map.of(),NOW)));
        test("planner.reject-expired-at-boundary",()->rejects(()->new AttemptPlanner().prepare(valid(plan()),plan().source(),plan().target(),Map.of(),NOW.plusSeconds(30))));
        test("plan.source-slot-cannot-pay-fee",()->{
            var p=plan(true,false);var holds=new ArrayList<>(p.feeItems());holds.set(0,new AttemptPlan.ItemHold(0,3,holds.getFirst().snapshot()));rejects(()->withHolds(p,holds));
        });
        test("plan.no-over-or-under-allocation",()->{
            var p=plan(true,false);var h=new ArrayList<>(p.feeItems());h.set(0,new AttemptPlan.ItemHold(1,2,h.getFirst().snapshot()));rejects(()->withHolds(p,h));
            h.set(0,new AttemptPlan.ItemHold(1,4,h.getFirst().snapshot()));rejects(()->withHolds(p,h));
        });
        test("plan.no-extra-resource-allocation",()->rejects(()->withHolds(plan(),List.of(new AttemptPlan.ItemHold(1,1,item("minecraft:emerald",1))))));
        test("plan.reject-risky-metadata",()->{
            var unsafe=new ItemSnapshot(new ItemFacts(ItemKey.of("minecraft:paper"),1,0,0,Map.of(),"",Set.of(ItemFacts.Risk.IDENTITY_UNVERIFIED)),new byte[]{1});
            rejects(()->new AttemptPlan.ItemHold(1,1,unsafe));
        });
        test("plan.immutable-sorted-holds",()->{
            var p=plan(true,false);var list=new ArrayList<>(p.feeItems());Collections.reverse(list);var copy=withHolds(p,list);list.clear();
            eq(3,copy.feeItems().size());eq(1,copy.feeItems().getFirst().slot());
            byte[] before=copy.source().bytes();byte[] external=copy.source().bytes();external[0]++;check(Arrays.equals(before,copy.source().bytes()));
        });
    }
    private static UpgradeQuoteService.Validation valid(AttemptPlan p){return new UpgradeQuoteService.Validation(UpgradeQuoteService.ValidationStatus.VALID_PREVIEW,Optional.of(quoteFrom(p)));}
    private static vn.ledat.itemupgrader.quote.UpgradeQuote quoteFrom(AttemptPlan p){
        var sel=new TargetSelectionService.Selection(p.playerId(),p.sessionId(),p.configRevision(),p.catalogGeneration(),p.source().fingerprint(),p.source().facts(),p.targetId(),p.target().fingerprint(),p.expiresAt());
        var alloc=p.feeItems().stream().map(h->new ResourceAssessment.Allocation(CostResource.item(h.snapshot().facts().key()),h.slot(),h.amount(),h.snapshot().fingerprint())).toList();
        return new UpgradeQuote(p.quoteId(),new QuoteRequest(p.sessionId(),p.targetId(),p.terms().profileId(),p.terms().boosts(),p.sourceSlot()),sel,p.sourceTotal(),p.targetTotal(),
                new ChanceQuote(p.terms().probability(),d("0.1"),List.of(),false,false),p.terms(),new ResourceAssessment(ResourceAssessment.Status.AVAILABLE_PREVIEW,List.of(),alloc));
    }
    private static AttemptPlan withHolds(AttemptPlan p,List<AttemptPlan.ItemHold> h){return new AttemptPlan(p.playerId(),p.sessionId(),p.quoteId(),p.configRevision(),p.catalogGeneration(),p.sourceSlot(),p.source(),p.targetId(),p.target(),p.sourceTotal(),p.targetTotal(),p.terms(),h,p.expiresAt());}
    private static void codecTests(){
        test("codec.plan-round-trip-and-canonical-digest",()->{
            var p=plan(true,true);var bytes=JournalCodec.encodePlan(p);var copy=JournalCodec.decodePlan(bytes);
            eq(JournalCodec.planDigest(p),JournalCodec.planDigest(copy));eq(p.source().facts(),copy.source().facts());check(Arrays.equals(p.source().bytes(),copy.source().bytes()));
        });
        test("codec.record-round-trip",()->{
            var record=AttemptRecord.initial(plan(),NOW);byte[] bytes=JournalCodec.encodeRecord(record);check(Arrays.equals(bytes,JournalCodec.encodeRecord(JournalCodec.decodeRecord(bytes))));
        });
        test("codec.reject-truncated-and-trailing",()->{
            byte[] bytes=JournalCodec.encodePlan(plan());rejects(()->JournalCodec.decodePlan(Arrays.copyOf(bytes,bytes.length-1)));
            rejects(()->JournalCodec.decodePlan(Arrays.copyOf(bytes,bytes.length+1)));rejects(()->JournalCodec.decodeRecord(bytes));
        });
        test("codec.reject-size-before-reading",()->{rejects(()->JournalCodec.decodePlan(new byte[JournalCodec.MAX_BYTES+1]));rejects(()->JournalCodec.decodePlan(new byte[0]));});
        test("codec.500-corrupted-bits-rejected",()->{
            byte[] original=JournalCodec.encodePlan(plan(true,true));var random=new Random(44);
            for(int i=0;i<500;i++){byte[] data=original.clone();int at=random.nextInt(data.length);data[at]^=(byte)(1<<random.nextInt(8));rejects(()->JournalCodec.decodePlan(data));}
        });
        test("codec.no-record-content-aliasing",()->{
            byte[] data=JournalCodec.encodeRecord(AttemptRecord.initial(plan(),NOW));var record=JournalCodec.decodeRecord(data);Arrays.fill(data,(byte)0);
            eq(plan().source().fingerprint(),record.plan().source().fingerprint());
        });
        test("codec.amount-and-metadata-affect-plan-digest",()->{
            var a=plan();var b=probability(a,90_000_001);check(!JournalCodec.planDigest(a).equals(JournalCodec.planDigest(b)));eq(a.attemptId(),b.attemptId());
        });
    }
    private static void scriptTests(){
        test("script.reserves-source-before-costs-and-ledger-first",()->{
            var script=EffectScript.reservation(plan(true,false));eq(Effect.Kind.LEDGER_CLAIM,script.get(0).kind());eq(Effect.Kind.HOLD_SOURCE,script.get(1).kind());
            eq(7,script.size());eq(Effect.Kind.DEBIT_CURRENCY,script.getLast().kind());
        });
        test("script.win-refunds-only-unused-branches",()->{
            var p=plan(true,false);var s=EffectScript.settlement(p,true);
            eq(d("11"),amount(s,Effect.Kind.REFUND_CURRENCY));eq(d("3"),amount(s,Effect.Kind.RETURN_FEE_ITEM));
            check(s.stream().anyMatch(e->e.kind()==Effect.Kind.DELIVER_TARGET));check(s.stream().noneMatch(e->e.kind()==Effect.Kind.RETURN_SOURCE));
        });
        test("script.loss-keep-returns-source-not-target",()->{
            var s=EffectScript.settlement(plan(true,true),false);eq(BigDecimal.ZERO,amount(s,Effect.Kind.REFUND_CURRENCY));
            check(s.contains(Effect.simple(Effect.Kind.RETURN_SOURCE)));check(s.stream().noneMatch(e->e.kind()==Effect.Kind.DELIVER_TARGET));eq(Effect.Kind.LEDGER_SUCCESS,s.getLast().kind());
        });
        test("script.loss-destroy-never-returns-source",()->check(EffectScript.settlement(plan(true,false),false).stream().noneMatch(e->e.kind()==Effect.Kind.RETURN_SOURCE)));
        test("script.no-fresh-item-template-needed-on-recovery",()->{
            var p=plan(true,true);var copy=JournalCodec.decodePlan(JournalCodec.encodePlan(p));eq(EffectScript.settlement(p,false),EffectScript.settlement(copy,false));
        });
    }
    private static BigDecimal amount(List<Effect> effects,Effect.Kind kind){return effects.stream().filter(e->e.kind()==kind).map(Effect::amount).reduce(BigDecimal.ZERO,BigDecimal::add).stripTrailingZeros();}
    private static void machineTests(){
        test("machine.no-effect-before-durable-intent",()->{
            var m=new TransactionMachine();var record=AttemptRecord.initial(plan(),NOW);var started=m.next(record,NOW);
            eq(TransactionMachine.Work.NONE,started.work());eq(AttemptRecord.State.RESERVING,started.next().state());
            var intent=m.next(started.next(),NOW);eq(TransactionMachine.Work.EFFECT,intent.work());check(TransactionMachine.pending(intent.next()));check(m.next(intent.next(),NOW).stopped());
        });
        test("machine.stale-wrong-ordinal-receipt-rejected",()->{
            var m=new TransactionMachine();var r=m.next(m.next(AttemptRecord.initial(plan(),NOW),NOW).next(),NOW).next();
            rejects(()->m.receipt(r,1,new EffectReceipt(EffectReceipt.Status.APPLIED,"receipt"),NOW));
            rejects(()->m.receipt(r,0,EffectReceipt.intent(),NOW));
        });
        test("machine.cannot-draw-from-prepared",()->rejects(()->new TransactionMachine().commitDraw(AttemptRecord.initial(plan(),NOW),0,NOW)));
        test("machine.cannot-skip-to-completed",()->{
            var r=AttemptRecord.initial(plan(),NOW);rejects(()->JournalCodec.checkTransition(r,new AttemptRecord(r.plan(),AttemptRecord.State.COMPLETED,1,List.of(),0L,NOW,NOW,"")));
        });
        test("machine.expired-prepared-aborts-no-effects",()->{
            var d=new TransactionMachine().next(AttemptRecord.initial(plan(),NOW),NOW.plusSeconds(30));eq(AttemptRecord.State.ABORTED,d.next().state());eq(0,d.next().steps().size());
        });
        test("machine.time-rollback-never-rewinds-journal-time",()->{
            var r=AttemptRecord.initial(plan(),NOW);var next=new TransactionMachine().next(r,NOW.minusSeconds(10)).next();eq(NOW,next.updatedAt());
        });
        test("machine.no-change-to-pinned-plan",()->{
            var m=new TransactionMachine();var r=AttemptRecord.initial(plan(),NOW);var other=m.next(AttemptRecord.initial(probability(plan(),10),NOW),NOW).next();rejects(()->JournalCodec.checkTransition(r,other));
        });
    }
    private static void driverTests(){
        test("driver.success-exactly-one-draw-and-delivery",()->{
            var j=new SimulationJournal();var port=new Port();var rng=new CountingRandom(0);var r=run(engine(j,port,rng,Runnable::run),plan());
            eq(UpgradeTransactionEngine.Status.COMPLETED,r.status());check(r.record().orElseThrow().successfulRoll());eq(1,rng.calls.get());eq(1L,port.count(Effect.Kind.DELIVER_TARGET));check(j.activeAttempt(PLAYER).isEmpty());
        });
        test("driver.fail-destroy-no-reward",()->{
            var j=new SimulationJournal();var port=new Port();var r=run(engine(j,port,new CountingRandom(90_000_000),Runnable::run),plan());
            eq(UpgradeTransactionEngine.Status.COMPLETED,r.status());check(!r.record().orElseThrow().successfulRoll());eq(0L,port.count(Effect.Kind.DELIVER_TARGET));eq(0L,port.count(Effect.Kind.RETURN_SOURCE));
        });
        test("driver.fail-keep-consumes-costs-and-returns-source",()->{
            var j=new SimulationJournal();var port=new Port();var r=run(engine(j,port,new CountingRandom(999_999_999),Runnable::run),plan(true,true));
            eq(UpgradeTransactionEngine.Status.COMPLETED,r.status());eq(1L,port.count(Effect.Kind.RETURN_SOURCE));eq(0L,port.count(Effect.Kind.LEDGER_FAIL));
        });
        test("driver.expired-new-plan-never-acquires-lock",()->{
            var j=new SimulationJournal();var port=new Port();var e=new UpgradeTransactionEngine(AsyncTransactionJournal.offload(j,Runnable::run),port,()->0,Runnable::run,Clock.fixed(NOW.plusSeconds(30),ZoneOffset.UTC),16,(l,x)->{});
            eq(UpgradeTransactionEngine.Status.EXPIRED,run(e,plan()).status());check(j.activeAttempt(PLAYER).isEmpty());eq(0,port.calls.size());
        });
        test("driver.duplicate-completed-attempt-no-effects",()->{
            var j=new SimulationJournal();var port=new Port();var rng=new CountingRandom(0);var e=engine(j,port,rng,Runnable::run);run(e,plan());int effects=port.calls.size();
            eq(UpgradeTransactionEngine.Status.DUPLICATE,run(e,plan()).status());eq(effects,port.calls.size());eq(1,rng.calls.get());
        });
        test("driver.reused-quote-with-different-plan-conflicts",()->{
            var j=new SimulationJournal();var p=new Port();var e=engine(j,p,()->0,Runnable::run);run(e,plan());
            eq(UpgradeTransactionEngine.Status.IDEMPOTENCY_CONFLICT,run(e,probability(plan(),1)).status());
        });
        for(var kind:List.of(Effect.Kind.LEDGER_CLAIM,Effect.Kind.HOLD_SOURCE,Effect.Kind.HOLD_FEE_ITEM,Effect.Kind.DEBIT_CURRENCY)){
            test("driver.definite-rejection-compensates-"+kind,()->{
                var j=new SimulationJournal();var p=new Port();var rng=new CountingRandom(0);p.behavior=c->CompletableFuture.completedFuture(c.effect().kind()==kind?new EffectReceipt(EffectReceipt.Status.NOT_APPLIED,"rejected-no-effect"):Port.applied(c));
                var r=run(engine(j,p,rng,Runnable::run),plan(true,false));eq(UpgradeTransactionEngine.Status.ABORTED,r.status());eq(0,rng.calls.get());check(j.activeAttempt(PLAYER).isEmpty());
                eq(0L,p.count(Effect.Kind.DELIVER_TARGET));
            });
            test("driver.uncertain-freezes-without-refund-"+kind,()->{
                var j=new SimulationJournal();var p=new Port();var rng=new CountingRandom(0);p.behavior=c->CompletableFuture.completedFuture(c.effect().kind()==kind?EffectReceipt.unknown("provider-ambiguous"):Port.applied(c));
                var r=run(engine(j,p,rng,Runnable::run),plan(true,false));eq(UpgradeTransactionEngine.Status.RECONCILIATION_REQUIRED,r.status());eq(0,rng.calls.get());
                eq(0L,p.count(Effect.Kind.REFUND_CURRENCY));eq(0L,p.count(Effect.Kind.RETURN_SOURCE));check(j.activeAttempt(PLAYER).isPresent());
            });
        }
        test("driver.unknown-does-not-allow-new-session-to-bypass-owner",()->{
            var j=new SimulationJournal();var p=new Port();p.behavior=c->CompletableFuture.completedFuture(EffectReceipt.unknown("unknown"));var e=engine(j,p,()->0,Runnable::run);run(e,plan());
            eq(UpgradeTransactionEngine.Status.PLAYER_BUSY,run(e,identity(plan(),PLAYER,UUID.randomUUID())).status());
        });
        test("driver.sync-adapter-throw-becomes-unknown",()->{
            var j=new SimulationJournal();var p=new Port();p.behavior=c->{throw new IllegalStateException("adapter exception");};
            eq(UpgradeTransactionEngine.Status.RECONCILIATION_REQUIRED,run(engine(j,p,()->0,Runnable::run),plan()).status());
        });
        test("driver.async-adapter-failure-becomes-unknown",()->{
            var j=new SimulationJournal();var p=new Port();p.behavior=c->CompletableFuture.failedFuture(new IOException("lost provider ack"));
            eq(UpgradeTransactionEngine.Status.RECONCILIATION_REQUIRED,run(engine(j,p,()->0,Runnable::run),plan()).status());
        });
        test("driver.invalid-null-adapter-reply-freezes",()->{
            var j=new SimulationJournal();var p=new Port();p.behavior=c->CompletableFuture.completedFuture(null);
            eq(UpgradeTransactionEngine.Status.RECONCILIATION_REQUIRED,run(engine(j,p,()->0,Runnable::run),plan()).status());
        });
        for(var kind:List.of(Effect.Kind.REFUND_CURRENCY,Effect.Kind.RETURN_FEE_ITEM,Effect.Kind.DELIVER_TARGET,Effect.Kind.LEDGER_SUCCESS))
            test("driver.settlement-rejection-is-not-full-refund-"+kind,()->{
                var j=new SimulationJournal();var p=new Port();p.behavior=c->CompletableFuture.completedFuture(c.effect().kind()==kind?new EffectReceipt(EffectReceipt.Status.NOT_APPLIED,"rejected"):Port.applied(c));
                var r=run(engine(j,p,()->0,Runnable::run),plan(true,false));eq(UpgradeTransactionEngine.Status.RECONCILIATION_REQUIRED,r.status());
                check(r.record().orElseThrow().sample()!=null);eq(0L,p.count(Effect.Kind.RETURN_SOURCE));
            });
        test("driver.compensation-rejection-quarantines-owner",()->{
            var j=new SimulationJournal();var p=new Port();p.behavior=c->CompletableFuture.completedFuture(
                    c.effect().kind()==Effect.Kind.DEBIT_CURRENCY||c.effect().kind()==Effect.Kind.RETURN_FEE_ITEM?new EffectReceipt(EffectReceipt.Status.NOT_APPLIED,"rejected"):Port.applied(c));
            eq(UpgradeTransactionEngine.Status.RECONCILIATION_REQUIRED,run(engine(j,p,()->0,Runnable::run),plan(true,false)).status());check(j.activeAttempt(PLAYER).isPresent());
        });
        test("driver.storage-down-before-claim-no-effects",()->{
            var j=new SimulationJournal();j.available(false);var p=new Port();eq(UpgradeTransactionEngine.Status.STORAGE_OR_DRIVER_ERROR,run(engine(j,p,()->0,Runnable::run),plan()).status());eq(0,p.calls.size());
        });
        test("driver.intent-write-failure-never-dispatches-effect",()->{
            var j=new SimulationJournal();j.probes((old,next)->{if(TransactionMachine.pending(next))throw new IllegalStateException("write refused");},(a,b)->{});var p=new Port();
            eq(UpgradeTransactionEngine.Status.STORAGE_OR_DRIVER_ERROR,run(engine(j,p,()->0,Runnable::run),plan()).status());eq(0,p.calls.size());check(j.activeAttempt(PLAYER).isPresent());
        });
        test("driver.effect-applied-ack-write-failed-no-replay",()->{
            var j=new SimulationJournal();var p=new Port();j.probes((old,next)->{if(old!=null&&TransactionMachine.pending(old)&&!TransactionMachine.pending(next))throw new IllegalStateException("ack lost");},(a,b)->{});
            var e=engine(j,p,()->0,Runnable::run);eq(UpgradeTransactionEngine.Status.STORAGE_OR_DRIVER_ERROR,run(e,plan()).status());eq(1,p.calls.size());
            eq(UpgradeTransactionEngine.Status.DUPLICATE,run(e,plan()).status());eq(1,p.calls.size());eq(RecoveryPlanner.Action.VERIFY_EXTERNAL_EFFECT,new RecoveryPlanner().inspect(j.find(plan().attemptId()).orElseThrow()).action());
        });
        test("driver.outcome-write-failed-never-delivers-or-redraws",()->{
            var j=new SimulationJournal();var p=new Port();var rng=new CountingRandom(0);
            j.probes((old,next)->{if(next.state()==AttemptRecord.State.OUTCOME_COMMITTED)throw new IllegalStateException("outcome write refused");},(a,b)->{});
            eq(UpgradeTransactionEngine.Status.STORAGE_OR_DRIVER_ERROR,run(engine(j,p,rng,Runnable::run),plan()).status());eq(1,rng.calls.get());eq(0L,p.count(Effect.Kind.DELIVER_TARGET));
            var saved=j.find(plan().attemptId()).orElseThrow();eq(RecoveryPlanner.Action.REVIEW_UNCOMMITTED_DRAW,new RecoveryPlanner().inspect(saved).action());
        });
        test("driver.draw-out-of-range-quarantines",()->{
            var j=new SimulationJournal();eq(UpgradeTransactionEngine.Status.RECONCILIATION_REQUIRED,run(engine(j,new Port(),()->Probability.DENOMINATOR,Runnable::run),plan()).status());
        });
        test("driver.journal-cas-no-effect-on-lost-commit-ack",()->{
            var j=new SimulationJournal();var p=new Port();j.probes((a,b)->{},(old,next)->{if(TransactionMachine.pending(next))throw new IllegalStateException("intent committed but ack lost");});
            eq(UpgradeTransactionEngine.Status.STORAGE_OR_DRIVER_ERROR,run(engine(j,p,()->0,Runnable::run),plan()).status());eq(0,p.calls.size());check(TransactionMachine.pending(j.find(plan().attemptId()).orElseThrow()));
        });
    }
    private static void recoveryTests(){
        test("recovery.committed-outcome-resumes-with-no-second-draw",()->{
            var j=new SimulationJournal();var p=new Port();var rng=new CountingRandom(0);
            j.probes((a,b)->{},(old,next)->{if(next.state()==AttemptRecord.State.OUTCOME_COMMITTED)throw new IllegalStateException("lost commit reply");});
            run(engine(j,p,rng,Runnable::run),plan());var saved=j.find(plan().attemptId()).orElseThrow();eq(0L,saved.sample());
            var restored=SimulationJournal.restored(saved);var nextRng=new CountingRandom(999_999_999);var r=engine(restored,new Port(),nextRng,Runnable::run).resumeQuiescent(saved).toCompletableFuture().get(10,TimeUnit.SECONDS);
            eq(UpgradeTransactionEngine.Status.COMPLETED,r.status());check(r.record().orElseThrow().successfulRoll());eq(0,nextRng.calls.get());
        });
        test("recovery.cannot-repeat-pending-delivery",()->{
            var j=new SimulationJournal();var p=new Port();p.behavior=c->{if(c.effect().kind()==Effect.Kind.DELIVER_TARGET)throw new IllegalStateException("unknown delivery");return CompletableFuture.completedFuture(Port.applied(c));};
            run(engine(j,p,()->0,Runnable::run),plan());var saved=j.find(plan().attemptId()).orElseThrow();var next=new Port();
            eq(UpgradeTransactionEngine.Status.RECONCILIATION_REQUIRED,engine(SimulationJournal.restored(saved),next,()->1,Runnable::run).resumeQuiescent(saved).toCompletableFuture().get().status());eq(0,next.calls.size());
        });
        test("recovery.stale-snapshot-cannot-take-new-owner",()->{
            var j=new SimulationJournal();var p=plan();var old=j.claim(p,NOW).record().orElseThrow();j.compareAndSet(old,new TransactionMachine().next(old,NOW).next());
            eq(UpgradeTransactionEngine.Status.CONFLICT,engine(j,new Port(),()->0,Runnable::run).resumeQuiescent(old).toCompletableFuture().get().status());
        });
        test("recovery.all-durable-transition-boundaries",()->{
            var original=new SimulationJournal();run(engine(original,new Port(),()->0,Runnable::run),plan(true,true));
            for(byte[] bytes:original.history()) {
                var saved=JournalCodec.decodeRecord(bytes);var restored=SimulationJournal.restored(saved);var rng=new CountingRandom(0);var p=new Port();
                var expected=new RecoveryPlanner().inspect(saved).action();var r=engine(restored,p,rng,Runnable::run).resumeQuiescent(saved).toCompletableFuture().get(10,TimeUnit.SECONDS);
                if(expected==RecoveryPlanner.Action.RESUME_WITH_SAME_PLAN){eq(UpgradeTransactionEngine.Status.COMPLETED,r.status());if(saved.sample()!=null)eq(0,rng.calls.get());}
                else if(expected==RecoveryPlanner.Action.NONE){eq(UpgradeTransactionEngine.Status.COMPLETED,r.status());eq(0,p.calls.size());eq(0,rng.calls.get());}
                else{eq(UpgradeTransactionEngine.Status.RECONCILIATION_REQUIRED,r.status());eq(0,p.calls.size());eq(0,rng.calls.get());}
                for(var call:p.calls)check(call.ordinal()>=saved.steps().size());
            }
        });
        test("recovery.pages-use-uuid-text-cursor-not-offset",()->{
            var j=new SimulationJournal();List<String> expected=new ArrayList<>();
            for(int i=1;i<=25;i++){var p=identity(plan(),new UUID(100,i),new UUID(-100,i));j.claim(p,NOW);expected.add(p.attemptId().toString());}
            Collections.sort(expected);List<String> actual=new ArrayList<>();Optional<UUID> cursor=Optional.empty();
            for(int i=0;i<10;i++){var page=j.unfinished(cursor,4);if(page.isEmpty())break;actual.addAll(page.stream().map(r->r.id().toString()).toList());cursor=Optional.of(page.getLast().id());}
            eq(expected,actual);rejects(()->j.unfinished(Optional.empty(),9));
        });
    }
    private static void gateTests(){
        test("gate.identity-safe-release",()->{
            var g=new AttemptRunGate(1);var a=g.enter(PLAYER,QUOTE).ticket().orElseThrow();g.leave(a);var b=g.enter(PLAYER,QUOTE).ticket().orElseThrow();g.leave(a);eq(1,g.inFlight());g.leave(b);eq(0,g.inFlight());
        });
        test("gate.stop-waits-without-expiring-native-work",()->{
            var g=new AttemptRunGate(1);var t=g.enter(PLAYER,QUOTE).ticket().orElseThrow();var stop=g.stop().toCompletableFuture();check(!stop.isDone());eq(AttemptRunGate.Status.STOPPED,g.enter(UUID.randomUUID(),QUOTE).status());g.leave(t);check(stop.isDone());
        });
        test("gate.caller-cancel-does-not-cancel-original-effect",()->{
            var j=new SimulationJournal();var port=new Port();var held=new CompletableFuture<EffectReceipt>();port.behavior=c->held;var e=engine(j,port,()->0,Runnable::run);
            var returned=e.submit(plan()).toCompletableFuture();check(returned.cancel(true));eq(1,e.inFlight());check(!held.isCancelled());
            eq(UpgradeTransactionEngine.Status.PLAYER_BUSY,run(e,plan()).status());var stopped=e.stop().toCompletableFuture();check(!stopped.isDone());held.complete(new EffectReceipt(EffectReceipt.Status.NOT_APPLIED,"quiescent-no-effect"));check(stopped.isDone());eq(0,e.inFlight());
        });
        test("gate.capacity-and-other-player-do-not-overwrite",()->{
            var j=new SimulationJournal();var p=new Port();p.behavior=c->new CompletableFuture<>();
            var e=new UpgradeTransactionEngine(AsyncTransactionJournal.offload(j,Runnable::run),p,()->0,Runnable::run,CLOCK,1,(l,x)->{});e.submit(plan());
            eq(UpgradeTransactionEngine.Status.CAPACITY,run(e,identity(plan(),UUID.randomUUID(),UUID.randomUUID())).status());eq(1,e.inFlight());
        });
        test("gate.rejected-executor-releases-admission",()->{
            Executor reject=r->{throw new RejectedExecutionException("full");};var e=engine(new SimulationJournal(),new Port(),()->0,reject);
            eq(UpgradeTransactionEngine.Status.STORAGE_OR_DRIVER_ERROR,run(e,plan()).status());eq(0,e.inFlight());
        });
        test("gate.diagnostics-throw-never-hangs-returned-future",()->{
            var j=new SimulationJournal();j.available(false);var e=new UpgradeTransactionEngine(AsyncTransactionJournal.offload(j,Runnable::run),new Port(),()->0,Runnable::run,CLOCK,1,(l,x)->{throw new IllegalStateException("logger broken");});
            try{run(e,plan());throw new AssertionError("expected exceptional completion");}catch(ExecutionException expected){check(expected.getCause() instanceof IllegalStateException);}eq(0,e.inFlight());
        });
        test("concurrency.128-duplicate-submits-one-effect-chain",()->{
            try(var pool=Executors.newFixedThreadPool(4)){
                var j=new SimulationJournal();var port=new Port();var rng=new CountingRandom(0);var e=engine(j,port,rng,pool);
                List<CompletableFuture<UpgradeTransactionEngine.Result>> futures=new ArrayList<>();for(int i=0;i<128;i++)futures.add(e.submit(plan()).toCompletableFuture());
                for(var future:futures){var status=future.get(15,TimeUnit.SECONDS).status();check(status==UpgradeTransactionEngine.Status.COMPLETED||status==UpgradeTransactionEngine.Status.PLAYER_BUSY||status==UpgradeTransactionEngine.Status.DUPLICATE);}
                eq(1,rng.calls.get());eq(1L,port.count(Effect.Kind.DELIVER_TARGET));e.stop().toCompletableFuture().get(10,TimeUnit.SECONDS);
            }
        });
    }

    private static void hardeningTests(){
        test("codec.state-envelope-is-bound-to-exact-plan",()->{
            var r=AttemptRecord.initial(plan(),NOW);var state=JournalCodec.encodeState(r);
            check(Arrays.equals(JournalCodec.encodeRecord(r),JournalCodec.encodeRecord(JournalCodec.decodeState(r.plan(),state))));
            rejects(()->JournalCodec.decodeState(probability(r.plan(),1),state));
            rejects(()->JournalCodec.decodeState(identity(r.plan(),PLAYER,UUID.randomUUID()),state));
        });
        test("codec.state-size-does-not-scale-with-item-payload",()->{
            var p=plan();byte[] payload=new byte[512*1024];Arrays.fill(payload,(byte)8);
            var big=new AttemptPlan(p.playerId(),p.sessionId(),p.quoteId(),p.configRevision(),p.catalogGeneration(),p.sourceSlot(),
                    new ItemSnapshot(p.source().facts(),payload),p.targetId(),p.target(),p.sourceTotal(),p.targetTotal(),p.terms(),p.feeItems(),p.expiresAt());
            int smallState=JournalCodec.encodeState(AttemptRecord.initial(p,NOW)).length;
            int largeState=JournalCodec.encodeState(AttemptRecord.initial(big,NOW)).length;
            eq(smallState,largeState);check(largeState<1024);check(JournalCodec.encodePlan(big).length>512*1024);
        });
        test("codec.state-hard-bound-and-type-rejection",()->{
            rejects(()->JournalCodec.decodeState(plan(),new byte[JournalCodec.MAX_STATE_BYTES+1]));
            rejects(()->JournalCodec.decodeState(plan(),JournalCodec.encodePlan(plan())));
        });
        test("driver.terminal-write-failure-recovery-does-not-redeliver",()->{
            var j=new SimulationJournal();var port=new Port();var rng=new CountingRandom(0);
            j.probes((old,next)->{if(next.state()==AttemptRecord.State.COMPLETED)throw new IllegalStateException("terminal write failure");},(a,b)->{});
            eq(UpgradeTransactionEngine.Status.STORAGE_OR_DRIVER_ERROR,run(engine(j,port,rng,Runnable::run),plan()).status());eq(1L,port.count(Effect.Kind.DELIVER_TARGET));
            var saved=j.find(plan().attemptId()).orElseThrow();var freshPort=new Port();var freshRng=new CountingRandom(99);
            var restored=SimulationJournal.restored(saved);
            eq(UpgradeTransactionEngine.Status.COMPLETED,engine(restored,freshPort,freshRng,Runnable::run).resumeQuiescent(saved).toCompletableFuture().get().status());
            eq(0,freshPort.calls.size());eq(0,freshRng.calls.get());check(restored.activeAttempt(PLAYER).isEmpty());
        });
        test("driver.terminal-commit-ack-lost-still-deduplicates",()->{
            var j=new SimulationJournal();var port=new Port();
            j.probes((a,b)->{},(old,next)->{if(next.state()==AttemptRecord.State.COMPLETED)throw new IllegalStateException("terminal commit reply lost");});
            var e=engine(j,port,()->0,Runnable::run);eq(UpgradeTransactionEngine.Status.STORAGE_OR_DRIVER_ERROR,run(e,plan()).status());
            check(j.activeAttempt(PLAYER).isEmpty());eq(UpgradeTransactionEngine.Status.DUPLICATE,run(e,plan()).status());eq(1L,port.count(Effect.Kind.DELIVER_TARGET));
        });
        test("driver.expired-duplicate-remains-a-duplicate",()->{
            var j=new SimulationJournal();run(engine(j,new Port(),()->0,Runnable::run),plan());
            eq(TransactionJournal.ClaimStatus.DUPLICATE,j.claim(plan(),NOW.plusSeconds(600)).status());
        });
        test("recovery.compensation-crash-boundaries-never-reroll",()->{
            var original=new SimulationJournal();var reject=new Port();reject.behavior=c->CompletableFuture.completedFuture(c.effect().kind()==Effect.Kind.DEBIT_CURRENCY
                    ?new EffectReceipt(EffectReceipt.Status.NOT_APPLIED,"no-debit"):Port.applied(c));
            run(engine(original,reject,()->0,Runnable::run),plan(true,false));
            for(byte[] bytes:original.history()){
                var saved=JournalCodec.decodeRecord(bytes);
                if(saved.state()!=AttemptRecord.State.COMPENSATING&&saved.state()!=AttemptRecord.State.ABORTED)continue;
                var port=new Port();var rng=new CountingRandom(0);var restored=SimulationJournal.restored(saved);
                var result=engine(restored,port,rng,Runnable::run).resumeQuiescent(saved).toCompletableFuture().get();
                eq(TransactionMachine.pending(saved)?UpgradeTransactionEngine.Status.RECONCILIATION_REQUIRED:UpgradeTransactionEngine.Status.ABORTED,result.status());
                eq(0,rng.calls.get());eq(0L,port.count(Effect.Kind.DELIVER_TARGET));
                for(var call:port.calls)check(call.ordinal()>=saved.steps().size());
            }
        });
        test("lifecycle.stop-between-intent-commit-and-effect-dispatch",()->{
            var j=new SimulationJournal();var base=AsyncTransactionJournal.offload(j,Runnable::run);var commitAck=new CompletableFuture<Boolean>();
            AsyncTransactionJournal controlled=new AsyncTransactionJournal(){
                public CompletionStage<TransactionJournal.Claim> claim(AttemptPlan p,Instant now){return base.claim(p,now);}
                public CompletionStage<Optional<AttemptRecord>> find(UUID id){return base.find(id);}
                public CompletionStage<List<AttemptRecord>> unfinished(Optional<UUID> after,int limit){return base.unfinished(after,limit);}
                public CompletionStage<Optional<UUID>> activeAttempt(UUID player){return base.activeAttempt(player);}
                public CompletionStage<Boolean> compareAndSet(AttemptRecord old,AttemptRecord next){
                    var saved=base.compareAndSet(old,next);
                    return TransactionMachine.pending(next)?commitAck:saved;
                }
            };
            var port=new Port();var e=new UpgradeTransactionEngine(controlled,port,()->0,Runnable::run,CLOCK,16,(l,x)->{});
            var result=e.submit(plan()).toCompletableFuture();eq(0,port.calls.size());check(TransactionMachine.pending(j.find(plan().attemptId()).orElseThrow()));
            var stopped=e.stop().toCompletableFuture();check(!stopped.isDone());commitAck.complete(true);
            eq(UpgradeTransactionEngine.Status.STOPPED,result.get().status());check(stopped.isDone());eq(0,port.calls.size());
            var saved=j.find(plan().attemptId()).orElseThrow();check(!TransactionMachine.pending(saved));
            eq(UpgradeTransactionEngine.Status.ABORTED,engine(j,port,()->0,Runnable::run).resumeQuiescent(saved).toCompletableFuture().get().status());eq(0,port.calls.size());
        });
        test("concurrency.two-engine-instances-one-durable-claim",()->{
            try(var pool=Executors.newFixedThreadPool(4)){
                var j=new SimulationJournal();var port=new Port();var rng=new CountingRandom(0);
                var one=engine(j,port,rng,pool);var two=engine(j,port,rng,pool);
                List<CompletableFuture<UpgradeTransactionEngine.Result>> futures=new ArrayList<>();
                for(int i=0;i<64;i++)futures.add((i%2==0?one:two).submit(plan()).toCompletableFuture());
                for(var future:futures){var status=future.get(15,TimeUnit.SECONDS).status();check(status==UpgradeTransactionEngine.Status.COMPLETED||status==UpgradeTransactionEngine.Status.DUPLICATE||status==UpgradeTransactionEngine.Status.PLAYER_BUSY);}
                eq(1,rng.calls.get());eq(1L,port.count(Effect.Kind.DELIVER_TARGET));
                one.stop().toCompletableFuture().get();two.stop().toCompletableFuture().get();
            }
        });
    }
    private static void propertyTests(){
        test("invariant.1000-settlement-conservation-cases",()->{
            var random=new Random(88);
            for(int i=0;i<1000;i++){
                int a=random.nextInt(10),s=random.nextInt(15),f=random.nextInt(15);if(a+s+f==0)a=1;
                var cost=new CostPlan(List.of(new CostPlan.Line(VAULT,BigDecimal.valueOf(a),BigDecimal.valueOf(s),BigDecimal.valueOf(f))));
                var p=plan();var terms=new UpgradeQuote.Terms("standard",List.of(),List.of(),p.terms().probability(),p.terms().failure(),cost);
                p=new AttemptPlan(p.playerId(),p.sessionId(),p.quoteId(),p.configRevision(),p.catalogGeneration(),p.sourceSlot(),p.source(),p.targetId(),p.target(),p.sourceTotal(),p.targetTotal(),terms,List.of(),p.expiresAt());
                for(boolean success:new boolean[]{false,true}){
                    int consume=a+(success?s:f);int reserve=a+Math.max(s,f);BigDecimal refund=amount(EffectScript.settlement(p,success),Effect.Kind.REFUND_CURRENCY);
                    check(refund.compareTo(BigDecimal.valueOf(reserve-consume))==0);check(refund.signum()>=0);
                }
            }
        });
        test("invariant.all-probability-boundaries",()->{
            for(long chance:new long[]{0,1,90_000_000,999_999_999,1_000_000_000})for(long sample:new long[]{0,1,89_999_999,90_000_000,999_999_999}){
                var j=new SimulationJournal();var p=probability(plan(),chance);var result=run(engine(j,new Port(),()->sample,Runnable::run),p);
                eq(sample<chance,result.record().orElseThrow().successfulRoll());
            }
        });
        test("invariant.secure-ticket-range-smoke-4096",()->{
            var rng=new SecureTicketSource();Set<Long> observed=new HashSet<>();for(int i=0;i<4096;i++){long v=rng.draw();check(v>=0&&v<Probability.DENOMINATOR);observed.add(v);}check(observed.size()>4000);
            // This is NOT a statistical/cryptographic proof. Uniform bounded generation relies on JDK SecureRandom contract.
        });
        test("invariant.applied-effects-always-have-earlier-persisted-intent",()->{
            var j=new SimulationJournal();var p=new Port();p.behavior=call->{var row=j.find(call.intent().id()).orElseThrow();eq(call.intent().version(),row.version());check(TransactionMachine.pending(row));return CompletableFuture.completedFuture(Port.applied(call));};
            run(engine(j,p,()->0,Runnable::run),plan(true,false));check(p.calls.size()>5);
        });
        test("invariant.duplicate-cas-one-winner",()->{
            var j=new SimulationJournal();var old=j.claim(plan(),NOW).record().orElseThrow();var next=new TransactionMachine().next(old,NOW).next();
            try(var pool=Executors.newFixedThreadPool(8)){
                List<Future<Boolean>> results=new ArrayList<>();for(int i=0;i<64;i++)results.add(pool.submit(()->j.compareAndSet(old,next)));
                int wins=0;for(var f:results)if(f.get())wins++;eq(1,wins);eq(1L,j.find(plan().attemptId()).orElseThrow().version());
            }
        });
    }
}
