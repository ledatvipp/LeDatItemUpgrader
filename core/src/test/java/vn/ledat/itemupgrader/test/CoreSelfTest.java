package vn.ledat.itemupgrader.test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import vn.ledat.itemupgrader.gui.*;
import vn.ledat.itemupgrader.item.*;
import vn.ledat.itemupgrader.recipe.*;
import vn.ledat.itemupgrader.runtime.RuntimeStore;
import vn.ledat.itemupgrader.storage.SchemaPlan;
import vn.ledat.itemupgrader.util.Decimals;
import vn.ledat.itemupgrader.value.*;

/** Dependency-free unit/invariant suite. No Bukkit mocks and no fabricated Platform SDK. */
public final class CoreSelfTest {
    @FunctionalInterface interface Checked { void run() throws Exception; }
    record Test(String name, Checked body) {}
    record Result(String name, double seconds, String failure) {}
    private static final List<Test> TESTS = new ArrayList<>();
    private static int assertions;
    private static final ItemKey A = k("minecraft:iron_ingot");
    private static final ItemKey B = k("minecraft:iron_block");
    private static final ItemKey C = k("minecraft:gold_ingot");
    private static final ItemKey UNKNOWN = k("minecraft:stone");
    private static final ItemValueService ENGINE = new ItemValueService();
    private CoreSelfTest() {}
    public static void main(String[] args) throws Exception {
        identityTests(); decimalTests(); snapshotTests(); valueTests(); recipeTests(); menuTests(); lifecycleTests(); storageTests(); propertyTests();
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
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuite name=\"ItemUpgraderCore\" tests=\"" + results.size()
                + "\" failures=\"" + failures + "\" errors=\"0\">\n");
        for (Result result : results) {
            xml.append("  <testcase classname=\"ItemUpgraderCore\" name=\"").append(escape(result.name())).append("\" time=\"")
                    .append(String.format(java.util.Locale.ROOT, "%.6f", result.seconds())).append("\">");
            if (result.failure() != null) xml.append("<failure message=\"").append(escape(result.failure())).append("\"/>");
            xml.append("</testcase>\n");
        }
        xml.append("  <system-out>assertions=").append(assertions).append("; No Paper/Platform runtime tests in this suite.</system-out>\n</testsuite>\n");
        Path report = Path.of(args.length == 0 ? "build/reports/self-test.xml" : args[0]);
        Files.createDirectories(report.toAbsolutePath().getParent()); Files.writeString(report, xml, StandardCharsets.UTF_8);
        System.out.println("RESULT tests=" + results.size() + " assertions=" + assertions + " failures=" + failures);
        if (failures != 0) throw new AssertionError(failures + " failed test groups");
    }
    private static void identityTests() {
        test("key.vanilla", () -> eq("minecraft:diamond", k("minecraft:diamond").value()));
        test("key.provider-normalization-only", () -> eq("mmoitems:SWORD:Dragon_Blade", k("MMOITEMS:SWORD:Dragon_Blade").value()));
        test("key.custom-case-is-significant", () -> check(!k("oraxen:Ruby").equals(k("oraxen:ruby"))));
        test("key.itemsadder-three-parts", () -> eq("itemsadder", k("itemsadder:server:ruby").provider()));
        test("key.nexo", () -> check(!k("nexo:staff").vanilla()));
        test("key.no-unknown-provider", () -> illegal(() -> k("unknown:item")));
        test("key.no-implicit-provider", () -> illegal(() -> k("diamond")));
        test("key.no-empty-component", () -> illegal(() -> k("mmoitems::staff")));
        test("key.no-extra-component", () -> illegal(() -> k("minecraft:a:b")));
        test("key.no-command-injection", () -> illegal(() -> k("nexo:sword;op player")));
        test("key.no-newline", () -> illegal(() -> k("nexo:staff\n")));
        test("key.no-uppercase-vanilla", () -> illegal(() -> k("minecraft:DIAMOND")));
        test("key.no-uppercase-itemsadder", () -> illegal(() -> k("itemsadder:server:Ruby")));
        test("key.no-oversized-id", () -> illegal(() -> k("nexo:" + "a".repeat(201))));
    }
    private static void decimalTests() {
        test("decimal.exact", () -> amount("123456789012345678.12345678", Decimals.parse("123456789012345678.12345678", "x")));
        test("decimal.reject-nan", () -> illegal(() -> Decimals.parse("NaN", "x")));
        test("decimal.reject-infinity", () -> illegal(() -> Decimals.parse("Infinity", "x")));
        test("decimal.reject-negative", () -> illegal(() -> Decimals.parse("-1", "x")));
        test("decimal.reject-exponent-bomb", () -> illegal(() -> Decimals.parse("1e999999999", "x")));
        test("decimal.reject-excessive-fraction", () -> illegal(() -> Decimals.parse("1.000000001", "x")));
        test("decimal.reject-whitespace", () -> illegal(() -> Decimals.parse(" 1", "x")));
        test("decimal.reject-leading-zero", () -> illegal(() -> Decimals.parse("01", "x")));
        test("decimal.floor", () -> amount("1.123456", Decimals.floor(d("1.1234569"), 6)));
        test("decimal.no-double-artifact", () -> amount("0.3", Decimals.parse("0.1", "x").add(Decimals.parse("0.2", "y"))));
    }
    private static void snapshotTests() {
        test("snapshot.defensive-input-copy", () -> { byte[] b = {1,2,3}; var s = new ItemSnapshot(ItemFacts.clean(A,1),b); b[0]=9; eq((byte)1, s.bytes()[0]); });
        test("snapshot.defensive-output-copy", () -> { var s = snap(A,1); byte[] b = s.bytes(); b[0]=9; check(s.bytes()[0] != 9); });
        test("snapshot.hash-includes-bytes", () -> check(!new ItemSnapshot(ItemFacts.clean(A,1),new byte[]{1}).fingerprint()
                .equals(new ItemSnapshot(ItemFacts.clean(A,1),new byte[]{2}).fingerprint())));
        test("snapshot.hash-includes-amount", () -> check(!snap(A,1).fingerprint().equals(snap(A,2).fingerprint())));
        test("snapshot.hash-includes-key", () -> check(!snap(A,1).fingerprint().equals(snap(B,1).fingerprint())));
        test("snapshot.sha256-length", () -> eq(64, snap(A,1).fingerprint().length()));
        test("snapshot.no-empty-payload", () -> illegal(() -> new ItemSnapshot(ItemFacts.clean(A,1),new byte[0])));
        test("snapshot.hard-size-limit", () -> illegal(() -> new ItemSnapshot(ItemFacts.clean(A,1),new byte[1_048_577])));
        test("snapshot.no-negative-damage", () -> illegal(() -> facts(A,1,-1,100,Map.of(),"",Set.of())));
        test("snapshot.no-damage-over-maximum", () -> illegal(() -> facts(A,1,101,100,Map.of(),"",Set.of())));
        test("snapshot.no-zero-amount", () -> illegal(() -> ItemFacts.clean(A,0)));
        test("snapshot.no-untrusted-rarity-tags", () -> illegal(() -> facts(A,1,0,0,Map.of(),"<red>EPIC",Set.of())));
        test("snapshot.enchant-map-immutable", () -> { var map = new HashMap<String,Integer>(); map.put("minecraft:sharpness",1);
            var f = facts(A,1,0,0,map,"",Set.of()); map.put("minecraft:sharpness",8); eq(1,f.enchantments().get("minecraft:sharpness")); });
    }
    private static void valueTests() {
        test("value.manual-unit-and-total", () -> { var q = quote(snap(A,16), ValueDefinitions.manualOnly(Map.of(A,d("90")))); amount("90",q.unitValue()); amount("1440",q.totalValue()); });
        test("value.quote-pins-revision", () -> eq(7L, quote(snap(A,1), ValueDefinitions.manualOnly(Map.of(A,d("90")))).revision()));
        test("value.quote-pins-payload", () -> { var s=snap(A,1); eq(s.fingerprint(), quote(s,ValueDefinitions.manualOnly(Map.of(A,d("90")))).fingerprint()); });
        test("value.unknown-fails-closed", () -> status(ValueResult.Status.UNKNOWN_VALUE, snap(A,1), ValueDefinitions.manualOnly(Map.of())));
        test("value.custom-never-falls-back-to-material", () -> status(ValueResult.Status.UNKNOWN_VALUE, snap(k("mmoitems:SWORD:UNLISTED"),1), ValueDefinitions.manualOnly(Map.of(k("minecraft:diamond_sword"),d("1800")))));
        test("value.manual-before-rule", () -> { var c=defs(Map.of(A,d("90")),List.of(rule("a",1,A,"","", "999")),Map.of(),Map.of(),Set.of(),ModifierSettings.none(),RecipeIndex.empty(),ValueLimits.defaults()); amount("90",quote(snap(A,1),c).unitValue()); });
        test("value.highest-priority-rule", () -> { var c=defs(Map.of(),List.of(rule("a",1,A,"","","10"),rule("b",2,A,"","","20")),Map.of(),Map.of(),Set.of(),ModifierSettings.none(),RecipeIndex.empty(),ValueLimits.defaults()); amount("20",quote(snap(A,1),c).unitValue()); });
        test("value.equal-priority-rule-rejected", () -> { var c=defs(Map.of(),List.of(rule("a",1,A,"","","10"),rule("b",1,A,"","","20")),Map.of(),Map.of(),Set.of(),ModifierSettings.none(),RecipeIndex.empty(),ValueLimits.defaults()); status(ValueResult.Status.AMBIGUOUS_RULE,snap(A,1),c); });
        test("value.higher-priority-resolves-lower-tie", () -> { var c=defs(Map.of(),List.of(rule("a",1,A,"","","10"),rule("b",1,A,"","","20"),rule("c",2,A,"","","30")),Map.of(),Map.of(),Set.of(),ModifierSettings.none(),RecipeIndex.empty(),ValueLimits.defaults()); amount("30",quote(snap(A,1),c).unitValue()); });
        test("value.explicit-provider-fallback", () -> { var c=defs(Map.of(),List.of(),Map.of("nexo",d("30")),Map.of(),Set.of(),ModifierSettings.none(),RecipeIndex.empty(),ValueLimits.defaults()); amount("30",quote(snap(k("nexo:staff"),1),c).unitValue()); });
        test("value.explicit-rarity-fallback", () -> { var c=defs(Map.of(),List.of(),Map.of(),Map.of("EPIC",d("50")),Set.of(),ModifierSettings.none(),RecipeIndex.empty(),ValueLimits.defaults()); amount("50",quote(snapshot(facts(A,1,0,0,Map.of(),"EPIC",Set.of())),c).unitValue()); });
        test("value.blocked-overrides-manual", () -> status(ValueResult.Status.BLOCKED_ITEM,snap(A,1),defs(Map.of(A,d("90")),List.of(),Map.of(),Map.of(),Set.of(A),ModifierSettings.none(),RecipeIndex.empty(),ValueLimits.defaults())));
        for (ItemFacts.Risk risk : ItemFacts.Risk.values()) test("value.reject-risk-"+risk.name(), () -> status(ValueResult.Status.UNSAFE_METADATA,
            snapshot(facts(A,1,0,0,Map.of(),"",Set.of(risk))), ValueDefinitions.manualOnly(Map.of(A,d("90")))));
        test("value.amount-limit", () -> status(ValueResult.Status.AMOUNT_LIMIT,snap(A,65),ValueDefinitions.manualOnly(Map.of(A,d("90")))));
        test("value.payload-limit", () -> status(ValueResult.Status.SNAPSHOT_LIMIT,new ItemSnapshot(ItemFacts.clean(A,1),new byte[65537]),ValueDefinitions.manualOnly(Map.of(A,d("90")))));
        test("value.enchant-limit-even-if-unpriced", () -> status(ValueResult.Status.UNSAFE_ENCHANTMENT,snapshot(facts(A,1,0,0,Map.of("minecraft:sharpness",11),"",Set.of())),ValueDefinitions.manualOnly(Map.of(A,d("90")))));
        test("value.modifier-order", () -> {
            var mods=new ModifierSettings(Map.of("minecraft:sharpness",d("10")),Map.of("EPIC",d("1.5")),true,d("0.25"));
            var q=quote(snapshot(facts(A,2,50,100,Map.of("minecraft:sharpness",2),"EPIC",Set.of())),defs(Map.of(A,d("100")),List.of(),Map.of(),Map.of(),Set.of(),mods,RecipeIndex.empty(),ValueLimits.defaults()));
            amount("112.5",q.unitValue()); amount("225",q.totalValue()); eq(List.of("enchant","rarity","durability"),q.adjustments().stream().map(ValueResult.Adjustment::id).toList());
        });
        test("value.full-durability-preserves-value", () -> amount("100",quote(snapshot(facts(A,1,0,100,Map.of(),"",Set.of())),withMods(new ModifierSettings(Map.of(),Map.of(),true,d("0.25")))).unitValue()));
        test("value.zero-durability-respects-floor", () -> amount("25",quote(snapshot(facts(A,1,100,100,Map.of(),"",Set.of())),withMods(new ModifierSettings(Map.of(),Map.of(),true,d("0.25")))).unitValue()));
        test("value.non-damageable-no-penalty", () -> amount("100",quote(snap(A,1),withMods(new ModifierSettings(Map.of(),Map.of(),true,d("0.25")))).unitValue()));
        test("value.never-round-tiny-value-up", () -> status(ValueResult.Status.ZERO_AFTER_ROUNDING,snap(A,1),ValueDefinitions.manualOnly(Map.of(A,d("0.00000001")))));
        test("value.modifier-overflow-rejected-not-clamped", () -> { var c=defs(Map.of(A,d("1000000000000")),List.of(),Map.of(),Map.of(),Set.of(),new ModifierSettings(Map.of("minecraft:sharpness",d("1")),Map.of(),false,BigDecimal.ZERO),RecipeIndex.empty(),ValueLimits.defaults()); status(ValueResult.Status.VALUE_LIMIT,snapshot(facts(A,1,0,0,Map.of("minecraft:sharpness",1),"",Set.of())),c); });
        test("value.total-limit", () -> { var l=new ValueLimits(6,64,65536,10,d("100"),d("150"),24,2048); status(ValueResult.Status.VALUE_LIMIT,snap(A,2),defs(Map.of(A,d("100")),List.of(),Map.of(),Map.of(),Set.of(),ModifierSettings.none(),RecipeIndex.empty(),l)); });
        test("config.no-zero-manual-value", () -> illegal(() -> ValueDefinitions.manualOnly(Map.of(A,BigDecimal.ZERO))));
        test("config.base-above-limit-rejected", () -> illegal(() -> ValueDefinitions.manualOnly(Map.of(A,d("1000000000001")))));
        test("config.no-duplicate-rule-id", () -> illegal(() -> defs(Map.of(),List.of(rule("a",1,A,"","","1"),rule("a",2,B,"","","2")),Map.of(),Map.of(),Set.of(),ModifierSettings.none(),RecipeIndex.empty(),ValueLimits.defaults())));
        test("config.no-unbounded-rule", () -> illegal(() -> rule("a",1,null,"","","1")));
        test("config.no-conflicting-provider-rule", () -> illegal(() -> rule("a",1,A,"nexo","","1")));
        test("config.no-durability-factor-over-one", () -> illegal(() -> new ModifierSettings(Map.of(),Map.of(),true,d("1.1"))));
        test("config.defensive-map", () -> { var map=new HashMap<ItemKey,BigDecimal>(); map.put(A,d("90")); var c=ValueDefinitions.manualOnly(map); map.put(A,d("1")); amount("90",quote(snap(A,1),c).unitValue()); });
        test("value.result-invariant", () -> illegal(() -> new ValueResult(ValueResult.Status.AVAILABLE,Optional.empty(),"")));
    }
    private static void recipeTests() {
        test("recipe.output-quantity", () -> { var c=withRecipes(Map.of(A,d("90")),List.of(recipe("r",B,9,A,1))); amount("10",quote(snap(B,1),c).unitValue()); });
        test("recipe.ingredient-quantity", () -> { var c=withRecipes(Map.of(A,d("90")),List.of(recipe("r",B,1,A,9))); amount("810",quote(snap(B,1),c).unitValue()); });
        test("recipe.multi-level", () -> { var c=withRecipes(Map.of(A,d("10")),List.of(recipe("b",B,1,A,2),recipe("c",C,1,B,3))); amount("60",quote(snap(C,1),c).unitValue()); });
        test("recipe.manual-breaks-cycle", () -> { var c=withRecipes(Map.of(A,d("10")),List.of(recipe("b",B,1,A,9),recipe("a",A,9,B,1))); amount("90",quote(snap(B,1),c).unitValue()); });
        test("recipe.unanchored-cycle-unknown", () -> status(ValueResult.Status.UNKNOWN_VALUE,snap(A,1),withRecipes(Map.of(),List.of(recipe("a",A,1,B,1),recipe("b",B,1,A,1)))));
        test("recipe.cycle-route-plus-safe-route", () -> { var c=withRecipes(Map.of(C,d("10")),List.of(recipe("loop",A,1,B,1),recipe("back",B,1,A,1),recipe("safe",A,1,C,2))); amount("20",quote(snap(A,1),c).unitValue()); });
        test("recipe.missing-ingredient-unknown", () -> status(ValueResult.Status.UNKNOWN_VALUE,snap(B,1),withRecipes(Map.of(),List.of(recipe("b",B,1,A,1)))));
        test("recipe.blocked-ingredient-not-valued", () -> { var c=defs(Map.of(A,d("90")),List.of(),Map.of(),Map.of(),Set.of(A),ModifierSettings.none(),new RecipeIndex(List.of(recipe("b",B,1,A,1))),ValueLimits.defaults()); status(ValueResult.Status.UNKNOWN_VALUE,snap(B,1),c); });
        test("recipe.cheapest-known-alternative", () -> { var r=new RecipeDefinition("b",B,1,List.of(new RecipeDefinition.Ingredient(List.of(A,C),2))); amount("20",quote(snap(B,1),withRecipes(Map.of(A,d("90"),C,d("10")),List.of(r))).unitValue()); });
        test("recipe.unpriced-alternative-invalidates-recipe", () -> { var r=new RecipeDefinition("b",B,1,List.of(new RecipeDefinition.Ingredient(List.of(A,C),2))); status(ValueResult.Status.UNKNOWN_VALUE,snap(B,1),withRecipes(Map.of(A,d("90")),List.of(r))); });
        test("recipe.cheapest-complete-recipe", () -> amount("10",quote(snap(B,1),withRecipes(Map.of(A,d("90"),C,d("10")),List.of(recipe("b1",B,1,A,1),recipe("b2",B,1,C,1)))).unitValue()));
        test("recipe.reject-zero-output", () -> illegal(() -> recipe("bad",B,0,A,1)));
        test("recipe.reject-zero-ingredient", () -> illegal(() -> recipe("bad",B,1,A,0)));
        test("recipe.reject-empty-recipe", () -> illegal(() -> new RecipeDefinition("x",B,1,List.of())));
        test("recipe.reject-duplicate-recipe-id", () -> illegal(() -> new RecipeIndex(List.of(recipe("r",B,1,A,1),recipe("r",C,1,A,1)))));
        test("recipe.reject-duplicate-alternative", () -> illegal(() -> new RecipeDefinition.Ingredient(List.of(A,A),1)));
        test("recipe.depth-budget", () -> { var l=new ValueLimits(6,64,65536,10,d("1000000"),d("64000000"),1,2048); status(ValueResult.Status.RECIPE_LIMIT,snap(C,1),defs(Map.of(A,d("10")),List.of(),Map.of(),Map.of(),Set.of(),ModifierSettings.none(),new RecipeIndex(List.of(recipe("b",B,1,A,1),recipe("c",C,1,B,1))),l)); });
        test("recipe.node-budget", () -> { var l=new ValueLimits(6,64,65536,10,d("1000000"),d("64000000"),24,1); status(ValueResult.Status.RECIPE_LIMIT,snap(B,1),defs(Map.of(A,d("10")),List.of(),Map.of(),Map.of(),Set.of(),ModifierSettings.none(),new RecipeIndex(List.of(recipe("b",B,1,A,1))),l)); });
        test("recipe.provider-before-recipe-explicit-order", () -> { var c=defs(Map.of(A,d("90")),List.of(),Map.of("minecraft",d("1")),Map.of(),Set.of(),ModifierSettings.none(),new RecipeIndex(List.of(recipe("b",B,1,A,9))),ValueLimits.defaults()); amount("1",quote(snap(B,1),c).unitValue()); });
    }
    private static void menuTests() {
        test("menu.compile-slot-map", () -> { var m = compile(List.of("#########","#S#######","#########")); eq(27,m.size()); eq(10,m.sourceSlot()); eq(27,m.slots().size()); });
        test("menu.no-hardcoded-source-slot", () -> eq(24,compile(List.of("#########","#########","######S##")).sourceSlot()));
        test("menu.reject-wrong-width", () -> illegal(() -> compile(List.of("########"))));
        test("menu.reject-extra-row", () -> illegal(() -> compile(java.util.Collections.nCopies(7,"#########"))));
        test("menu.reject-empty", () -> illegal(() -> compile(List.of())));
        test("menu.reject-unknown-symbol", () -> illegal(() -> compile(List.of("?S#######"))));
        test("menu.reject-missing-source", () -> illegal(() -> compile(List.of("#########"))));
        test("menu.reject-repeated-source", () -> illegal(() -> compile(List.of("#SS######"))));
        test("menu.reject-unicode-slot-symbol", () -> illegal(() -> compile(List.of("#S######🙂"))));
        test("menu.action-needs-explicit-profile-id", () -> illegal(() -> element(MenuDefinition.Role.BUTTON,MenuDefinition.Action.SELECT_PROFILE,"")));
        test("menu.source-role-action-match", () -> illegal(() -> element(MenuDefinition.Role.SOURCE_INPUT,MenuDefinition.Action.NONE,"")));
        test("menu.readonly-cannot-upgrade", () -> illegal(() -> element(MenuDefinition.Role.FILLER,MenuDefinition.Action.UPGRADE,"")));
        test("menu.source-preview-cannot-be-input", () -> illegal(() -> element(MenuDefinition.Role.SOURCE_PREVIEW,MenuDefinition.Action.SOURCE_INPUT,"")));
        test("menu.model-key-validation", () -> illegal(() -> new MenuDefinition.Element(MenuDefinition.Role.BUTTON,"PAPER","",List.of(),"Invalid Key",null,false,MenuDefinition.Action.NONE,"")));
        test("menu.negative-cmd-rejected", () -> illegal(() -> new MenuDefinition.Element(MenuDefinition.Role.BUTTON,"PAPER","",List.of(),"",-1,false,MenuDefinition.Action.NONE,"")));
        test("menu.compiled-map-immutable", () -> { var m=compile(List.of("#S#######")); expect(UnsupportedOperationException.class,()->m.slots().clear()); });
    }
    private static void lifecycleTests() {
        test("reload.starts-gated", () -> { var s=new RuntimeStore<String>(); eq(RuntimeStore.State.STARTING,s.state()); check(s.snapshot().isEmpty()); });
        test("reload.one-flight", () -> { var s=new RuntimeStore<String>(); check(s.beginReload().isPresent()); check(s.beginReload().isEmpty()); });
        test("reload.atomic-publish", () -> { var s=new RuntimeStore<String>(); var t=s.beginReload().orElseThrow(); check(s.commit(t,"first")); eq("first",s.snapshot().orElseThrow().value()); eq(1L,s.snapshot().orElseThrow().revision()); });
        test("reload.failure-preserves-old-runtime", () -> { var s=ready(); var t=s.beginReload().orElseThrow(); s.reject(t); eq("first",s.snapshot().orElseThrow().value()); eq(RuntimeStore.State.READY,s.state()); eq(1L,s.snapshot().orElseThrow().revision()); });
        test("reload.initial-failure-gated", () -> { var s=new RuntimeStore<String>(); s.reject(s.beginReload().orElseThrow()); eq(RuntimeStore.State.FAILED,s.state()); check(s.snapshot().isEmpty()); });
        test("reload.recover-initial-failure", () -> { var s=new RuntimeStore<String>(); s.reject(s.beginReload().orElseThrow()); check(s.commit(s.beginReload().orElseThrow(),"fixed")); eq(RuntimeStore.State.READY,s.state()); });
        test("reload.old-ticket-cannot-commit", () -> { var s=ready(); var t=s.beginReload().orElseThrow(); s.reject(t); var next=s.beginReload().orElseThrow(); check(!s.commit(t,"old")); check(s.commit(next,"new")); });
        test("reload.double-commit-rejected", () -> { var s=new RuntimeStore<String>(); var t=s.beginReload().orElseThrow(); check(s.commit(t,"first")); check(!s.commit(t,"duplicate")); });
        test("reload.disable-rejects-late-result", () -> { var s=ready(); var t=s.beginReload().orElseThrow(); s.stop(); check(!s.commit(t,"late")); check(s.snapshot().isEmpty()); eq(RuntimeStore.State.STOPPED,s.state()); });
        test("reload.disable-rejects-new-request", () -> { var s=ready(); s.stop(); check(s.beginReload().isEmpty()); });
        test("reload.revision-stale-check", () -> { var s=ready(); check(s.isCurrent(1)); s.commit(s.beginReload().orElseThrow(),"second"); check(!s.isCurrent(1)); check(s.isCurrent(2)); });
        test("reload.concurrent-single-flight", () -> {
            var s = new RuntimeStore<String>(); var executor = Executors.newFixedThreadPool(8);
            try {
                List<Callable<Boolean>> calls = new ArrayList<>(); for(int i=0;i<128;i++) calls.add(()->s.beginReload().isPresent());
                int accepted=0; for(var future:executor.invokeAll(calls,5,TimeUnit.SECONDS)) if(future.get()) accepted++;
                eq(1,accepted);
            } finally { executor.shutdownNow(); check(executor.awaitTermination(5,TimeUnit.SECONDS)); }
        });
    }
    private static void storageTests() {
        test("schema.identifier-whitelist", () -> eq("itemupgrader_schema_meta",SchemaPlan.identifier("itemupgrader_schema_meta")));
        test("schema.identifier-rejects-injection", () -> illegal(() -> SchemaPlan.identifier("x;DROP TABLE y")));
        test("schema.identifier-rejects-quotes", () -> illegal(() -> SchemaPlan.identifier("x`")));
        test("schema.identifier-rejects-long-name", () -> illegal(() -> SchemaPlan.identifier("x".repeat(64))));
        test("schema.ddl-idempotent", () -> check(SchemaPlan.initialDdl("upgrader_meta").getFirst().startsWith("CREATE TABLE IF NOT EXISTS upgrader_meta")));
    }
    private static void propertyTests() {
        test("invariant.stack-split-merge-1000-cases", () -> {
            var random=new Random(20260916L);
            for(int i=0;i<1000;i++) {
                BigDecimal base=BigDecimal.valueOf(random.nextInt(100000)+1,4);
                var config=ValueDefinitions.manualOnly(Map.of(A,base)); int amount=random.nextInt(64)+1;
                amount(quote(snap(A,1),config).unitValue().multiply(BigDecimal.valueOf(amount)).toPlainString(),quote(snap(A,amount),config).totalValue());
            }
        });
        test("invariant.durability-monotonic-101-points", () -> {
            var c=withMods(new ModifierSettings(Map.of(),Map.of(),true,d("0.25"))); BigDecimal previous=d("101");
            for(int damage=0;damage<=100;damage++) { BigDecimal unit=quote(snapshot(facts(A,1,damage,100,Map.of(),"",Set.of())),c).unitValue(); check(unit.compareTo(previous)<=0); previous=unit; }
        });
        test("invariant.concurrent-valuation-256-requests", () -> {
            var executor=Executors.newFixedThreadPool(8); var c=ValueDefinitions.manualOnly(Map.of(A,d("90"))); var snapshot=snap(A,16);
            try {
                List<Callable<BigDecimal>> work=new ArrayList<>(); for(int i=0;i<256;i++) work.add(()->ENGINE.evaluate(snapshot,c,7).quote().orElseThrow().totalValue());
                for(var future:executor.invokeAll(work,5,TimeUnit.SECONDS)) amount("1440",future.get());
            } finally { executor.shutdownNow(); check(executor.awaitTermination(5,TimeUnit.SECONDS)); }
        });
    }
    private static void test(String name, Checked body) { TESTS.add(new Test(name,body)); }
    private static ItemKey k(String key) { return ItemKey.of(key); }
    private static BigDecimal d(String text) { return new BigDecimal(text); }
    private static ItemFacts facts(ItemKey key,int count,int damage,int max,Map<String,Integer> enchant,String rarity,Set<ItemFacts.Risk> risks) { return new ItemFacts(key,count,damage,max,enchant,rarity,risks); }
    private static ItemSnapshot snapshot(ItemFacts facts) { return new ItemSnapshot(facts, new byte[]{1,2,3,4}); }
    private static ItemSnapshot snap(ItemKey key,int amount) { return snapshot(ItemFacts.clean(key,amount)); }
    private static ValueRule rule(String id,int priority,ItemKey key,String provider,String rarity,String value) { return new ValueRule(id,priority,provider,key,rarity,d(value)); }
    private static ValueDefinitions defs(Map<ItemKey,BigDecimal> m,List<ValueRule> rules,Map<String,BigDecimal> p,Map<String,BigDecimal> r,Set<ItemKey> b,ModifierSettings mods,RecipeIndex index,ValueLimits limits) { return new ValueDefinitions(m,rules,p,r,b,mods,index,limits); }
    private static ValueDefinitions withMods(ModifierSettings m) { return defs(Map.of(A,d("100")),List.of(),Map.of(),Map.of(),Set.of(),m,RecipeIndex.empty(),ValueLimits.defaults()); }
    private static ValueDefinitions withRecipes(Map<ItemKey,BigDecimal> manual,List<RecipeDefinition> recipes) { return defs(manual,List.of(),Map.of(),Map.of(),Set.of(),ModifierSettings.none(),new RecipeIndex(recipes),ValueLimits.defaults()); }
    private static RecipeDefinition recipe(String id,ItemKey output,int count,ItemKey input,int amount) { return new RecipeDefinition(id,output,count,List.of(new RecipeDefinition.Ingredient(List.of(input),amount))); }
    private static ValueResult.Quote quote(ItemSnapshot snapshot, ValueDefinitions config) { var result=ENGINE.evaluate(snapshot,config,7); eq(ValueResult.Status.AVAILABLE,result.status()); return result.quote().orElseThrow(); }
    private static void status(ValueResult.Status expected, ItemSnapshot snapshot, ValueDefinitions config) { var result=ENGINE.evaluate(snapshot,config,7); eq(expected,result.status()); check(result.quote().isEmpty()); }
    private static MenuDefinition.Element element(MenuDefinition.Role role,MenuDefinition.Action action,String argument) { return new MenuDefinition.Element(role,"PAPER","",List.of(),"",null,false,action,argument); }
    private static MenuCompiler.CompiledMenu compile(List<String> matrix) { return new MenuCompiler().compile(new MenuDefinition("upgrader","",matrix,Map.of('#',element(MenuDefinition.Role.FILLER,MenuDefinition.Action.NONE,""),'S',element(MenuDefinition.Role.SOURCE_INPUT,MenuDefinition.Action.SOURCE_INPUT,""))),true); }
    private static RuntimeStore<String> ready() { var s=new RuntimeStore<String>(); s.commit(s.beginReload().orElseThrow(),"first"); return s; }
    private static void illegal(Runnable body) { expect(IllegalArgumentException.class,body); }
    private static void expect(Class<? extends Throwable> type,Runnable body) { assertions++; try { body.run(); } catch(Throwable error) { if(type.isInstance(error)) return; throw new AssertionError("Expected " + type + " but got " + error,error); } throw new AssertionError("Expected " + type.getSimpleName()); }
    private static void check(boolean condition) { assertions++; if(!condition) throw new AssertionError("condition failed"); }
    private static void eq(Object expected,Object actual) { assertions++; if(!java.util.Objects.equals(expected,actual)) throw new AssertionError("Expected " + expected + "; got " + actual); }
    private static void amount(String expected,BigDecimal actual) { assertions++; if(d(expected).compareTo(actual)!=0) throw new AssertionError("Expected decimal " + expected + "; got " + actual); }
    private static String escape(String text) { return text.replace("&","&amp;").replace("\"","&quot;").replace("<","&lt;").replace(">","&gt;"); }
}
