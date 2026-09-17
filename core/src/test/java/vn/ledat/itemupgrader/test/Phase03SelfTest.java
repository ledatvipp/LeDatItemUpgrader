package vn.ledat.itemupgrader.test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import vn.ledat.itemupgrader.catalog.*;
import vn.ledat.itemupgrader.chance.*;
import vn.ledat.itemupgrader.cost.*;
import vn.ledat.itemupgrader.profile.*;
import vn.ledat.itemupgrader.boost.*;
import vn.ledat.itemupgrader.condition.*;
import vn.ledat.itemupgrader.quote.*;
import java.math.BigInteger;
import java.math.RoundingMode;
import vn.ledat.itemupgrader.item.*;
import vn.ledat.itemupgrader.recipe.RecipeIndex;
import vn.ledat.itemupgrader.runtime.RuntimeStore;
import vn.ledat.itemupgrader.runtime.PreviewRequestGate;
import java.util.concurrent.atomic.AtomicLong;
import vn.ledat.itemupgrader.value.*;

/** Detached fake payload fixtures test business rules, not Paper/provider serialization or server TPS. */
public final class Phase03SelfTest {
    @FunctionalInterface interface Checked { void run() throws Exception; }
    record Test(String name, Checked body) {}
    record Result(String name, double seconds, String failure) {}
    private static final List<Test> TESTS = new ArrayList<>();
    private static int assertions;

    private static final UUID VIEWER = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SESSION = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-09-16T00:00:00Z");
    private static final ItemKey IRON = k("minecraft:iron_ingot");
    private static final ItemKey GOLD = k("minecraft:gold_ingot");
    private static final ItemKey DIAMOND = k("minecraft:diamond");
    private static final UpgradeQuoteService QUOTES = new UpgradeQuoteService();
    private static final ChanceCalculator MATH = new ChanceCalculator();
    private static final CostResource VAULT = CostResource.currency(CostResource.Currency.VAULT);
    private static final CostResource POINTS = CostResource.currency(CostResource.Currency.PLAYERPOINTS);
    private static final CostResource GEM = CostResource.item(k("minecraft:emerald"));
    private static final String FP = "a".repeat(64);
    private Phase03SelfTest() {}
    public static void main(String[] args) throws Exception {
        mathTests(); conditionTests(); costTests(); definitionTests(); quoteTests(); argumentTests(); invariantTests();
        List<Result> results = new ArrayList<>();
        int failures = 0;
        for (Test test : TESTS) {
            long start = System.nanoTime();
            String failure = null;
            try { test.body().run(); }
            catch (Exception | AssertionError error) {
                failure = error.getClass().getSimpleName() + ": " + error.getMessage();
                failures++; error.printStackTrace(System.err);
            }
            results.add(new Result(test.name(), (System.nanoTime() - start) / 1_000_000_000.0, failure));
            System.out.println((failure == null ? "PASS " : "FAIL ") + test.name());
        }
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuite name=\"ItemUpgraderPhase03\" tests=\"" + results.size()
                + "\" failures=\"" + failures + "\" errors=\"0\">\n");
        for (Result result : results) {
            xml.append("  <testcase classname=\"ItemUpgraderPhase03\" name=\"").append(escape(result.name())).append("\" time=\"")
                    .append(String.format(java.util.Locale.ROOT, "%.6f", result.seconds())).append("\">");
            if (result.failure() != null) xml.append("<failure message=\"").append(escape(result.failure())).append("\"/>");
            xml.append("</testcase>\n");
        }
        xml.append("  <system-out>assertions=").append(assertions).append("; No Paper/Platform runtime tests in this suite.</system-out>\n</testsuite>\n");
        Path report = Path.of(args.length == 0 ? "build/reports/phase03-self-test.xml" : args[0]);
        Files.createDirectories(report.toAbsolutePath().getParent()); Files.writeString(report, xml, StandardCharsets.UTF_8);
        System.out.println("RESULT tests=" + results.size() + " assertions=" + assertions + " failures=" + failures);
        if (failures != 0) throw new AssertionError(failures + " failed test groups");
    }


    private static void mathTests() {
        test("math.ratio-nine-percent", () -> amount("9", chance("100","1000").probability().percent()));
        test("math.add-five-percentage-points", () -> amount("14", calc(ratio(),List.of(),List.of(adj("lucky","1","5"))).probability().percent()));
        test("math.multiply-one-point-zero-five", () -> amount("9.45", calc(ratio(),List.of(adj("vip","1.05","0")),List.of()).probability().percent()));
        test("math.permission-points-before-boost-multiplier", () -> amount("28", calc(ratio(),List.of(adj("vip","1","5")),List.of(adj("lucky","2","0"))).probability().percent()));
        test("math.all-products-before-all-points", () -> amount("48", calc(ratio(),List.of(),List.of(adj("a","2","3"),adj("b","2","9"))).probability().percent()));
        test("math.profile-safe-multiplier", () -> amount("5.4", MATH.calculate(d("90"),d("900"),ratio(),d("0.6"),List.of(),List.of(),d("0.1"),d("90")).probability().percent()));
        test("math.minimum-clamp", () -> { var q=chance("1","1000000");amount("0.1",q.probability().percent());check(q.clamped()); });
        test("math.maximum-clamp", () -> { var q=calc(ratio(),List.of(),List.of(adj("big","10","90")));amount("90",q.probability().percent());check(q.clamped()); });
        test("math.no-clamp-or-round-for-exact-percent", () -> {var q=chance("100","1000");check(!q.clamped());check(!q.quantized());});
        test("math.one-third-final-floor", () -> {var q=MATH.calculate(d("1"),d("3"),new ChanceFormula.Ratio(d("1")),d("1"),List.of(),List.of(),d("0"),d("100"));eq(333333333L,q.probability().winningTickets());check(q.quantized());});
        test("math.exact-ticket-boundary-no-intermediate-round", () -> {var q=MATH.calculate(d("2999999999"),d("3000000000"),new ChanceFormula.Ratio(d("1")),d("0.3"),List.of(),List.of(),d("0"),d("100"));eq(299999999L,q.probability().winningTickets());});
        test("math.just-below-table-threshold-is-lower-band", () -> {var f=new ChanceFormula.Table(List.of(point("0","0"),point("0.1","25")));var q=MATH.calculate(d("99999999999999999.99999999"),d("1000000000000000000"),f,d("1"),List.of(),List.of(),d("0"),d("100"));eq(0L,q.probability().winningTickets());});
        test("math.table-lower-inclusive", () -> amount("25", calc(new ChanceFormula.Table(List.of(point("0","0"),point("0.1","25"),point("0.5","75"))),List.of(),List.of()).probability().percent()));
        test("math.curve-linear-interpolation", () -> amount("10", calc(new ChanceFormula.Curve(List.of(point("0","0"),point("0.2","20"),point("1","90"))),List.of(),List.of()).probability().percent()));
        test("math.power-two", () -> amount("0.9", calc(new ChanceFormula.Power(d("0.9"),2),List.of(),List.of()).probability().percent()));
        test("math.power-eight-no-float", () -> {var q=MATH.calculate(d("1"),d("2"),new ChanceFormula.Power(d("1"),8),d("1"),List.of(),List.of(),d("0"),d("100"));eq(3906250L,q.probability().winningTickets());});
        test("math.lower-bound-rounds-up-to-grid", () -> {var q=MATH.calculate(d("1"),d("10"),ratio(),d("1"),List.of(),List.of(),d("9.00000001"),d("10"));amount("9.0000001",q.probability().percent());});
        test("math.upper-bound-rounds-down-to-grid", () -> {var q=MATH.calculate(d("1"),d("2"),ratio(),d("1"),List.of(),List.of(),d("0"),d("8.99999999"));amount("8.9999999",q.probability().percent());});
        test("math.impossible-grid-bounds-rejected", () -> illegal(()->MATH.calculate(d("1"),d("10"),ratio(),d("1"),List.of(),List.of(),d("0.00000001"),d("0.00000009"))));
        test("math.zero-odds-legal-explicitly", () -> eq(0L, MATH.calculate(d("1"),d("10"),ratio(),d("1"),List.of(),List.of(),d("0"),d("0")).probability().winningTickets()));
        test("math.hundred-percent-legal-explicitly", () -> eq(Probability.DENOMINATOR, MATH.calculate(d("1"),d("2"),new ChanceFormula.Ratio(d("2")),d("1"),List.of(),List.of(),d("0"),d("100")).probability().winningTickets()));
        test("math.ticket-endpoints", () -> {var p=new Probability(90000000);check(p.succeeds(89999999));check(!p.succeeds(90000000));check(!new Probability(0).succeeds(0));check(new Probability(Probability.DENOMINATOR).succeeds(Probability.DENOMINATOR-1));});
        test("math.sample-range-validation", () -> {illegal(()->new Probability(1).succeeds(-1));illegal(()->new Probability(1).succeeds(Probability.DENOMINATOR));});
        test("math.probability-range-validation", () -> {illegal(()->new Probability(-1));illegal(()->new Probability(Probability.DENOMINATOR+1));});
        test("math.zero-source-rejected", () -> illegal(()->chance("0","1000")));
        test("math.zero-target-rejected", () -> illegal(()->chance("100","0")));
        test("math.target-must-exceed-source", () -> {illegal(()->chance("100","100"));illegal(()->chance("101","100"));});
        test("math.negative-rejected", () -> illegal(()->chance("-1","100")));
        test("math.exponent-bomb-rejected-before-allocation", () -> illegal(()->chance("1E+999999999","1000")));
        test("math.multiplier-out-of-range", () -> {illegal(()->new ChanceFormula.Ratio(d("0")));illegal(()->new ChanceFormula.Ratio(d("10.1")));illegal(()->adj("a","-1","0"));});
        test("math.no-negative-points", () -> illegal(()->adj("a","1","-1")));
        test("math.no-too-many-points", () -> illegal(()->adj("a","1","101")));
        test("math.exponent-bounded-integer", () -> {illegal(()->new ChanceFormula.Power(d("1"),0));illegal(()->new ChanceFormula.Power(d("1"),9));});
        test("math.table-needs-zero-threshold", () -> illegal(()->new ChanceFormula.Table(List.of(point("0.1","5")))));
        test("math.table-no-duplicate-threshold", () -> illegal(()->new ChanceFormula.Table(List.of(point("0","0"),point("0.1","5"),point("0.10","9")))));
        test("math.table-does-not-silently-sort", () -> illegal(()->new ChanceFormula.Table(List.of(point("0","0"),point("0.5","50"),point("0.1","60")))));
        test("math.curve-must-have-full-domain", () -> illegal(()->new ChanceFormula.Curve(List.of(point("0","0"),point("0.5","50")))));
        test("math.curve-no-decreasing-chance", () -> illegal(()->new ChanceFormula.Curve(List.of(point("0","20"),point("1","10")))));
        test("math.table-points-immutable", () -> {var list=new ArrayList<>(List.of(point("0","0"),point("0.1","25")));var f=new ChanceFormula.Table(list);list.clear();eq(2,f.points().size());expect(UnsupportedOperationException.class,()->f.points().clear());});
        test("math.duplicate-adjustment-rejected", () -> illegal(()->calc(ratio(),List.of(),List.of(adj("a","1","1"),adj("a","1","1")))));
        test("math.boost-limit-eight", () -> {var list=new ArrayList<ChanceAdjustment>();for(int i=0;i<9;i++)list.add(adj("b"+i,"1","1"));illegal(()->calc(ratio(),List.of(),list));});
        test("math.breakdown-stages-explicit", () -> eq(List.of("formula","profile","permissions-multiply","permissions-points","boosts-multiply","boosts-points","clamp","ticket-grid"),chance("100","1000").steps().stream().map(ChanceQuote.Step::stage).toList()));
    }

    private static void conditionTests() {
        test("condition.numeric-at-threshold", () -> eq(ConditionEvaluator.Status.SATISFIED, condition(numberCondition("GE","50"),"50")));
        test("condition.numeric-below-threshold", () -> eq(ConditionEvaluator.Status.NOT_MATCHED, condition(numberCondition("GE","50"),"49.99")));
        test("condition.numeric-negative-supported", () -> eq(ConditionEvaluator.Status.SATISFIED, condition(numberCondition("LT","0"),"-1")));
        test("condition.numeric-decimal-scale-irrelevant", () -> eq(ConditionEvaluator.Status.SATISFIED, condition(numberCondition("EQ","50"),"50.000")));
        for(String op:List.of("EQ","NE","GT","GE","LT","LE")) test("condition.operator-"+op, () -> {
            var expected=switch(op){case "NE","GT","GE"->ConditionEvaluator.Status.SATISFIED;default->ConditionEvaluator.Status.NOT_MATCHED;};
            eq(expected,condition(numberCondition(op,"1"),"2"));
        });
        test("condition.missing-ne-never-passes", () -> eq(ConditionEvaluator.Status.UNAVAILABLE,new ConditionEvaluator().test(numberCondition("NE","0"),ConditionEvaluator.Resolution.missing())));
        test("condition.unresolved-ne-never-passes", () -> eq(ConditionEvaluator.Status.UNRESOLVED,condition(numberCondition("NE","0"),"%player_level%")));
        for(String raw:List.of("NaN","Infinity","1e5","1,000","<red>50","50\n","","05","9999999999999999999","0.123456789"))
            test("condition.reject-invalid-number-"+Integer.toHexString(raw.hashCode()), () -> eq(ConditionEvaluator.Status.INVALID_VALUE,condition(numberCondition("GE","0"),raw)));
        test("condition.string-case-sensitive", () -> {var c=new ConditionDefinition("rank","%vault_rank%",ConditionDefinition.Type.TEXT,ConditionDefinition.Operator.EQ,"VIP");eq(ConditionEvaluator.Status.SATISFIED,condition(c,"VIP"));eq(ConditionEvaluator.Status.NOT_MATCHED,condition(c,"vip"));});
        test("condition.text-ne-unresolved-still-denies", () -> {var c=new ConditionDefinition("rank","%vault_rank%",ConditionDefinition.Type.TEXT,ConditionDefinition.Operator.NE,"guest");eq(ConditionEvaluator.Status.UNRESOLVED,condition(c,"%vault_rank%"));});
        test("condition.boolean-no-truthy-coercion", () -> {var c=new ConditionDefinition("yes","%test_flag%",ConditionDefinition.Type.BOOLEAN,ConditionDefinition.Operator.EQ,"true");eq(ConditionEvaluator.Status.INVALID_VALUE,condition(c,"yes"));eq(ConditionEvaluator.Status.SATISFIED,condition(c,"true"));});
        test("condition.result-length-limit", () -> eq(ConditionEvaluator.Status.INVALID_VALUE,condition(numberCondition("GE","0"),"9".repeat(257))));
        test("condition.no-relational-placeholder", () -> illegal(()->new ConditionDefinition("x","%rel_test_name%",ConditionDefinition.Type.TEXT,ConditionDefinition.Operator.EQ,"a")));
        test("condition.no-compound-placeholder-expression", () -> illegal(()->new ConditionDefinition("x","%player_level% + 1",ConditionDefinition.Type.NUMBER,ConditionDefinition.Operator.GE,"1")));
        test("condition.no-script-operator", () -> illegal(()->new ConditionDefinition("x","%vault_rank%",ConditionDefinition.Type.TEXT,ConditionDefinition.Operator.GT,"a")));
        test("condition.invalid-expected-number-rejects-config", () -> illegal(()->numberCondition("GE","NaN")));
        test("condition.budget-exhaustion-denies-ne", () -> eq(ConditionEvaluator.Status.BUDGET_EXCEEDED,new ConditionEvaluator().test(numberCondition("NE","0"),new ConditionEvaluator.Resolution(ConditionEvaluator.Status.BUDGET_EXCEEDED,""))));
        test("condition.evidence-only-passed-ids", () -> {var evaluator=new ConditionEvaluator();var e=evaluator.evaluate(List.of(numberCondition("GE","10")),Map.of("%player_level%",ConditionEvaluator.Resolution.value("20")));eq(Set.of("level"),e.satisfied());});
        test("condition.evidence-immutable", () -> {var e=new ConditionEvaluator().evaluate(List.of(numberCondition("GE","10")),Map.of());expect(UnsupportedOperationException.class,()->e.satisfied().add("x"));expect(UnsupportedOperationException.class,()->e.statuses().clear());});
        test("condition.duplicate-id-rejected", () -> illegal(()->new ConditionEvaluator().evaluate(List.of(numberCondition("GE","10"),numberCondition("GE","20")),Map.of())));
    }

    private static void costTests() {
        test("cost.attempt-plus-max-outcome-not-sum", () -> {var p=plan(cost(VAULT,"10","ON_ATTEMPT"),cost(VAULT,"20","ON_SUCCESS"),cost(VAULT,"30","ON_FAILURE"));amount("40",p.lines().getFirst().reserve());amount("30",p.lines().getFirst().consumed(true));amount("40",p.lines().getFirst().consumed(false));});
        test("cost.fee-multiplier-profile-only", () -> {var p=new CostPlanner().plan(List.of(cost(VAULT,"10","ON_ATTEMPT")),d("2"),List.of(cost(VAULT,"3","ON_ATTEMPT")));amount("23",p.lines().getFirst().reserve());});
        test("cost.merge-before-ceil-currency", () -> {var p=new CostPlanner().plan(List.of(cost(VAULT,"0.01","ON_ATTEMPT"),cost(VAULT,"0.01","ON_ATTEMPT")),d("0.5"),List.of());amount("0.01",p.lines().getFirst().reserve());});
        test("cost.item-fee-ceil-not-truncate", () -> {var p=new CostPlanner().plan(List.of(cost(GEM,"1","ON_ATTEMPT")),d("0.1"),List.of());amount("1",p.lines().getFirst().reserve());});
        test("cost.playerpoints-scaled-ceil-int", () -> {var p=new CostPlanner().plan(List.of(cost(POINTS,"3","ON_ATTEMPT")),d("0.5"),List.of());amount("2",p.lines().getFirst().reserve());});
        test("cost.playerpoints-raw-fraction-rejected", () -> illegal(()->cost(POINTS,"1.5","ON_ATTEMPT")));
        test("cost.item-raw-fraction-rejected", () -> illegal(()->cost(GEM,"1.5","ON_ATTEMPT")));
        test("cost.vault-raw-more-than-two-decimals-rejected", () -> illegal(()->cost(VAULT,"0.001","ON_ATTEMPT")));
        test("cost.amount-zero-rejected", () -> illegal(()->cost(VAULT,"0","ON_ATTEMPT")));
        test("cost.amount-negative-rejected", () -> illegal(()->cost(VAULT,"-1","ON_ATTEMPT")));
        test("cost.playerpoints-int-max-allowed", () -> amount("2147483647",cost(POINTS,"2147483647","ON_ATTEMPT").amount()));
        test("cost.playerpoints-overflow-rejected", () -> illegal(()->cost(POINTS,"2147483648","ON_ATTEMPT")));
        test("cost.scaled-total-overflow-rejected", () -> illegal(()->new CostPlanner().plan(List.of(cost(POINTS,"2147483647","ON_ATTEMPT")),d("2"),List.of())));
        test("cost.max-reservation-overflow-rejected", () -> illegal(()->plan(cost(POINTS,"2147483647","ON_ATTEMPT"),cost(POINTS,"1","ON_FAILURE"))));
        test("cost.only-success-fee-does-not-protect-free-retries", () -> check(!plan(cost(VAULT,"10","ON_SUCCESS")).hasFailureLoss()));
        test("cost.failure-fee-counts-loss", () -> check(plan(cost(VAULT,"10","ON_FAILURE")).hasFailureLoss()));
        test("cost.unknown-currency-rejected", () -> illegal(()->new CostResource(CostResource.Kind.CURRENCY,"coinz")));
        test("cost.resource-case-kept-for-custom-id", () -> eq("mmoitems:MATERIAL:Token",CostResource.item(k("mmoitems:MATERIAL:Token")).key()));
        test("cost.available-currency", () -> check(assess(plan(cost(VAULT,"10","ON_ATTEMPT")),funded("10",List.of())).available()));
        test("cost.insufficient-currency", () -> eq(ResourceAssessment.Status.INSUFFICIENT,assess(plan(cost(VAULT,"10","ON_ATTEMPT")),funded("9.99",List.of())).status()));
        test("cost.missing-provider-is-not-zero-balance", () -> {var a=assess(plan(cost(VAULT,"1","ON_ATTEMPT")),ResourceSnapshot.empty(VIEWER,0));eq(ResourceAssessment.Status.UNAVAILABLE,a.status());eq(ResourceAssessment.Reason.PROVIDER_UNAVAILABLE,a.issues().getFirst().reason());});
        test("cost.source-slot-never-used-for-boost", () -> {var supply=List.of(supply(0,10),supply(1,1));eq(ResourceAssessment.Status.INSUFFICIENT,assess(plan(cost(GEM,"2","ON_ATTEMPT")),funded("0",supply)).status());});
        test("cost.same-resource-profile-and-boost-do-not-double-count", () -> {var p=new CostPlanner().plan(List.of(cost(GEM,"2","ON_ATTEMPT")),d("1"),List.of(cost(GEM,"2","ON_FAILURE")));eq(ResourceAssessment.Status.INSUFFICIENT,assess(p,funded("0",List.of(supply(1,3)))).status());});
        test("cost.allocation-combines-multiple-slots", () -> {var a=assess(plan(cost(GEM,"4","ON_ATTEMPT")),funded("0",List.of(supply(2,3),supply(1,2))));check(a.available());eq(List.of(1,2),a.allocations().stream().map(ResourceAssessment.Allocation::slot).toList());eq(List.of(2,2),a.allocations().stream().map(ResourceAssessment.Allocation::amount).toList());});
        test("cost.no-partial-allocation-when-currency-missing", () -> {var a=assess(plan(cost(VAULT,"100","ON_ATTEMPT"),cost(GEM,"2","ON_ATTEMPT")),funded("0",List.of(supply(1,5))));check(a.allocations().isEmpty());});
        test("cost.missing-item-matcher-fails-closed", () -> {var a=assess(plan(cost(GEM,"1","ON_ATTEMPT")),ResourceSnapshot.empty(VIEWER,0));eq(ResourceAssessment.Reason.ITEM_MATCHER_UNAVAILABLE,a.issues().getFirst().reason());});
        test("cost.duplicate-physical-slots-rejected", () -> illegal(()->funded("0",List.of(supply(1,1),supply(1,1)))));
        test("cost.negative-balance-rejected", () -> illegal(()->funded("-1",List.of())));
        test("cost.playerpoints-fractional-balance-rejected", () -> illegal(()->new ResourceSnapshot(VIEWER,Map.of(POINTS,d("1.5")),Set.of(),List.of(),Set.of(0))));
        test("cost.playerpoints-balance-overflow-rejected", () -> illegal(()->new ResourceSnapshot(VIEWER,Map.of(POINTS,d("2147483648")),Set.of(),List.of(),Set.of(0))));
        test("cost.unverified-supply-rejected", () -> illegal(()->new ResourceSnapshot(VIEWER,Map.of(),Set.of(),List.of(supply(1,2)),Set.of(0))));
        test("cost.no-offhand-supply-slot", () -> illegal(()->new ResourceSnapshot.ItemSupply(40,k(GEM.key()),1,FP)));
        test("cost.snapshot-defensive-copies", () -> {var balances=new HashMap<CostResource,BigDecimal>();balances.put(VAULT,d("100"));var s=new ResourceSnapshot(VIEWER,balances,Set.of(),List.of(),Set.of(0));balances.clear();amount("100",s.balances().get(VAULT));expect(UnsupportedOperationException.class,()->s.excludedSlots().clear());});
        test("cost.plan-canonical-scale-equality", () -> eq(plan(cost(VAULT,"10","ON_ATTEMPT")),plan(cost(VAULT,"10.00","ON_ATTEMPT"))));
        test("cost.source-only-has-empty-available-cost-plan", () -> {var p=plan();check(p.lines().isEmpty());check(assess(p,ResourceSnapshot.empty(VIEWER,0)).available());});
    }

    private static void definitionTests() {
        test("rules.defaults-no-rng-no-fees", () -> {var r=UpgradeRules.defaults(catalog());eq("standard",r.settings().defaultProfile());check(r.profiles().get("standard").costs().isEmpty());});
        test("rules.no-default-profile-missing", () -> illegal(()->rules(catalog(),QuoteSettings.defaults(),List.of(),List.of(),List.of(),List.of(),List.of())));
        test("rules.no-disabled-default", () -> illegal(()->rules(catalog(),QuoteSettings.defaults(),List.of(profile("standard",false,"DESTROY",List.of(),"",Set.of())),List.of(),List.of(),List.of(),List.of())));
        test("rules.no-missing-formula-reference", () -> illegal(()->rules(catalog(),QuoteSettings.defaults(),List.of(new RiskProfile("standard",true,"unknown",d("1"),d("1"),RiskProfile.FailureMode.DESTROY,List.of(),"",Set.of())),List.of(),List.of(),List.of(),List.of())));
        test("rules.no-duplicate-profile-id", () -> illegal(()->rules(catalog(),QuoteSettings.defaults(),List.of(standard(),standard()),List.of(),List.of(),List.of(),List.of())));
        test("rules.no-unknown-boost-profile", () -> illegal(()->rules(catalog(),QuoteSettings.defaults(),List.of(standard()),List.of(boost("a",Set.of("ghost"),"",false,List.of())),List.of(),List.of(),List.of())));
        test("rules.keep-with-no-loss-rejected", () -> illegal(()->rules(catalog(),QuoteSettings.defaults(),List.of(profile("standard",true,"KEEP",List.of(),"",Set.of())),List.of(),List.of(),List.of(),List.of())));
        test("rules.keep-with-success-only-cost-rejected", () -> illegal(()->rules(catalog(),QuoteSettings.defaults(),List.of(profile("standard",true,"KEEP",List.of(cost(VAULT,"10","ON_SUCCESS")),"",Set.of())),List.of(),List.of(),List.of(),List.of())));
        test("rules.keep-explicit-free-policy-opt-in", () -> {var s=new QuoteSettings("standard",d("0.1"),d("90"),4,Duration.ofSeconds(30),true);var r=rules(catalog(),s,List.of(profile("standard",true,"KEEP",List.of(),"",Set.of())),List.of(),List.of(),List.of(),List.of());check(r.settings().allowFreeProtection());});
        test("rules.condition-unknown-reference-rejected", () -> illegal(()->rules(catalog(),QuoteSettings.defaults(),List.of(profile("standard",true,"DESTROY",List.of(),"",Set.of("unknown"))),List.of(),List.of(),List.of(),List.of())));
        test("rules.catalog-condition-cross-checked", () -> {var t=target();var gated=new TargetDefinition(t.id(),t.item(),1,t.name(),t.category(),Set.of(),true,0,"",Set.of("unknown"),Set.of());var c=new CatalogDefinitions(CatalogSettings.defaults(),List.of(gated),List.of());illegal(()->UpgradeRules.defaults(c));});
        test("rules.permission-group-no-priority-ties", () -> illegal(()->rules(catalog(),QuoteSettings.defaults(),List.of(standard()),List.of(),List.of(bonus("a",10,"vip","1.05"),bonus("b",10,"mvp","1.1")),List.of(),List.of())));
        test("rules.permission-highest-granted-per-group", () -> {var r=rules(catalog(),QuoteSettings.defaults(),List.of(standard()),List.of(),List.of(bonus("vip",10,"vip","1.05"),bonus("mvp",20,"mvp","1.1")),List.of(),List.of());eq(List.of("mvp"),r.permissionBonuses(access(Set.of("vip","mvp"),Set.of())).stream().map(PermissionBonus::id).toList());});
        test("rules.permission-lower-granted-still-applies", () -> {var r=rules(catalog(),QuoteSettings.defaults(),List.of(standard()),List.of(),List.of(bonus("vip",10,"vip","1.05"),bonus("mvp",20,"mvp","1.1")),List.of(),List.of());eq(List.of("vip"),r.permissionBonuses(access(Set.of("vip"),Set.of())).stream().map(PermissionBonus::id).toList());});
        test("rules.path-profile-reference-validated", () -> illegal(()->rules(catalog(),QuoteSettings.defaults(),List.of(standard()),List.of(),List.of(),List.of(),List.of(new PathProfileRule("unknown",Set.of("standard"),"standard")))));
        test("rules.path-default-must-be-allowed", () -> illegal(()->new PathProfileRule("iron",Set.of("standard"),"safe")));
        test("rules.quote-lifetime-bounded", () -> {illegal(()->new QuoteSettings("standard",d("0"),d("90"),4,Duration.ZERO,false));illegal(()->new QuoteSettings("standard",d("0"),d("90"),4,Duration.ofMinutes(6),false));});
        test("rules.boost-count-bounded", () -> illegal(()->new QuoteSettings("standard",d("0"),d("90"),9,Duration.ofSeconds(30),false)));
        test("rules.no-noop-booster", () -> illegal(()->new BoostDefinition("nope",true,"",Set.of(),d("1"),d("0"),BoostDefinition.Protection.NONE,List.of(),"",Set.of())));
    }

    private static void quoteTests() {
        test("quote.standard-integrates-catalog-value", () -> {var c=catalog();var q=quote(UpgradeRules.defaults(c),index(c),access(),request("",List.of()),resources());amount("90",q.sourceTotal());amount("900",q.targetTotal());amount("9",q.chance().probability().percent());check(q.resources().available());});
        test("quote.source-owner-bound", () -> {var c=catalog();var r=UpgradeRules.defaults(c);var result=QUOTES.quote(request("",List.of()),source(),index(c),access(),r,ResourceSnapshot.empty(UUID.randomUUID(),0),NOW);eq(QuoteResult.Status.WRONG_RESOURCE_OWNER,result.status());});
        test("quote.source-slot-must-be-excluded", () -> {var c=catalog();var r=UpgradeRules.defaults(c);var result=QUOTES.quote(request("",List.of()),source(),index(c),access(),r,new ResourceSnapshot(VIEWER,Map.of(),Set.of(),List.of(),Set.of()),NOW);eq(QuoteResult.Status.SOURCE_SLOT_NOT_EXCLUDED,result.status());});
        test("quote.wrong-rules-catalog-instance-rejected", () -> {var c=catalog();eq(QuoteResult.Status.STALE_RULES,result(UpgradeRules.defaults(c),index(catalog()),access(),request("",List.of()),resources()).status());});
        test("quote.target-denied", () -> {var c=catalog();eq(QuoteResult.Status.TARGET_DENIED,result(UpgradeRules.defaults(c),index(c),access(),new QuoteRequest(SESSION,"unknown","",List.of(),0),resources()).status());});
        test("quote.profile-unknown-denied", () -> {var c=catalog();eq(QuoteResult.Status.PROFILE_DENIED,result(UpgradeRules.defaults(c),index(c),access(),request("ghost",List.of()),resources()).status());});
        test("quote.profile-permission-denied", () -> {var c=catalog();var r=rules(c,QuoteSettings.defaults(),List.of(profile("standard",true,"DESTROY",List.of(),"vip",Set.of())),List.of(),List.of(),List.of(),List.of());eq(QuoteResult.Status.PROFILE_DENIED,result(r,index(c),access(),request("",List.of()),resources()).status());});
        test("quote.profile-condition-required", () -> {var c=catalog();var r=rules(c,QuoteSettings.defaults(),List.of(profile("standard",true,"DESTROY",List.of(),"",Set.of("level"))),List.of(),List.of(),List.of(numberCondition("GE","50")),List.of());eq(QuoteResult.Status.PROFILE_DENIED,result(r,index(c),access(),request("",List.of()),resources()).status());eq(QuoteResult.Status.QUOTED,result(r,index(c),access(Set.of(),Set.of("level")),request("",List.of()),resources()).status());});
        test("quote.high-priority-path-denied-no-fallback", () -> {var c=new CatalogDefinitions(CatalogSettings.defaults(),List.of(target()),List.of(new UpgradePath("high",Set.of(IRON),100,UpgradePath.Mode.LOCKED,List.of("diamond"),true,"vip",Set.of()),new UpgradePath("low",Set.of(IRON),0,UpgradePath.Mode.OPEN,List.of("diamond"),true,"",Set.of())));eq(QuoteResult.Status.TARGET_DENIED,result(UpgradeRules.defaults(c),index(c),access(),request("",List.of()),resources()).status());});
        test("quote.path-profile-lock-respected", () -> {var c=catalogWithPath();var safe=profile("safe",true,"KEEP",List.of(cost(VAULT,"10","ON_ATTEMPT")),"",Set.of());var r=rules(c,QuoteSettings.defaults(),List.of(standard(),safe),List.of(),List.of(),List.of(),List.of(new PathProfileRule("iron",Set.of("standard"),"standard")));eq(QuoteResult.Status.PROFILE_DENIED,result(r,index(c),access(),request("safe",List.of()),resources()).status());});
        test("quote.path-default-profile-selected", () -> {var c=catalogWithPath();var safe=profile("safe",true,"KEEP",List.of(cost(VAULT,"10","ON_ATTEMPT")),"",Set.of());var r=rules(c,QuoteSettings.defaults(),List.of(standard(),safe),List.of(),List.of(),List.of(),List.of(new PathProfileRule("iron",Set.of("safe"),"safe")));eq("safe",quote(r,index(c),access(),request("",List.of()),resources()).terms().profileId());});
        test("quote.path-default-permission-denied-no-fallback", () -> {var c=catalogWithPath();var safe=profile("safe",true,"KEEP",List.of(cost(VAULT,"10","ON_ATTEMPT")),"vip",Set.of());var r=rules(c,QuoteSettings.defaults(),List.of(standard(),safe),List.of(),List.of(),List.of(),List.of(new PathProfileRule("iron",Set.of("safe","standard"),"safe")));eq(QuoteResult.Status.PROFILE_DENIED,result(r,index(c),access(),request("",List.of()),resources()).status());});
        test("quote.boost-unknown-denied", () -> {var c=catalog();eq(QuoteResult.Status.BOOST_DENIED,result(UpgradeRules.defaults(c),index(c),access(),request("",List.of("ghost")),resources()).status());});
        test("quote.boost-outside-profile-denied", () -> {var c=catalog();var r=rules(c,QuoteSettings.defaults(),List.of(standard(),profile("other",true,"DESTROY",List.of(),"",Set.of())),List.of(boost("lucky",Set.of("other"),"",false,List.of())),List.of(),List.of(),List.of());eq(QuoteResult.Status.BOOST_DENIED,result(r,index(c),access(),request("",List.of("lucky")),resources()).status());});
        test("quote.exclusive-group-conflict", () -> {var c=catalog();var r=rules(c,QuoteSettings.defaults(),List.of(standard()),List.of(boost("a",Set.of(),"luck",false,List.of()),boost("b",Set.of(),"luck",false,List.of())),List.of(),List.of(),List.of());eq(QuoteResult.Status.BOOST_CONFLICT,result(r,index(c),access(),request("",List.of("a","b")),resources()).status());});
        test("quote.two-protections-conflict-even-different-groups", () -> {var c=catalog();var r=rules(c,QuoteSettings.defaults(),List.of(standard()),List.of(boost("a",Set.of(),"one",true,List.of(cost(GEM,"1","ON_FAILURE"))),boost("b",Set.of(),"two",true,List.of(cost(GEM,"1","ON_FAILURE")))),List.of(),List.of(),List.of());eq(QuoteResult.Status.BOOST_CONFLICT,result(r,index(c),access(),request("",List.of("a","b")),resources()).status());});
        test("quote.redundant-protection-on-keep-denied", () -> {var c=catalog();var r=rules(c,QuoteSettings.defaults(),List.of(profile("standard",true,"KEEP",List.of(cost(VAULT,"10","ON_ATTEMPT")),"",Set.of())),List.of(boost("p",Set.of(),"",true,List.of(cost(GEM,"1","ON_FAILURE")))),List.of(),List.of(),List.of());eq(QuoteResult.Status.BOOST_CONFLICT,result(r,index(c),access(),request("",List.of("p")),resources()).status());});
        test("quote.free-protection-without-loss-denied", () -> {var c=catalog();var r=rules(c,QuoteSettings.defaults(),List.of(standard()),List.of(boost("p",Set.of(),"",true,List.of())),List.of(),List.of(),List.of());eq(QuoteResult.Status.FREE_PROTECTION_DENIED,result(r,index(c),access(),request("",List.of("p")),resources()).status());});
        test("quote.protection-success-only-cost-denied", () -> {var c=catalog();var r=rules(c,QuoteSettings.defaults(),List.of(standard()),List.of(boost("p",Set.of(),"",true,List.of(cost(GEM,"1","ON_SUCCESS")))),List.of(),List.of(),List.of());eq(QuoteResult.Status.FREE_PROTECTION_DENIED,result(r,index(c),access(),request("",List.of("p")),resources()).status());});
        test("quote.protection-on-failure-reserved-in-preview", () -> {var c=catalog();var r=protectedRules(c);var q=quote(r,index(c),access(),request("",List.of("protection")),resources());eq(RiskProfile.FailureMode.KEEP,q.terms().failure());var line=q.terms().costs().lines().getFirst();amount("1",line.reserve());amount("0",line.consumed(true));amount("1",line.consumed(false));});
        test("quote.source-only-needs-no-economy-provider", () -> {var c=catalog();check(quote(UpgradeRules.defaults(c),index(c),access(),request("",List.of()),ResourceSnapshot.empty(VIEWER,0)).resources().available());});
        test("quote.missing-currency-quotes-unavailable-not-free", () -> {var c=catalog();var r=rules(c,QuoteSettings.defaults(),List.of(profile("standard",true,"DESTROY",List.of(cost(VAULT,"10","ON_ATTEMPT")),"",Set.of())),List.of(),List.of(),List.of(),List.of());eq(ResourceAssessment.Status.UNAVAILABLE,quote(r,index(c),access(),request("",List.of()),ResourceSnapshot.empty(VIEWER,0)).resources().status());});
        test("quote.boost-shortage-does-not-silently-remove-boost", () -> {var c=catalog();var r=rules(c,QuoteSettings.defaults(),List.of(standard()),List.of(boost("lucky",Set.of(),"",false,List.of(cost(GEM,"20","ON_ATTEMPT")))),List.of(),List.of(),List.of());var q=quote(r,index(c),access(),request("",List.of("lucky")),resources());amount("14",q.chance().probability().percent());eq(ResourceAssessment.Status.INSUFFICIENT,q.resources().status());});
        test("quote.max-selected-boosts-enforced", () -> {var c=catalog();var s=new QuoteSettings("standard",d("0"),d("90"),0,Duration.ofSeconds(30),false);var r=rules(c,s,List.of(standard()),List.of(boost("a",Set.of(),"",false,List.of())),List.of(),List.of(),List.of());eq(QuoteResult.Status.BOOST_DENIED,result(r,index(c),access(),request("",List.of("a")),resources()).status());});
        test("quote.valid-revalidation", () -> {var c=catalog();var i=index(c);var r=UpgradeRules.defaults(c);var q=quote(r,i,access(),request("",List.of()),resources());eq(UpgradeQuoteService.ValidationStatus.VALID_PREVIEW,validate(q,i,r,access(),source(),resources(),NOW.plusSeconds(1)).status());});
        test("quote.expiry-inclusive", () -> {var c=catalog();var i=index(c);var r=UpgradeRules.defaults(c);var q=quote(r,i,access(),request("",List.of()),resources());eq(UpgradeQuoteService.ValidationStatus.EXPIRED,validate(q,i,r,access(),source(),resources(),NOW.plusSeconds(30)).status());});
        test("quote.wrong-viewer", () -> {var c=catalog();var i=index(c);var r=UpgradeRules.defaults(c);var q=quote(r,i,access(),request("",List.of()),resources());eq(UpgradeQuoteService.ValidationStatus.WRONG_VIEWER,validate(q,i,r,new CatalogAccess(UUID.randomUUID(),Set.of(),Set.of()),source(),resources(),NOW).status());});
        test("quote.wrong-session", () -> {var c=catalog();var i=index(c);var r=UpgradeRules.defaults(c);var q=quote(r,i,access(),request("",List.of()),resources());eq(UpgradeQuoteService.ValidationStatus.WRONG_SESSION,QUOTES.revalidate(q,UUID.randomUUID(),source(),i,access(),r,resources(),NOW).status());});
        test("quote.catalog-rebuild-invalidates", () -> {var c=catalog();var i=index(c);var r=UpgradeRules.defaults(c);var q=quote(r,i,access(),request("",List.of()),resources());eq(UpgradeQuoteService.ValidationStatus.STALE_SELECTION,validate(q,index(c),r,access(),source(),resources(),NOW).status());});
        test("quote.source-change-invalidates", () -> {var c=catalog();var i=index(c);var r=UpgradeRules.defaults(c);var q=quote(r,i,access(),request("",List.of()),resources());eq(UpgradeQuoteService.ValidationStatus.STALE_SELECTION,validate(q,i,r,access(),new ItemSnapshot(ItemFacts.clean(IRON,2),new byte[]{1,2,3}),resources(),NOW).status());});
        test("quote.permission-bonus-loss-requires-reconfirmation", () -> {var c=catalog();var i=index(c);var r=rules(c,QuoteSettings.defaults(),List.of(standard()),List.of(),List.of(bonus("vip",1,"vip","1.05")),List.of(),List.of());var q=quote(r,i,access(Set.of("vip"),Set.of()),request("",List.of()),resources());var v=validate(q,i,r,access(),source(),resources(),NOW.plusSeconds(1));eq(UpgradeQuoteService.ValidationStatus.RECONFIRM_REQUIRED,v.status());amount("9",v.replacement().orElseThrow().chance().probability().percent());amount("9.45",q.chance().probability().percent());});
        test("quote.profile-access-loss-no-longer-eligible", () -> {var c=catalog();var i=index(c);var r=rules(c,QuoteSettings.defaults(),List.of(profile("standard",true,"DESTROY",List.of(),"vip",Set.of())),List.of(),List.of(),List.of(),List.of());var q=quote(r,i,access(Set.of("vip"),Set.of()),request("",List.of()),resources());eq(UpgradeQuoteService.ValidationStatus.NO_LONGER_ELIGIBLE,validate(q,i,r,access(),source(),resources(),NOW).status());});
        test("quote.resources-lost-blocks-not-rerolls", () -> {var c=catalog();var i=index(c);var r=protectedRules(c);var q=quote(r,i,access(),request("",List.of("protection")),resources());var v=validate(q,i,r,access(),source(),funded("100",List.of()),NOW);eq(UpgradeQuoteService.ValidationStatus.INSUFFICIENT_RESOURCES,v.status());eq(q.chance().probability(),v.replacement().orElseThrow().chance().probability());});
        test("quote.provider-lost-blocks-not-free", () -> {var c=catalog();var i=index(c);var r=protectedRules(c);var q=quote(r,i,access(),request("",List.of("protection")),resources());eq(UpgradeQuoteService.ValidationStatus.RESOURCES_UNAVAILABLE,validate(q,i,r,access(),source(),ResourceSnapshot.empty(VIEWER,0),NOW).status());});
        test("quote.balance-changes-with-enough-funds-do-not-change-terms", () -> {var c=catalog();var i=index(c);var r=rules(c,QuoteSettings.defaults(),List.of(profile("standard",true,"DESTROY",List.of(cost(VAULT,"10","ON_ATTEMPT")),"",Set.of())),List.of(),List.of(),List.of(),List.of());var q=quote(r,i,access(),request("",List.of()),resources());eq(UpgradeQuoteService.ValidationStatus.VALID_PREVIEW,validate(q,i,r,access(),source(),funded("20",List.of()),NOW).status());});
        test("quote.revalidation-does-not-mutate-old-expiry", () -> {var c=catalog();var i=index(c);var r=UpgradeRules.defaults(c);var q=quote(r,i,access(),request("",List.of()),resources());var v=validate(q,i,r,access(),source(),resources(),NOW.plusSeconds(29));eq(NOW.plusSeconds(30),q.selection().expiresAt());eq(q.selection().expiresAt(),v.replacement().orElseThrow().selection().expiresAt());eq(q.quoteId(),v.replacement().orElseThrow().quoteId());});
    }

    private static void argumentTests() {
        test("arguments.catalog-v2-identifiers-not-regressed", () -> {var id="a."+"b".repeat(62);eq(id,QuoteArguments.parse(List.of(id),SESSION,0).targetId());});
        test("arguments.eight-64-character-boost-identifiers", () -> {var ids=new ArrayList<String>();for(int n=0;n<8;n++)ids.add("b"+n+"x".repeat(62));eq(8,QuoteArguments.parse(List.of("diamond","default",String.join(",",ids)),SESSION,0).boostIds().size());});
        test("arguments.quote-defaults", () -> {var q=QuoteArguments.parse(List.of("diamond"),SESSION,0);eq("",q.profileId());check(q.boostIds().isEmpty());});
        test("arguments.quote-comma-canonical-order", () -> eq(List.of("a","b"),QuoteArguments.parse(List.of("diamond","default","b,a"),SESSION,0).boostIds()));
        test("arguments.quote-none", () -> check(QuoteArguments.parse(List.of("diamond","standard","none"),SESSION,0).boostIds().isEmpty()));
        test("arguments.quote-no-duplicate-boost", () -> illegal(()->QuoteArguments.parse(List.of("diamond","default","a,a"),SESSION,0)));
        test("arguments.quote-no-trailing-empty-boost", () -> illegal(()->QuoteArguments.parse(List.of("diamond","default","a,"),SESSION,0)));
        test("arguments.quote-no-command-injection", () -> illegal(()->QuoteArguments.parse(List.of("diamond;op"),SESSION,0)));
        test("arguments.quote-no-control", () -> illegal(()->QuoteArguments.parse(List.of("diamond\n"),SESSION,0)));
        test("arguments.quote-no-huge-token", () -> illegal(()->QuoteArguments.parse(List.of("a".repeat(601)),SESSION,0)));
        test("arguments.quote-no-missing-target", () -> illegal(()->QuoteArguments.parse(List.of(),SESSION,0)));
    }

    private static void invariantTests() {
        test("invariant.4000-ratio-quotes-against-big-integer-oracle", () -> {
            var random=new Random(30317);
            for(int i=0;i<4000;i++) {
                long source=1+random.nextInt(999_999);long target=source+1+random.nextInt(99_999_999);
                int profile=1+random.nextInt(200);int bonus=1+random.nextInt(200);int points=random.nextInt(10);
                var q=MATH.calculate(BigDecimal.valueOf(source),BigDecimal.valueOf(target),ratio(),BigDecimal.valueOf(profile,2),List.of(adj("p",BigDecimal.valueOf(bonus,2).toPlainString(),"0")),List.of(adj("b","1",String.valueOf(points))),d("0"),d("90"));
                // Independently construct: s/t * 9/10 * profile/100 * bonus/100 + points/100.
                BigInteger den=BigInteger.valueOf(target).multiply(BigInteger.valueOf(100000));
                BigInteger num=BigInteger.valueOf(source).multiply(BigInteger.valueOf(9L*profile*bonus)).add(den.multiply(BigInteger.valueOf(points)).divide(BigInteger.valueOf(100)));
                long expected=num.multiply(BigInteger.valueOf(Probability.DENOMINATOR)).divide(den).min(BigInteger.valueOf(900000000L)).longValueExact();
                eq(expected,q.probability().winningTickets());
            }
        });
        test("invariant.2000-monotonic-source-values-four-formulas", () -> {
            var formulas=List.<ChanceFormula>of(ratio(),new ChanceFormula.Power(d("0.9"),2),new ChanceFormula.Table(List.of(point("0","0"),point("0.25","20"),point("0.5","45"))),new ChanceFormula.Curve(List.of(point("0","0"),point("0.25","20"),point("1","90"))));
            for(var f:formulas){long previous=-1;for(int source=1;source<=2000;source++){long tickets=MATH.calculate(BigDecimal.valueOf(source),d("2001"),f,d("1"),List.of(),List.of(),d("0"),d("90")).probability().winningTickets();check(tickets>=previous);previous=tickets;}}
        });
        test("invariant.256-adjustment-order-shuffles", () -> {
            var adjustments=new ArrayList<>(List.of(adj("a","1.05","1"),adj("b","1.4","2"),adj("c","0.8","3"),adj("d","1.2","0")));
            var expected=calc(ratio(),List.of(),adjustments).probability();var random=new Random(3);
            for(int i=0;i<256;i++){Collections.shuffle(adjustments,random);eq(expected,calc(ratio(),List.of(),adjustments).probability());}
        });
        test("invariant.1000-cost-outcome-reservations-independent-oracle", () -> {
            var random=new Random(4);
            for(int i=0;i<1000;i++) {
                int a=1+random.nextInt(100),s=1+random.nextInt(100),f=1+random.nextInt(100),m=1+random.nextInt(200);
                var plan=new CostPlanner().plan(List.of(cost(POINTS,String.valueOf(a),"ON_ATTEMPT"),cost(POINTS,String.valueOf(s),"ON_SUCCESS"),cost(POINTS,String.valueOf(f),"ON_FAILURE")),BigDecimal.valueOf(m,2),List.of());
                long attempt=(a*m+99)/100,success=(s*m+99)/100,failure=(f*m+99)/100;
                amount(String.valueOf(attempt+Math.max(success,failure)),plan.lines().getFirst().reserve());
                amount(String.valueOf(attempt+success),plan.lines().getFirst().consumed(true));
                amount(String.valueOf(attempt+failure),plan.lines().getFirst().consumed(false));
            }
        });
        test("invariant.concurrent-256-pure-quotes-same-terms", () -> {
            var c=catalog();var i=index(c);var r=UpgradeRules.defaults(c);var expected=quote(r,i,access(),request("",List.of()),resources()).terms();
            var executor=Executors.newFixedThreadPool(4);
            try {var jobs=new ArrayList<Callable<UpgradeQuote.Terms>>();for(int n=0;n<256;n++)jobs.add(()->quote(r,i,access(),request("",List.of()),resources()).terms());for(var f:executor.invokeAll(jobs,10,TimeUnit.SECONDS))eq(expected,f.get());}
            finally {executor.shutdownNow();check(executor.awaitTermination(5,TimeUnit.SECONDS));}
        });
    }

    private static ChanceFormula ratio(){return new ChanceFormula.Ratio(d("0.9"));}
    private static ChanceFormula.Point point(String ratio,String percent){return new ChanceFormula.Point(d(ratio),d(percent));}
    private static ChanceAdjustment adj(String id,String multiplier,String points){return new ChanceAdjustment(id,d(multiplier),d(points));}
    private static ChanceQuote chance(String source,String target){return MATH.calculate(d(source),d(target),ratio(),d("1"),List.of(),List.of(),d("0.1"),d("90"));}
    private static ChanceQuote calc(ChanceFormula formula,List<ChanceAdjustment> bonuses,List<ChanceAdjustment> boosts){return MATH.calculate(d("100"),d("1000"),formula,d("1"),bonuses,boosts,d("0.1"),d("90"));}
    private static ConditionDefinition numberCondition(String op,String expected){return new ConditionDefinition("level","%player_level%",ConditionDefinition.Type.NUMBER,ConditionDefinition.Operator.valueOf(op),expected);}
    private static ConditionEvaluator.Status condition(ConditionDefinition c,String raw){return new ConditionEvaluator().test(c,ConditionEvaluator.Resolution.value(raw));}
    private static CostEntry cost(CostResource resource,String amount,String when){return new CostEntry(resource,d(amount),CostEntry.ConsumeWhen.valueOf(when));}
    private static CostPlan plan(CostEntry... costs){return new CostPlanner().plan(List.of(costs),d("1"),List.of());}
    private static ResourceAssessment assess(CostPlan plan,ResourceSnapshot snapshot){return new ResourceAssessor().assess(plan,snapshot);}
    private static ResourceSnapshot.ItemSupply supply(int slot,int count){return new ResourceSnapshot.ItemSupply(slot,k(GEM.key()),count,FP);}
    private static ResourceSnapshot funded(String money,List<ResourceSnapshot.ItemSupply> supplies){return new ResourceSnapshot(VIEWER,Map.of(VAULT,d(money)),Set.of(k(GEM.key())),supplies,Set.of(0));}
    private static ResourceSnapshot resources(){return funded("1000",List.of(supply(1,10)));}
    private static RiskProfile standard(){return profile("standard",true,"DESTROY",List.of(),"",Set.of());}
    private static RiskProfile profile(String id,boolean enabled,String fail,List<CostEntry> costs,String permission,Set<String> conditions){return new RiskProfile(id,enabled,"ratio",d("1"),d("1"),RiskProfile.FailureMode.valueOf(fail),costs,permission,conditions);}
    private static BoostDefinition boost(String id,Set<String> profiles,String group,boolean protection,List<CostEntry> costs){return new BoostDefinition(id,true,group,profiles,d("1"),d(protection?"0":"5"),protection?BoostDefinition.Protection.KEEP_SOURCE_ON_FAILURE:BoostDefinition.Protection.NONE,costs,"",Set.of());}
    private static PermissionBonus bonus(String id,int priority,String permission,String multiplier){return new PermissionBonus(id,true,"rank",priority,permission,Set.of(),d(multiplier),d("0"));}
    private static TargetDefinition target(){return new TargetDefinition("diamond",DIAMOND,1,"<aqua>Diamond</aqua>","materials",Set.of(),true,0,"",Set.of(),Set.of());}
    private static CatalogDefinitions catalog(){return new CatalogDefinitions(CatalogSettings.defaults(),List.of(target()),List.of());}
    private static CatalogDefinitions catalogWithPath(){return new CatalogDefinitions(CatalogSettings.defaults(),List.of(target()),List.of(new UpgradePath("iron",Set.of(IRON),0,UpgradePath.Mode.LOCKED,List.of("diamond"),true,"",Set.of())));}
    private static ItemSnapshot source(){return new ItemSnapshot(ItemFacts.clean(IRON,1),new byte[]{1,2,3});}
    private static CatalogIndex index(CatalogDefinitions c){return CatalogIndex.build(1,c,ValueDefinitions.manualOnly(Map.of(IRON,d("90"),DIAMOND,d("900"))),Map.of("diamond",TargetProbe.verified(new ItemSnapshot(ItemFacts.clean(DIAMOND,1),new byte[]{4,5,6}))));}
    private static UpgradeRules rules(CatalogDefinitions c,QuoteSettings settings,List<RiskProfile> profiles,List<BoostDefinition> boosts,List<PermissionBonus> bonuses,List<ConditionDefinition> conditions,List<PathProfileRule> paths){return new UpgradeRules(c,settings,Map.of("ratio",ratio()),profiles,boosts,bonuses,conditions,paths);}
    private static UpgradeRules protectedRules(CatalogDefinitions c){return rules(c,QuoteSettings.defaults(),List.of(standard()),List.of(boost("protection",Set.of(),"",true,List.of(cost(GEM,"1","ON_FAILURE")))),List.of(),List.of(),List.of());}
    private static QuoteRequest request(String profile,List<String> boosts){return new QuoteRequest(SESSION,"diamond",profile,boosts,0);}
    private static CatalogAccess access(){return access(Set.of(),Set.of());}
    private static CatalogAccess access(Set<String> permissions,Set<String> conditions){return new CatalogAccess(VIEWER,permissions,conditions);}
    private static QuoteResult result(UpgradeRules r,CatalogIndex i,CatalogAccess a,QuoteRequest request,ResourceSnapshot resources){return QUOTES.quote(request,source(),i,a,r,resources,NOW);}
    private static UpgradeQuote quote(UpgradeRules r,CatalogIndex i,CatalogAccess a,QuoteRequest request,ResourceSnapshot resources){return result(r,i,a,request,resources).quote().orElseThrow();}
    private static UpgradeQuoteService.Validation validate(UpgradeQuote q,CatalogIndex i,UpgradeRules r,CatalogAccess a,ItemSnapshot source,ResourceSnapshot resources,Instant now){return QUOTES.revalidate(q,SESSION,source,i,a,r,resources,now);}
    private static void test(String name,Checked body){TESTS.add(new Test(name,body));}
    private static ItemKey k(String key){return ItemKey.of(key);}
    private static BigDecimal d(String value){return new BigDecimal(value);}
    private static void illegal(Runnable body) { expect(IllegalArgumentException.class,body); }
    private static void expect(Class<? extends Throwable> type,Runnable body) { assertions++; try { body.run(); } catch(Throwable error) { if(type.isInstance(error)) return; throw new AssertionError("Expected " + type + " but got " + error,error); } throw new AssertionError("Expected " + type.getSimpleName()); }
    private static void check(boolean condition) { assertions++; if(!condition) throw new AssertionError("condition failed"); }
    private static void eq(Object expected,Object actual) { assertions++; if(!java.util.Objects.equals(expected,actual)) throw new AssertionError("Expected " + expected + "; got " + actual); }
    private static void amount(String expected,BigDecimal actual) { assertions++; if(d(expected).compareTo(actual)!=0) throw new AssertionError("Expected decimal " + expected + "; got " + actual); }
    private static String escape(String text) { return text.replace("&","&amp;").replace("\"","&quot;").replace("<","&lt;").replace(">","&gt;"); }
}
