package vn.ledat.itemupgrader.test;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import vn.ledat.itemupgrader.item.*;
import vn.ledat.itemupgrader.chance.*;
import vn.ledat.itemupgrader.cost.*;
import vn.ledat.itemupgrader.profile.*;
import vn.ledat.itemupgrader.quote.*;
import vn.ledat.itemupgrader.transaction.*;
import vn.ledat.itemupgrader.transaction.model.*;
import vn.ledat.itemupgrader.transaction.storage.*;
import vn.ledat.itemupgrader.demo.support.SimulationJournal;

final class TransactionFixtures {
    static final Instant NOW=Instant.parse("2026-09-16T00:00:00Z");
    static final UUID PLAYER=UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID SESSION=UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final UUID QUOTE=UUID.fromString("00000000-0000-0000-0000-000000000003");
    static final UUID GEN=UUID.fromString("00000000-0000-0000-0000-000000000004");
    static final Clock CLOCK=Clock.fixed(NOW,ZoneOffset.UTC);
    static final CostResource VAULT=CostResource.currency(CostResource.Currency.VAULT);
    static final CostResource POINTS=CostResource.currency(CostResource.Currency.PLAYERPOINTS);
    static final CostResource EMERALD=CostResource.item(ItemKey.of("minecraft:emerald"));
    static final CostResource PAPER=CostResource.item(ItemKey.of("minecraft:paper"));
    private TransactionFixtures(){}
    static BigDecimal d(String v){return new BigDecimal(v);}
    static ItemSnapshot item(String key,int amount){return new ItemSnapshot(ItemFacts.clean(ItemKey.of(key),amount),(key+":"+amount).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    static AttemptPlan plan(){return plan(false,false);}
    static AttemptPlan plan(boolean rich,boolean keep){
        var costs=rich?new CostPlan(List.of(
                new CostPlan.Line(VAULT,d("10"),d("20"),d("30")),
                new CostPlan.Line(POINTS,d("2"),d("0"),d("1")),
                new CostPlan.Line(EMERALD,d("1"),d("2"),d("4")),
                new CostPlan.Line(PAPER,d("0"),d("0"),d("1")))):new CostPlan(List.of());
        List<AttemptPlan.ItemHold> holds=rich?List.of(new AttemptPlan.ItemHold(1,3,item("minecraft:emerald",8)),
                new AttemptPlan.ItemHold(2,2,item("minecraft:emerald",2)),new AttemptPlan.ItemHold(3,1,item("minecraft:paper",1))):List.of();
        return new AttemptPlan(PLAYER,SESSION,QUOTE,7,GEN,0,item("minecraft:iron_ingot",1),"diamond",item("minecraft:diamond",1),
                d("90"),d("900"),new UpgradeQuote.Terms("standard",List.of(),List.of(),new Probability(90_000_000L),
                keep?RiskProfile.FailureMode.KEEP:RiskProfile.FailureMode.DESTROY,costs),holds,NOW.plusSeconds(30));
    }
    static AttemptPlan identity(AttemptPlan p,UUID player,UUID quote){return new AttemptPlan(player,p.sessionId(),quote,p.configRevision(),p.catalogGeneration(),p.sourceSlot(),p.source(),p.targetId(),p.target(),p.sourceTotal(),p.targetTotal(),p.terms(),p.feeItems(),p.expiresAt());}
    static AttemptPlan probability(AttemptPlan p,long tickets){
        var t=p.terms();return new AttemptPlan(p.playerId(),p.sessionId(),p.quoteId(),p.configRevision(),p.catalogGeneration(),p.sourceSlot(),p.source(),p.targetId(),p.target(),p.sourceTotal(),p.targetTotal(),new UpgradeQuote.Terms(t.profileId(),t.boosts(),t.permissionBonuses(),new Probability(tickets),t.failure(),t.costs()),p.feeItems(),p.expiresAt());
    }
    static UpgradeTransactionEngine engine(SimulationJournal journal,EffectPort port,TicketSource random,Executor worker){
        return new UpgradeTransactionEngine(AsyncTransactionJournal.offload(journal,worker),port,random,worker,CLOCK,16,(label,error)->{});
    }
    static UpgradeTransactionEngine.Result run(UpgradeTransactionEngine engine,AttemptPlan p)throws Exception{return engine.submit(p).toCompletableFuture().get(10,TimeUnit.SECONDS);}
    static final class Port implements EffectPort {
        final List<Call> calls=Collections.synchronizedList(new ArrayList<>());
        final Map<String,String> bindings=new ConcurrentHashMap<>();
        java.util.function.Function<Call,CompletionStage<EffectReceipt>> behavior=c->CompletableFuture.completedFuture(applied(c));
        @Override public CompletionStage<EffectReceipt> execute(Call call){
            calls.add(call);String prior=bindings.putIfAbsent(call.operationKey(),call.planDigest());
            if(prior!=null&&!prior.equals(call.planDigest()))throw new IllegalStateException("operation digest rebound");
            return behavior.apply(call);
        }
        long count(Effect.Kind kind){return calls.stream().filter(c->c.effect().kind()==kind).count();}
        static EffectReceipt applied(Call c){return new EffectReceipt(EffectReceipt.Status.APPLIED,"simulation:"+c.ordinal());}
    }
    static final class CountingRandom implements TicketSource {
        final AtomicInteger calls=new AtomicInteger();final long sample;
        CountingRandom(long sample){this.sample=sample;}
        @Override public long draw(){calls.incrementAndGet();return sample;}
    }
}
