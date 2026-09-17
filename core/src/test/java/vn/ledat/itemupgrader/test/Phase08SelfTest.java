package vn.ledat.itemupgrader.test;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import vn.ledat.itemupgrader.demo.*;
import vn.ledat.itemupgrader.pity.*;
import vn.ledat.itemupgrader.history.*;
import vn.ledat.itemupgrader.history.storage.*;
import vn.ledat.itemupgrader.chance.*;
import vn.ledat.itemupgrader.cost.*;
import vn.ledat.itemupgrader.quote.*;
import vn.ledat.itemupgrader.profile.*;
import vn.ledat.itemupgrader.item.*;
import vn.ledat.itemupgrader.catalog.*;
import vn.ledat.itemupgrader.transaction.*;
import vn.ledat.itemupgrader.transaction.model.*;
import vn.ledat.itemupgrader.transaction.storage.*;
import vn.ledat.itemupgrader.output.*;
import vn.ledat.itemupgrader.failure.*;
import vn.ledat.itemupgrader.transfer.*;
import vn.ledat.itemupgrader.gui.*;

/** Runs real dependency-free core. Native Paper/Platform, provider effects and driver integration are NOT mocked as production. */
public final class Phase08SelfTest {
    @FunctionalInterface interface Checked {void run()throws Exception;}
    private static final Map<String,Checked> TESTS=new LinkedHashMap<>();
    private static int assertions;
    private static final UUID PLAYER=ProgressDemo.PLAYER,SESSION=ProgressDemo.SESSION,OTHER=new UUID(0,99);
    private static final Instant NOW=ProgressDemo.NOW;
    private static final String SCOPE=ProgressFixtures.SCOPE;
    private Phase08SelfTest(){}
    public static void main(String[] args)throws Exception {
        pityModels();pityQuotes();scopeTests();codecTests();historyTests();statisticsTests();sessionTests();configurationTests();
        int failures=0;var xml=new StringBuilder();
        for(var e:TESTS.entrySet()) {
            String failure=null;long start=System.nanoTime();
            try{e.getValue().run();}catch(Exception|AssertionError error){failure=error.toString();failures++;error.printStackTrace(System.err);}
            System.out.println((failure==null?"PASS ":"FAIL ")+e.getKey());
            xml.append("  <testcase classname=\"Phase08\" name=\"").append(escape(e.getKey())).append("\" time=\"")
                .append(String.format(Locale.ROOT,"%.6f",(System.nanoTime()-start)/1e9)).append("\">");
            if(failure!=null)xml.append("<failure message=\"").append(escape(failure)).append("\"/>");xml.append("</testcase>\n");
        }
        Path report=Path.of(args.length==0?"core/build/reports/phase08-self-test.xml":args[0]);Files.createDirectories(report.toAbsolutePath().getParent());
        Files.writeString(report,"<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuite name=\"ItemUpgraderPhase08\" tests=\""+TESTS.size()+"\" failures=\""+failures
            +"\" errors=\"0\">\n"+xml+"<system-out>assertions="+assertions+"; detached core; not native or JDBC driver integration</system-out>\n</testsuite>\n",StandardCharsets.UTF_8);
        System.out.println("RESULT tests="+TESTS.size()+" assertions="+assertions+" failures="+failures);
        if(failures!=0)throw new AssertionError("Phase08 failures="+failures);
    }
    private static void test(String id,Checked body){if(TESTS.put(id,body)!=null)throw new IllegalArgumentException("duplicate");}
    private static void check(boolean value){assertions++;if(!value)throw new AssertionError("expected true");}
    private static void eq(Object expected,Object actual){assertions++;if(!Objects.equals(expected,actual))throw new AssertionError("expected "+expected+", got "+actual);}
    private static void decimal(String expected,BigDecimal actual){assertions++;if(new BigDecimal(expected).compareTo(actual)!=0)throw new AssertionError("expected decimal "+expected+", got "+actual);}
    private static void rejects(Checked body)throws Exception {assertions++;try{body.run();}catch(IllegalArgumentException|IllegalStateException|IOException|java.sql.SQLException expected){return;}throw new AssertionError("expected failure");}
    private static String escape(String s){return s.replace("&","&amp;").replace("<","&lt;").replace("\"","&quot;");}
    private static PityPolicy policy(){return ProgressDemo.fixture().policies().policies().getFirst();}
    private static PityStamp stamp(long version,long failures){return new PityStamp("diamond_progress",1,SCOPE,version,failures,new BigDecimal("1.25"),new BigDecimal("10"));}
    private static QuoteResult quote(ProgressDemo.Fixture f,PityPolicies policies,PityQuoteService.Reader reader) {
        return new PityQuoteService().quote(f.request(),f.source(),f.index(),f.access(),f.rules(),f.resources(),NOW,policies,reader);
    }
    private static UpgradeQuoteService.Validation validate(ProgressDemo.Fixture f,UpgradeQuote q,PityQuoteService.Reader reader,Instant now) {
        return new PityQuoteService().revalidate(q,SESSION,f.source(),f.index(),f.access(),f.rules(),f.resources(),now,f.policies(),reader);
    }
    private static void pityModels() {
        test("pity.default-disabled-explicit-allowlists",()->{check(!PityPolicies.disabled().enabled());check(PityPolicies.disabled().find("x","y","z").isEmpty());});
        test("pity.counter-loss-win-and-version",()->{var s=PitySnapshot.empty(PLAYER,SCOPE);s=s.complete(false);eq(1L,s.version());eq(1L,s.failures());s=s.complete(true);eq(2L,s.version());eq(0L,s.failures());});
        test("pity.counter-bounds",()->{for(long v:List.of(-1L,PitySnapshot.MAX_COUNTER+1))rejects(()->new PitySnapshot(PLAYER,SCOPE,v,0));rejects(()->new PitySnapshot(PLAYER,SCOPE,2,3));rejects(()->new PitySnapshot(PLAYER,SCOPE,2,-1));rejects(()->new PitySnapshot(PLAYER,SCOPE,PitySnapshot.MAX_COUNTER,0).complete(true));});
        test("pity.scope-is-exact-lowercase-hash",()->{for(String s:List.of("","a".repeat(63),"a".repeat(65),"A".repeat(64),"../x","<red>"))rejects(()->PitySnapshot.empty(PLAYER,s));});
        test("pity.increment-cap-not-multiplier",()->{decimal("0",stamp(0,0).bonusPoints());decimal("1.25",stamp(1,1).bonusPoints());decimal("5",stamp(4,4).bonusPoints());decimal("10",stamp(1000,1000).bonusPoints());});
        test("pity.bonus-never-exceeds-cap-or-decreases",()->{BigDecimal prior=BigDecimal.ZERO;for(int n=0;n<256;n++){var value=stamp(n,n).bonusPoints();check(value.compareTo(prior)>=0);check(value.compareTo(new BigDecimal("10"))<=0);prior=value;}});
        test("pity.policy-has-meaningful-source-floor",()->{var p=policy();for(String s:List.of("0","-1","1E+1000000000","1E-1000","100000000000000001"))rejects(()->new PityPolicy(p.id(),1,p.paths(),p.targets(),p.profiles(),new BigDecimal(s),p.incrementPoints(),p.maximumPoints()));});
        test("pity.policy-epoch-range",()->{for(int e:List.of(-1,0,1000001))rejects(()->new PityStamp("x",e,SCOPE,0,0,BigDecimal.ONE,BigDecimal.TEN));});
        test("pity.policy-no-empty-or-wildcard",()->{var p=policy();for(Set<String> paths:List.of(Set.<String>of(),Set.of("*"),Set.of("../x")))rejects(()->new PityPolicy(p.id(),1,paths,p.targets(),p.profiles(),p.minimumSourceValue(),p.incrementPoints(),p.maximumPoints()));});
        test("pity.overlapping-policy-rejected-even-disabled",()->{var p=policy();var duplicate=new PityPolicy("another",1,p.paths(),p.targets(),p.profiles(),p.minimumSourceValue(),p.incrementPoints(),p.maximumPoints());rejects(()->new PityPolicies(false,List.of(p,duplicate)));rejects(()->new PityPolicies(true,List.of(p,p)));});
        test("pity.disjoint-policy-supported",()->{var p=policy();var other=new PityPolicy("other",1,p.paths(),Set.of("different"),p.profiles(),p.minimumSourceValue(),p.incrementPoints(),p.maximumPoints());eq(2,new PityPolicies(true,List.of(p,other)).policies().size());});
        test("pity.invalid-point-ranges",()->{for(String s:List.of("0","-1","101","0.0000001","1E+10000"))rejects(()->new PityStamp("x",1,SCOPE,0,0,new BigDecimal(s),new BigDecimal("10")));rejects(()->new PityStamp("x",1,SCOPE,0,0,BigDecimal.TEN,BigDecimal.ONE));});
        test("pity.stamp-version-binding",()->{var s=stamp(7,3);check(s.matches(new PitySnapshot(PLAYER,SCOPE,7,3)));check(!s.matches(new PitySnapshot(PLAYER,SCOPE,8,3)));check(!s.matches(new PitySnapshot(PLAYER,"b".repeat(64),7,3)));});
        test("pity.binary-roundtrip",()->{var s=stamp(9,8);var bytes=new ByteArrayOutputStream();s.write(new DataOutputStream(bytes));eq(s,PityStamp.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))));});
        test("pity.binary-truncated-rejected",()->{var bytes=new ByteArrayOutputStream();stamp(9,8).write(new DataOutputStream(bytes));byte[] data=bytes.toByteArray();for(int n=0;n<data.length;n+=7){int length=n;rejects(()->PityStamp.read(new DataInputStream(new ByteArrayInputStream(Arrays.copyOf(data,length)))));}});
        test("pity.clamp-after-points-not-before",()->{var c=new ChanceCalculator();var q=c.calculate(new BigDecimal("90"),new BigDecimal("900"),new ChanceFormula.Ratio(new BigDecimal("0.9")),BigDecimal.ONE,List.of(),List.of(),new BigDecimal("20"),new BigDecimal("90"),new BigDecimal("5"));decimal("20",q.probability().percent());});
        test("pity.global-max-survives-bonus",()->{var q=new ChanceCalculator().calculate(new BigDecimal("900"),new BigDecimal("1000"),new ChanceFormula.Ratio(BigDecimal.ONE),BigDecimal.ONE,List.of(),List.of(),BigDecimal.ZERO,new BigDecimal("90"),new BigDecimal("50"));decimal("90",q.probability().percent());check(q.clamped());});
        test("pity.zero-preserves-old-chance-breakdown",()->{var c=new ChanceCalculator();var f=new ChanceFormula.Ratio(new BigDecimal("0.9"));eq(c.calculate(BigDecimal.ONE,BigDecimal.TEN,f,BigDecimal.ONE,List.of(),List.of(),BigDecimal.ZERO,new BigDecimal("90")),c.calculate(BigDecimal.ONE,BigDecimal.TEN,f,BigDecimal.ONE,List.of(),List.of(),BigDecimal.ZERO,new BigDecimal("90"),BigDecimal.ZERO));});
        test("pity.random-exact-integer-grid-oracle",()->{var random=new Random(817);for(int i=0;i<300;i++){int source=1+random.nextInt(98),points=random.nextInt(11);var q=new ChanceCalculator().calculate(BigDecimal.valueOf(source),new BigDecimal("100"),new ChanceFormula.Ratio(new BigDecimal("0.9")),BigDecimal.ONE,List.of(),List.of(),BigDecimal.ZERO,new BigDecimal("90"),BigDecimal.valueOf(points));long expected=Math.min(900_000_000L,source*9_000_000L+points*10_000_000L);eq(expected,q.probability().winningTickets());}});
    }
    private static void pityQuotes() {
        test("quote.pity-zero-still-pins-version",()->{var q=ProgressDemo.fixture().quote(0,0).quote().orElseThrow();decimal("9",q.terms().probability().percent());check(q.terms().pity().isPresent());eq(0L,q.terms().pity().orElseThrow().version());});
        test("quote.four-failures-fourteen-percent",()->{var q=ProgressDemo.fixture().quote(4,4).quote().orElseThrow();decimal("14",q.terms().probability().percent());decimal("90",q.sourceTotal());decimal("900",q.targetTotal());});
        test("quote.cap-not-guaranteed-success",()->{decimal("19",ProgressDemo.fixture().quote(100,100).quote().orElseThrow().terms().probability().percent());});
        test("quote.disabled-does-not-read-storage",()->{var f=ProgressDemo.fixture();var r=quote(f,PityPolicies.disabled(),(p,s)->{throw new AssertionError("unexpected read");});check(r.quote().orElseThrow().terms().pity().isEmpty());});
        test("quote.database-error-not-zero-streak",()->{var f=ProgressDemo.fixture();eq(QuoteResult.Status.PITY_UNAVAILABLE,quote(f,f.policies(),(p,s)->{throw new IllegalStateException("unavailable");}).status());});
        test("quote.database-null-not-zero-streak",()->{var f=ProgressDemo.fixture();eq(QuoteResult.Status.PITY_UNAVAILABLE,quote(f,f.policies(),(p,s)->null).status());});
        test("quote.foreign-database-snapshot-refused",()->{var f=ProgressDemo.fixture();eq(QuoteResult.Status.PITY_UNAVAILABLE,quote(f,f.policies(),(p,s)->new PitySnapshot(OTHER,s,1,1)).status());eq(QuoteResult.Status.PITY_UNAVAILABLE,quote(f,f.policies(),(p,s)->new PitySnapshot(p,SCOPE,1,1)).status());});
        test("quote.floor-ineligible-no-read",()->{var f=ProgressDemo.fixture();var p=policy();var high=new PityPolicy(p.id(),1,p.paths(),p.targets(),p.profiles(),new BigDecimal("91"),p.incrementPoints(),p.maximumPoints());check(quote(f,new PityPolicies(true,List.of(high)),(a,b)->{throw new AssertionError();}).quote().orElseThrow().terms().pity().isEmpty());});
        test("quote.keep-cannot-earn-or-redeem-pity",()->{var f=ProgressDemo.fixture();var keep=new RiskProfile("standard",true,"ratio",BigDecimal.ONE,BigDecimal.ONE,RiskProfile.FailureMode.KEEP,List.of(),"",Set.of());var set=QuoteSettings.defaults();var rules=new UpgradeRules(f.rules().catalog(),new QuoteSettings("standard",set.minimumPercent(),set.maximumPercent(),4,set.lifetime(),true),f.rules().formulas(),List.of(keep),List.of(),List.of(),List.of(),List.of(),Optional.of(OutputRules.defaults()));var changed=new ProgressDemo.Fixture(f.source(),f.index(),f.access(),rules,f.request(),f.resources(),f.policies());check(quote(changed,changed.policies(),(a,b)->{throw new AssertionError();}).quote().orElseThrow().terms().pity().isEmpty());});
        test("quote.explicit-output-required-for-pity",()->{var f=ProgressDemo.fixture();var rules=new UpgradeRules(f.rules().catalog(),f.rules().settings(),f.rules().formulas(),List.copyOf(f.rules().profiles().values()),List.of(),List.of(),List.of(),List.of());var changed=new ProgressDemo.Fixture(f.source(),f.index(),f.access(),rules,f.request(),f.resources(),f.policies());eq(QuoteResult.Status.PITY_POLICY_DENIED,quote(changed,changed.policies(),(p,s)->PitySnapshot.empty(p,s)).status());});
        test("quote.version-change-reconfirm-even-same-bonus-cap",()->{var f=ProgressDemo.fixture();var q=f.quote(100,100).quote().orElseThrow();var v=validate(f,q,(p,s)->new PitySnapshot(p,s,101,101),NOW.plusSeconds(1));eq(UpgradeQuoteService.ValidationStatus.RECONFIRM_REQUIRED,v.status());});
        test("quote.unchanged-keeps-id-expiry",()->{var f=ProgressDemo.fixture();var q=f.quote(4,4).quote().orElseThrow();var v=validate(f,q,(p,s)->new PitySnapshot(p,s,4,4),NOW.plusSeconds(3));eq(UpgradeQuoteService.ValidationStatus.VALID_PREVIEW,v.status());eq(q.quoteId(),v.replacement().orElseThrow().quoteId());eq(q.selection().expiresAt(),v.replacement().orElseThrow().selection().expiresAt());});
        test("quote.expired-refused-without-new-pity-read",()->{var f=ProgressDemo.fixture();var q=f.quote(0,0).quote().orElseThrow();eq(UpgradeQuoteService.ValidationStatus.EXPIRED,validate(f,q,(p,s)->{throw new AssertionError();},q.selection().expiresAt()).status());});
        test("quote.wrong-session-refused",()->{var f=ProgressDemo.fixture();var q=f.quote(0,0).quote().orElseThrow();var v=new PityQuoteService().revalidate(q,OTHER,f.source(),f.index(),f.access(),f.rules(),f.resources(),NOW,f.policies(),(p,s)->{throw new AssertionError();});eq(UpgradeQuoteService.ValidationStatus.WRONG_SESSION,v.status());});
        test("quote.wrong-viewer-refused",()->{var f=ProgressDemo.fixture();var q=f.quote(0,0).quote().orElseThrow();var v=new PityQuoteService().revalidate(q,SESSION,f.source(),f.index(),new CatalogAccess(OTHER,Set.of(),Set.of()),f.rules(),f.resources(),NOW,f.policies(),(p,s)->{throw new AssertionError();});eq(UpgradeQuoteService.ValidationStatus.WRONG_VIEWER,v.status());});
        test("quote.revalidation-db-error-is-ineligible",()->{var f=ProgressDemo.fixture();eq(UpgradeQuoteService.ValidationStatus.NO_LONGER_ELIGIBLE,validate(f,f.quote(0,0).quote().orElseThrow(),(p,s)->{throw new IllegalStateException();},NOW).status());});
    }
    private static void scopeTests() {
        test("scope.repeated-quotes-share-logical-scope",()->{var f=ProgressDemo.fixture();eq(f.quote(0,0).quote().orElseThrow().terms().pity().orElseThrow().scope(),f.quote(3,3).quote().orElseThrow().terms().pity().orElseThrow().scope());});
        test("scope.epoch-and-cap-separate-progress",()->{var f=ProgressDemo.fixture();var base=new UpgradeQuoteService().quote(f.request(),f.source(),f.index(),f.access(),f.rules(),f.resources(),NOW).quote().orElseThrow();var p=policy();var original=PityScope.of(p,"iron_progression",base,f.source());var different=new PityPolicy(p.id(),2,p.paths(),p.targets(),p.profiles(),p.minimumSourceValue(),p.incrementPoints(),p.maximumPoints());check(!original.equals(PityScope.of(different,"iron_progression",base,f.source())));different=new PityPolicy(p.id(),1,p.paths(),p.targets(),p.profiles(),p.minimumSourceValue(),p.incrementPoints(),new BigDecimal("11"));check(!original.equals(PityScope.of(different,"iron_progression",base,f.source())));});
        test("scope.foreign-source-and-double-bonus-refused",()->{var f=ProgressDemo.fixture();var q=f.quote(1,1).quote().orElseThrow();rejects(()->PityScope.of(policy(),"iron_progression",q,f.source()));rejects(()->PityScope.of(policy(),"wrong",q,f.source()));});
        test("scope.source-stack-and-values-separate-progress",()->{var f=ProgressDemo.fixture();var s=new ItemSnapshot(ItemFacts.clean(f.source().facts().key(),2),new byte[]{1,2,3});var q=quote(f,f.policies(),(p,t)->PitySnapshot.empty(p,t)).quote().orElseThrow();var altered=new ProgressDemo.Fixture(s,f.index(),f.access(),f.rules(),f.request(),f.resources(),f.policies());var next=quote(altered,f.policies(),(p,t)->PitySnapshot.empty(p,t)).quote().orElseThrow();check(!q.terms().pity().orElseThrow().scope().equals(next.terms().pity().orElseThrow().scope()));});
        test("scope.unique-payload-not-a-progress-bypass",()->{var f=ProgressDemo.fixture();var s=new ItemSnapshot(f.source().facts(),new byte[]{9,8,7});var another=new ProgressDemo.Fixture(s,f.index(),f.access(),f.rules(),f.request(),f.resources(),f.policies());eq(f.quote(0,0).quote().orElseThrow().terms().pity().orElseThrow().scope(),another.quote(0,0).quote().orElseThrow().terms().pity().orElseThrow().scope());});
    }
    private static void codecTests() {
        test("codec.v1-v2-v3-selected-only-by-terms",()->{var legacy=TransactionFixtures.plan();var v2=OutputDemo.plan(new FailurePolicy.Destroy(),TransferPolicy.clean());var v3=ProgressFixtures.plan(10,4,4);eq(1,java.nio.ByteBuffer.wrap(JournalCodec.encodePlan(legacy)).getInt(4));eq(2,java.nio.ByteBuffer.wrap(JournalCodec.encodePlan(v2)).getInt(4));eq(3,java.nio.ByteBuffer.wrap(JournalCodec.encodePlan(v3)).getInt(4));});
        test("codec.pity-plan-pins-entire-stamp",()->{var p=ProgressFixtures.plan(10,4,4);var decoded=JournalCodec.decodePlan(JournalCodec.encodePlan(p));eq(p.terms(),decoded.terms());eq(JournalCodec.planDigest(p),JournalCodec.planDigest(decoded));});
        test("codec.version-change-alters-digest",()->{check(!JournalCodec.planDigest(ProgressFixtures.plan(10,4,4)).equals(JournalCodec.planDigest(ProgressFixtures.plan(10,5,4))));});
        for(boolean win:List.of(true,false))test("codec.all-transition-envelopes-"+(win?"win":"loss"),()->{for(var r:ProgressFixtures.history(ProgressFixtures.plan(10,4,4),win)){byte[] a=JournalCodec.encodeRecord(r),b=JournalCodec.encodeState(r);check(Arrays.equals(a,JournalCodec.encodeRecord(JournalCodec.decodeRecord(a))));check(Arrays.equals(b,JournalCodec.encodeState(JournalCodec.decodeState(r.plan(),b))));eq(r.plan().terms().pity(),JournalCodec.decodeRecord(a).plan().terms().pity());}});
        test("codec.pity-truncation-rejected",()->{var bytes=JournalCodec.encodePlan(ProgressFixtures.plan(10,4,4));for(int i=0;i<bytes.length;i+=17){int n=i;rejects(()->JournalCodec.decodePlan(Arrays.copyOf(bytes,n)));}});
        test("codec.trailing-data-refused",()->{var bytes=JournalCodec.encodePlan(ProgressFixtures.plan(10,4,4));rejects(()->JournalCodec.decodePlan(Arrays.copyOf(bytes,bytes.length+1)));});
        test("codec.no-implicit-pity-on-legacy",()->{for(var p:List.of(TransactionFixtures.plan(),OutputDemo.plan(new FailurePolicy.Destroy(),TransferPolicy.clean()))){var copy=JournalCodec.decodePlan(JournalCodec.encodePlan(p));check(copy.terms().pity().isEmpty());check(Arrays.equals(JournalCodec.encodePlan(p),JournalCodec.encodePlan(copy)));}});
        test("codec.pity-without-output-or-destroy-rejected",()->{var p=ProgressFixtures.plan(10,0,0);var t=p.terms();rejects(()->new UpgradeQuote.Terms(t.profileId(),t.boosts(),t.permissionBonuses(),t.probability(),t.failure(),t.costs(),Optional.empty(),t.pity()));rejects(()->new UpgradeQuote.Terms(t.profileId(),t.boosts(),t.permissionBonuses(),t.probability(),RiskProfile.FailureMode.KEEP,t.costs(),t.output(),t.pity()));});
        test("journal.no-op-hook-cannot-ignore-pity",()->{rejects(()->JournalCommitHook.NONE.claimed(null,AttemptRecord.initial(ProgressFixtures.plan(1,0,0),NOW)));JournalCommitHook.NONE.claimed(null,AttemptRecord.initial(TransactionFixtures.plan(),TransactionFixtures.NOW));});
    }
    private static HistoryQuery query(HistoryQuery.Filter filter,int limit){return new HistoryQuery(PLAYER,filter,NOW.toEpochMilli(),Optional.empty(),limit);}
    private static HistoryEntry row(long id,long ms){return ProgressFixtures.row(PLAYER,id,ms,HistoryEntry.Outcome.WIN,true);}
    private static void historyTests() {
        test("history.drawn-is-not-delivered",()->{var history=ProgressFixtures.history(ProgressFixtures.plan(1,0,0),true);var r=history.stream().filter(v->v.state()==AttemptRecord.State.OUTCOME_COMMITTED).findFirst().orElseThrow();var e=HistoryEntry.from(r);eq(HistoryEntry.Outcome.WIN,e.outcome());check(!e.settled());check(!e.terminal());check(HistoryEntry.from(history.getLast()).settled());});
        test("history.reconciliation-preserves-known-result",()->{var m=new TransactionMachine();var r=ProgressFixtures.history(ProgressFixtures.plan(1,0,0),false).stream().filter(v->v.state()==AttemptRecord.State.OUTCOME_COMMITTED).findFirst().orElseThrow();var frozen=m.quarantine(r,NOW,"TEST_UNCERTAIN");var h=HistoryEntry.from(frozen);eq(HistoryEntry.Outcome.LOSS,h.outcome());check(!h.terminal());var d=AttemptDiagnostic.from(frozen);check(d.needsReconciliation());eq(HistoryEntry.Outcome.LOSS,d.outcome());});
        test("history.undrawn-abort-not-counted-loss",()->{var r=AttemptRecord.initial(ProgressFixtures.plan(1,0,0),NOW);var aborted=new TransactionMachine().next(r,r.plan().expiresAt()).next();var e=HistoryEntry.from(aborted);eq(HistoryEntry.Outcome.NOT_ROLLED,e.outcome());check(e.terminal());check(!e.settled());});
        test("history.public-record-has-no-raw-evidence",()->{Set<String> names=new HashSet<>();for(var c:HistoryEntry.class.getRecordComponents())names.add(c.getName());for(String forbidden:List.of("sample","payload","receipt","pdc","evidence"))check(!names.contains(forbidden));});
        test("history.bad-state-outcome-rejected",()->{var a=row(1,1);rejects(()->new HistoryEntry(a.transactionId(),a.playerId(),1,AttemptRecord.State.RESERVING,HistoryEntry.Outcome.WIN,a.sourceKey(),1,a.targetId(),a.targetKey(),1,a.sourceValue(),a.targetValue(),a.probability(),a.profile(),a.failure(),a.createdAt(),a.updatedAt()));});
        test("history.value-exponent-bomb-refused",()->{var a=row(1,1);rejects(()->new HistoryEntry(a.transactionId(),a.playerId(),1,a.state(),a.outcome(),a.sourceKey(),1,a.targetId(),a.targetKey(),1,BigDecimal.ONE,new BigDecimal("1E+1000000"),a.probability(),a.profile(),a.failure(),a.createdAt(),a.updatedAt()));});
        test("history.pagination-same-time-orders-uuid-text",()->{var q=query(HistoryQuery.Filter.ALL,2);var p=new HistoryPage(q,List.of(row(3,100),row(2,100)),true);eq(new UUID(0,2),p.next().orElseThrow().transactionId());var next=new HistoryQuery(PLAYER,q.filter(),q.upperCreatedMillis(),p.next(),2);check(next.accepts(row(1,100)));check(!next.accepts(row(2,100)));check(next.accepts(row(99,99)));});
        test("history.foreign-cursor-filter-window-refused",()->{var q=query(HistoryQuery.Filter.ALL,2);var c=q.cursor(row(2,100));rejects(()->new HistoryQuery(OTHER,q.filter(),q.upperCreatedMillis(),Optional.of(c),2));rejects(()->new HistoryQuery(PLAYER,HistoryQuery.Filter.LOSS,q.upperCreatedMillis(),Optional.of(c),2));rejects(()->new HistoryQuery(PLAYER,q.filter(),q.upperCreatedMillis()+1,Optional.of(c),2));});
        test("history.page-bounds-and-partial-more",()->{for(int limit:List.of(-1,0,46,Integer.MAX_VALUE))rejects(()->query(HistoryQuery.Filter.ALL,limit));rejects(()->new HistoryPage(query(HistoryQuery.Filter.ALL,2),List.of(row(1,10)),true));});
        test("history.unordered-or-duplicate-page-refused",()->{rejects(()->new HistoryPage(query(HistoryQuery.Filter.ALL,2),List.of(row(1,10),row(2,10)),false));rejects(()->new HistoryPage(query(HistoryQuery.Filter.ALL,2),List.of(row(1,10),row(1,10)),false));});
        test("history.foreign-row-and-newer-window-refused",()->{rejects(()->new HistoryPage(query(HistoryQuery.Filter.ALL,2),List.of(ProgressFixtures.row(OTHER,1,10,HistoryEntry.Outcome.WIN,true)),false));rejects(()->new HistoryPage(query(HistoryQuery.Filter.ALL,2),List.of(row(1,NOW.toEpochMilli()+1)),false));});
        test("history.filter-validates-projection-not-ui",()->{check(!query(HistoryQuery.Filter.LOSS,2).accepts(row(1,1)));check(!query(HistoryQuery.Filter.UNFINISHED,2).accepts(row(1,1)));check(query(HistoryQuery.Filter.UNFINISHED,2).accepts(ProgressFixtures.row(PLAYER,1,1,HistoryEntry.Outcome.WIN,false)));});
        test("history.empty-page-valid-no-next",()->{var p=new HistoryPage(query(HistoryQuery.Filter.ALL,2),List.of(),false);check(p.next().isEmpty());});
        test("history.keyset-randomized-reference-pages",()->{var random=new Random(8808);var data=new ArrayList<HistoryEntry>();for(int i=0;i<180;i++)data.add(row(i,random.nextInt(10)));data.sort(Comparator.comparing(HistoryEntry::createdAt).thenComparing(e->e.transactionId().toString()).reversed());var q=query(HistoryQuery.Filter.ALL,13);int seen=0;var ids=new HashSet<UUID>();while(true){var chosen=data.stream().filter(q::accepts).limit(14).toList();boolean more=chosen.size()>13;var p=new HistoryPage(q,chosen.subList(0,Math.min(chosen.size(),13)),more);for(var e:p.rows()){eq(data.get(seen++).transactionId(),e.transactionId());check(ids.add(e.transactionId()));}if(!more)break;q=new HistoryQuery(PLAYER,q.filter(),q.upperCreatedMillis(),p.next(),13);}eq(180,seen);});
    }
    private static StatisticsCache cache(int max){return new StatisticsCache(max,Duration.ofSeconds(10));}
    private static void statisticsTests() {
        test("stats.completed-totals-and-rounding",()->{var s=PlayerStatistics.empty(PLAYER).complete(false).complete(true).complete(false);eq(3L,s.completed());eq(1L,s.wins());eq(2L,s.losses());decimal("33.33",s.winRate());});
        test("stats.invalid-or-overflow-refused",()->{rejects(()->new PlayerStatistics(PLAYER,2,2,2));rejects(()->new PlayerStatistics(PLAYER,-1,0,0));rejects(()->new PlayerStatistics(PLAYER,PitySnapshot.MAX_COUNTER,PitySnapshot.MAX_COUNTER,0).complete(true));decimal("0",PlayerStatistics.empty(PLAYER).winRate());});
        test("cache.cold-placeholder-no-query",()->{var c=cache(2);eq("",c.placeholder(PLAYER,"wins",NOW));eq(0,c.size());});
        test("cache.single-flight-and-capacity",()->{var c=cache(1);check(c.begin(PLAYER).isPresent());check(c.begin(PLAYER).isEmpty());check(c.begin(OTHER).isEmpty());});
        test("cache.load-failure-not-zero-result",()->{var c=cache(1);var t=c.begin(PLAYER).orElseThrow();c.failed(t);eq("",c.placeholder(PLAYER,"completed",NOW));check(c.begin(PLAYER).isPresent());});
        test("cache.ttl-boundary-and-values",()->{var c=cache(1);check(c.complete(c.begin(PLAYER).orElseThrow(),new PlayerStatistics(PLAYER,3,1,2),NOW));eq("3",c.placeholder(PLAYER,"completed",NOW));eq("33.33",c.placeholder(PLAYER,"win_rate",NOW));eq("",c.placeholder(PLAYER,"arbitrary",NOW));eq("",c.placeholder(PLAYER,"completed",NOW.plusSeconds(10)));});
        test("cache.quit-old-ticket-not-new-session",()->{var c=cache(1);var a=c.begin(PLAYER).orElseThrow();c.invalidate(PLAYER);var b=c.begin(PLAYER).orElseThrow();check(!c.complete(a,PlayerStatistics.empty(PLAYER),NOW));check(c.complete(b,PlayerStatistics.empty(PLAYER),NOW));});
        test("cache.regression-and-equal-count-conflict-refused",()->{var c=cache(1);c.complete(c.begin(PLAYER).orElseThrow(),new PlayerStatistics(PLAYER,2,1,1),NOW);check(!c.complete(c.begin(PLAYER).orElseThrow(),new PlayerStatistics(PLAYER,1,1,0),NOW));check(!c.complete(c.begin(PLAYER).orElseThrow(),new PlayerStatistics(PLAYER,2,2,0),NOW));eq("1",c.placeholder(PLAYER,"wins",NOW));});
        test("cache.foreign-result-refused",()->{var c=cache(1);var t=c.begin(PLAYER).orElseThrow();rejects(()->c.complete(t,PlayerStatistics.empty(OTHER),NOW));check(c.get(PLAYER,NOW).isEmpty());});
        test("cache.bounded-lru-cleanup",()->{var c=cache(2);for(int i=0;i<10;i++){var p=new UUID(0,100+i);c.complete(c.begin(p).orElseThrow(),PlayerStatistics.empty(p),NOW);}eq(2,c.size());check(c.get(new UUID(0,100),NOW).isEmpty());});
        test("cache.closed-rejects-late-response",()->{var c=cache(1);var t=c.begin(PLAYER).orElseThrow();c.close();check(!c.complete(t,PlayerStatistics.empty(PLAYER),NOW));check(c.begin(PLAYER).isEmpty());eq(0,c.size());});
        test("cache.concurrent-one-winner-for-same-player",()->{var c=cache(1);var won=new AtomicInteger();try(var executor=Executors.newFixedThreadPool(8)){List<Future<?>> jobs=new ArrayList<>();for(int i=0;i<32;i++)jobs.add(executor.submit(()->{if(c.begin(PLAYER).isPresent())won.incrementAndGet();}));for(var f:jobs)f.get(5,TimeUnit.SECONDS);}eq(1,won.get());});
    }
    private static HistorySessionStore store(){return new HistorySessionStore(2,2,Duration.ofSeconds(3),Duration.ofSeconds(30));}
    private static HistorySessionStore.View open(HistorySessionStore s){return s.open(PLAYER,PLAYER,1,HistoryQuery.Filter.ALL,1,NOW).orElseThrow();}
    private static HistoryPage page(HistorySessionStore.Request r,long id,boolean more){return new HistoryPage(r.query(),List.of(row(id,100)),more);}
    private static void sessionTests() {
        test("session.capacity-and-replacement",()->{var s=store();var v=open(s);check(s.open(OTHER,OTHER,1,HistoryQuery.Filter.ALL,1,NOW).isPresent());check(s.open(new UUID(0,5),PLAYER,1,HistoryQuery.Filter.ALL,1,NOW).isEmpty());var n=open(s);check(!v.session().equals(n.session()));eq(2,s.size());});
        test("session.single-inflight",()->{var s=store();var v=open(s);check(s.begin(PLAYER,v.session(),1,HistorySessionStore.Navigation.FIRST,NOW).isPresent());check(s.begin(PLAYER,v.session(),1,HistorySessionStore.Navigation.REFRESH,NOW).isEmpty());});
        test("session.next-prev-cursor-trail",()->{var s=store();var v=open(s);var a=s.begin(PLAYER,v.session(),1,HistorySessionStore.Navigation.FIRST,NOW).orElseThrow();eq(1,s.complete(a,page(a,3,true),1,NOW).orElseThrow().pageNumber());var b=s.begin(PLAYER,v.session(),1,HistorySessionStore.Navigation.NEXT,NOW).orElseThrow();eq(new UUID(0,3),b.query().after().orElseThrow().transactionId());eq(2,s.complete(b,page(b,2,true),1,NOW).orElseThrow().pageNumber());check(s.begin(PLAYER,v.session(),1,HistorySessionStore.Navigation.NEXT,NOW).isEmpty());var back=s.begin(PLAYER,v.session(),1,HistorySessionStore.Navigation.PREVIOUS,NOW).orElseThrow();check(back.query().after().isEmpty());eq(1,s.complete(back,page(back,3,true),1,NOW).orElseThrow().pageNumber());});
        test("session.failed-next-keeps-page",()->{var s=store();var v=open(s);var a=s.begin(PLAYER,v.session(),1,HistorySessionStore.Navigation.FIRST,NOW).orElseThrow();s.complete(a,page(a,3,true),1,NOW);var b=s.begin(PLAYER,v.session(),1,HistorySessionStore.Navigation.NEXT,NOW).orElseThrow();check(s.failed(b,1,NOW));eq(1,s.view(PLAYER,v.session(),1,NOW).orElseThrow().pageNumber());});
        test("session.request-expired-does-not-render",()->{var s=store();var v=open(s);var a=s.begin(PLAYER,v.session(),1,HistorySessionStore.Navigation.FIRST,NOW).orElseThrow();check(s.complete(a,page(a,3,true),1,NOW.plusSeconds(3)).isEmpty());check(s.begin(PLAYER,v.session(),1,HistorySessionStore.Navigation.FIRST,NOW.plusSeconds(3)).isPresent());});
        test("session.old-close-and-callback-ignore-new-view",()->{var s=store();var old=open(s);var request=s.begin(PLAYER,old.session(),1,HistorySessionStore.Navigation.FIRST,NOW).orElseThrow();var replacement=open(s);s.close(PLAYER,old.session());check(s.view(PLAYER,replacement.session(),1,NOW).isPresent());check(s.complete(request,page(request,3,true),1,NOW).isEmpty());});
        test("session.revision-mismatch-cleans-old-state",()->{var s=store();var v=open(s);var a=s.begin(PLAYER,v.session(),1,HistorySessionStore.Navigation.FIRST,NOW).orElseThrow();check(s.complete(a,page(a,3,true),2,NOW).isEmpty());eq(0,s.size());});
        test("session.forged-response-query-refused",()->{var s=store();var v=open(s);var a=s.begin(PLAYER,v.session(),1,HistorySessionStore.Navigation.FIRST,NOW).orElseThrow();var q=new HistoryQuery(OTHER,HistoryQuery.Filter.ALL,a.query().upperCreatedMillis(),Optional.empty(),1);rejects(()->s.complete(a,new HistoryPage(q,List.of(),false),1,NOW));});
        test("session.refresh-resets-window-only-after-success",()->{var s=store();var v=open(s);var a=s.begin(PLAYER,v.session(),1,HistorySessionStore.Navigation.FIRST,NOW).orElseThrow();s.complete(a,page(a,3,true),1,NOW);var refresh=s.begin(PLAYER,v.session(),1,HistorySessionStore.Navigation.REFRESH,NOW.plusSeconds(1)).orElseThrow();eq(NOW.plusSeconds(1).toEpochMilli(),refresh.query().upperCreatedMillis());check(refresh.query().after().isEmpty());s.complete(refresh,page(refresh,4,false),1,NOW.plusSeconds(1));check(s.begin(PLAYER,v.session(),1,HistorySessionStore.Navigation.NEXT,NOW.plusSeconds(1)).isEmpty());});
        test("session.ttl-and-shutdown",()->{var s=store();var v=open(s);eq(1,s.sweep(NOW.plusSeconds(30)));check(s.view(PLAYER,v.session(),1,NOW.plusSeconds(30)).isEmpty());s.shutdown();check(s.open(PLAYER,PLAYER,1,HistoryQuery.Filter.ALL,1,NOW).isEmpty());});
        test("session.pagezero-previous-and-next-before-fetch",()->{var s=store();var v=open(s);check(s.begin(PLAYER,v.session(),1,HistorySessionStore.Navigation.PREVIOUS,NOW).isEmpty());check(s.begin(PLAYER,v.session(),1,HistorySessionStore.Navigation.NEXT,NOW).isEmpty());});
        test("session.query-subject-not-viewer-for-admin",()->{var s=store();var v=s.open(PLAYER,OTHER,1,HistoryQuery.Filter.LOSS,1,NOW).orElseThrow();var r=s.begin(PLAYER,v.session(),1,HistorySessionStore.Navigation.FIRST,NOW).orElseThrow();eq(OTHER,r.query().playerId());eq(PLAYER,r.viewer());});
    }
    private static MenuCompiler.CompiledMenu historyMenu() {
        Map<Character,MenuDefinition.Element> symbols=new HashMap<>();
        symbols.put('#',element(MenuDefinition.Role.FILLER,MenuDefinition.Action.NONE));
        symbols.put('E',element(MenuDefinition.Role.INFO,MenuDefinition.Action.NONE));
        symbols.put('P',element(MenuDefinition.Role.BUTTON,MenuDefinition.Action.PREVIOUS_PAGE));
        symbols.put('N',element(MenuDefinition.Role.BUTTON,MenuDefinition.Action.NEXT_PAGE));
        symbols.put('R',element(MenuDefinition.Role.BUTTON,MenuDefinition.Action.REFRESH));
        symbols.put('X',element(MenuDefinition.Role.BUTTON,MenuDefinition.Action.CLOSE));
        return new MenuCompiler().compile(new MenuDefinition("history","Title",List.of("#########","#E#P#N#R#","####X####"),symbols),false);
    }
    private static MenuDefinition.Element element(MenuDefinition.Role role,MenuDefinition.Action action) {
        return new MenuDefinition.Element(role,"PAPER","Name",List.of(),"",null,false,action,"");
    }
    private static HistorySettings settings(MenuCompiler.CompiledMenu menu,int sessions,int queries,int pages,int cache,int days,int batch) {
        return new HistorySettings(false,true,sessions,queries,pages,cache,Duration.ofSeconds(30),Duration.ofSeconds(10),Duration.ofSeconds(300),days,batch,menu,PityPolicies.disabled());
    }
    private static void configurationTests() {
        test("history-config.read-only-entry-and-navigation",()->{var c=settings(historyMenu(),64,16,64,2000,90,200);eq(1,c.entrySlots().size());check(!c.enabled());check(c.pity().policies().isEmpty());});
        test("history-config.limits",()->{var m=historyMenu();rejects(()->settings(m,129,16,64,2000,90,200));rejects(()->settings(m,64,33,64,2000,90,200));rejects(()->settings(m,64,16,101,2000,90,200));rejects(()->settings(m,64,16,64,10001,90,200));rejects(()->settings(m,64,16,64,2000,0,200));rejects(()->settings(m,64,16,64,2000,90,1001));});
        test("history-config.no-upgrade-action",()->{var m=historyMenu();var slots=new HashMap<>(m.slots());slots.put(10,element(MenuDefinition.Role.INFO,MenuDefinition.Action.UPGRADE));rejects(()->settings(new MenuCompiler.CompiledMenu(m.id(),m.title(),m.size(),slots,-1),64,16,64,2000,90,200));});
        test("history-config.requires-entry-and-navigation",()->{var m=historyMenu();for(int slot:List.of(10,12,14,16,22)){var slots=new HashMap<>(m.slots());slots.put(slot,element(MenuDefinition.Role.FILLER,MenuDefinition.Action.NONE));rejects(()->settings(new MenuCompiler.CompiledMenu(m.id(),m.title(),m.size(),slots,-1),64,16,64,2000,90,200));}});
        test("history-config.source-role-forbidden",()->{var m=historyMenu();var slots=new HashMap<>(m.slots());slots.put(10,element(MenuDefinition.Role.SOURCE_INPUT,MenuDefinition.Action.SOURCE_INPUT));rejects(()->settings(new MenuCompiler.CompiledMenu(m.id(),m.title(),m.size(),slots,10),64,16,64,2000,90,200));});
        test("history-config.duration-bounds",()->{var m=historyMenu();rejects(()->new HistorySettings(false,true,64,16,64,2000,Duration.ZERO,Duration.ofSeconds(10),Duration.ofSeconds(300),90,200,m,PityPolicies.disabled()));});

        test("sql.binds-user-data-and-keyset-not-offset",()->{var sql=new ProgressSql(ProgressSql.Tables.prefixed("test_"));for(var f:HistoryQuery.Filter.values()){String text=sql.page(f);check(text.contains("player_uuid=?"));check(text.contains("LIMIT ?"));check(!text.contains("OFFSET"));}check(sql.updatePity().contains("version=? AND failures=?"));});
        test("sql.table-identifiers-not-user-input",()->{for(String p:List.of("x;DROP_","x-", "x".repeat(45)))rejects(()->new ProgressSql(ProgressSql.Tables.prefixed(p)));rejects(()->new ProgressSql.Tables("x","x","p","c","m"));});
        test("sql.retention-does-not-delete-authoritative-state",()->{var sql=new ProgressSql(ProgressSql.Tables.prefixed("test_"));check(sql.pruneOne().startsWith("DELETE FROM test_history "));check(sql.pruneOne().contains("terminal=1"));check(sql.retentionCandidates().contains("LIMIT ?"));});
        test("sql.mysql-tables-declare-innodb",()->{var sql=new ProgressSql(ProgressSql.Tables.prefixed("test_"));eq(5,sql.ddl(JournalSql.Dialect.MYSQL).size());for(String ddl:sql.ddl(JournalSql.Dialect.MYSQL))check(ddl.endsWith("ENGINE=InnoDB"));});
    }
}
