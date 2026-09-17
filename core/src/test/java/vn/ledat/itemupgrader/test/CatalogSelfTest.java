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
import vn.ledat.itemupgrader.item.*;
import vn.ledat.itemupgrader.recipe.RecipeIndex;
import vn.ledat.itemupgrader.runtime.RuntimeStore;
import vn.ledat.itemupgrader.runtime.PreviewRequestGate;
import java.util.concurrent.atomic.AtomicLong;
import vn.ledat.itemupgrader.value.*;

/** Detached fake payload fixtures test business rules, not Paper/provider serialization or server TPS. */
public final class CatalogSelfTest {
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
    private static final CatalogService SERVICE = new CatalogService();
    private CatalogSelfTest() {}
    public static void main(String[] args) throws Exception {
        definitionTests(); indexTests(); discoveryTests(); selectionTests(); argumentTests(); requestGateTests(); invariantTests();
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
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuite name=\"ItemUpgraderCatalog\" tests=\"" + results.size()
                + "\" failures=\"" + failures + "\" errors=\"0\">\n");
        for (Result result : results) {
            xml.append("  <testcase classname=\"ItemUpgraderCatalog\" name=\"").append(escape(result.name())).append("\" time=\"")
                    .append(String.format(java.util.Locale.ROOT, "%.6f", result.seconds())).append("\">");
            if (result.failure() != null) xml.append("<failure message=\"").append(escape(result.failure())).append("\"/>");
            xml.append("</testcase>\n");
        }
        xml.append("  <system-out>assertions=").append(assertions).append("; No Paper/Platform runtime tests in this suite.</system-out>\n</testsuite>\n");
        Path report = Path.of(args.length == 0 ? "build/reports/catalog-self-test.xml" : args[0]);
        Files.createDirectories(report.toAbsolutePath().getParent()); Files.writeString(report, xml, StandardCharsets.UTF_8);
        System.out.println("RESULT tests=" + results.size() + " assertions=" + assertions + " failures=" + failures);
        if (failures != 0) throw new AssertionError(failures + " failed test groups");
    }

    private static void definitionTests() {
        test("settings.valid-defaults", () -> amount("2", CatalogSettings.defaults().preferredRatio()));
        test("settings.no-sub-one-min", () -> illegal(() -> settings("0.9","12","2",true)));
        test("settings.no-inverted-bounds", () -> illegal(() -> settings("4","2","3",true)));
        test("settings.preferred-in-range", () -> illegal(() -> settings("2","3","4",true)));
        test("settings.no-exponent-bomb", () -> illegal(() -> settings("1","1E+999999","2",true)));
        test("settings.bounded-counts", () -> illegal(() -> new CatalogSettings(d("1"),d("12"),d("2"),46,3,2048,512,true)));
        test("target.id-not-command", () -> illegal(() -> target("diamond;op",DIAMOND)));
        test("target.amount-bound", () -> illegal(() -> custom("x",DIAMOND,65,"materials",Set.of(),"",Set.of(),Set.of(),0,true)));
        test("target.permission-not-expression", () -> illegal(() -> custom("x",DIAMOND,1,"materials",Set.of(),"vip || true",Set.of(),Set.of(),0,true)));
        test("target.permission-no-wildcard-configuration", () -> illegal(() -> custom("x",DIAMOND,1,"materials",Set.of(),"*",Set.of(),Set.of(),0,true)));
        test("target.name-no-newline", () -> illegal(() -> new TargetDefinition("x",DIAMOND,1,"a\nb","materials",Set.of(),true,0,"",Set.of(),Set.of())));
        test("target.custom-id-case-preserved", () -> eq("mmoitems:SWORD:Dragon_Blade", target("blade", k("mmoitems:SWORD:Dragon_Blade")).item().value()));
        test("target.defensive-sets", () -> {
            Set<String> tags = new HashSet<>(Set.of("gem"));
            var t = custom("x",DIAMOND,1,"materials",tags,"",Set.of(),Set.of(),0,true); tags.clear();
            eq(Set.of("gem"),t.tags()); expect(UnsupportedOperationException.class,() -> t.tags().add("other"));
        });
        test("path.nonempty-sources", () -> illegal(() -> new UpgradePath("x",Set.of(),1,UpgradePath.Mode.LOCKED,List.of("diamond"),true,"",Set.of())));
        test("path.no-duplicate-targets", () -> illegal(() -> path("x",100,UpgradePath.Mode.LOCKED,List.of("diamond","diamond"),"",Set.of())));
        test("definition.duplicate-target-id", () -> illegal(() -> defs(List.of(target("x",GOLD),target("x",DIAMOND)),List.of())));
        test("definition.duplicate-item-quantity", () -> illegal(() -> defs(List.of(target("a",GOLD),target("b",GOLD)),List.of())));
        test("definition.different-quantity-allowed", () -> eq(2,defs(List.of(target("a",GOLD),custom("b",GOLD,2,"materials",Set.of(),"",Set.of(),Set.of(),0,true)),List.of()).targets().size()));
        test("definition.dangling-reference", () -> illegal(() -> defs(List.of(target("gold",GOLD)),List.of(path("p",1,UpgradePath.Mode.OPEN,List.of("absent"),"",Set.of())))));
        test("definition.duplicate-path-id", () -> illegal(() -> defs(targets(),List.of(path("p",1,UpgradePath.Mode.OPEN,List.of("gold"),"",Set.of()),path("p",2,UpgradePath.Mode.OPEN,List.of("diamond"),"",Set.of())))));
        test("definition.equal-priority-overlap-rejected", () -> illegal(() -> defs(targets(),List.of(path("a",5,UpgradePath.Mode.OPEN,List.of("gold"),"",Set.of()),path("b",5,UpgradePath.Mode.LOCKED,List.of("diamond"),"",Set.of())))));
        test("definition.non-top-tie-also-rejected", () -> illegal(() -> defs(targets(),List.of(path("a",100,UpgradePath.Mode.OPEN,List.of("gold"),"",Set.of()),path("b",5,UpgradePath.Mode.LOCKED,List.of("diamond"),"",Set.of()),path("c",5,UpgradePath.Mode.OPEN,List.of("gold"),"",Set.of())))));
        test("definition.disabled-path-does-not-lock", () -> {
            var disabled = new UpgradePath("disabled",Set.of(IRON),100,UpgradePath.Mode.LOCKED,List.of("gold"),false,"vip",Set.of());
            check(defs(targets(),List.of(disabled)).pathFor(IRON).isEmpty());
        });
        test("definition.structural-highest-priority", () -> eq("high",defs(targets(),List.of(path("low",1,UpgradePath.Mode.OPEN,List.of("gold"),"",Set.of()),path("high",2,UpgradePath.Mode.LOCKED,List.of("diamond"),"vip",Set.of()))).pathFor(IRON).orElseThrow().id()));
        test("definition.permission-condition-index", () -> {
            var t=custom("diamond",DIAMOND,1,"materials",Set.of(),"vip",Set.of("level50"),Set.of(),0,true);
            var def=defs(List.of(t),List.of(path("p",1,UpgradePath.Mode.OPEN,List.of("diamond"),"path.use",Set.of("quest"))));
            eq(Set.of("vip","path.use"),def.permissionNodes()); eq(Set.of("level50","quest"),def.conditionIds());
        });
        test("definition.config-count-limit", () -> {
            var opts = new CatalogSettings(d("1.25"),d("12"),d("2"),12,3,1,1,true);
            illegal(() -> new CatalogDefinitions(opts,targets(),List.of()));
        });
        test("definition.collections-immutable", () -> {
            var d=defs(targets(),List.of()); expect(UnsupportedOperationException.class,() -> d.targets().clear());
            expect(UnsupportedOperationException.class,() -> d.categories().clear());
        });
    }
    private static void indexTests() {
        test("index.no-probe-no-target", () -> {
            var i=CatalogIndex.build(1,defs(targets(),List.of()),values(),Map.of());
            eq(0,i.ready().size()); eq("NOT_PROBED",i.rejected().get("diamond").code());
        });
        test("probe.none-without-snapshot-rejected", () -> illegal(() -> TargetProbe.failed(TargetProbe.Failure.NONE)));
        test("index.unknown-probe-id", () -> illegal(() -> CatalogIndex.build(1,defs(targets(),List.of()),values(),Map.of("ghost",TargetProbe.verified(snap(GOLD,1))))));
        test("index.revision-positive", () -> illegal(() -> CatalogIndex.build(0,defs(targets(),List.of()),values(),Map.of())));
        test("index.provider-missing-not-vanilla-fallback", () -> {
            var i=CatalogIndex.build(1,defs(targets(),List.of()),values(),Map.of("diamond",TargetProbe.failed(TargetProbe.Failure.PROVIDER_UNAVAILABLE)));
            eq("PROVIDER_UNAVAILABLE",i.rejected().get("diamond").code()); check(i.ready().isEmpty());
        });
        test("index.target-key-mismatch", () -> eq("TEMPLATE_MISMATCH",CatalogIndex.build(1,defs(targets(),List.of()),values(),Map.of("diamond",TargetProbe.verified(snap(GOLD,1)))).rejected().get("diamond").code()));
        test("index.target-quantity-mismatch", () -> eq("TEMPLATE_MISMATCH",CatalogIndex.build(1,defs(targets(),List.of()),values(),Map.of("diamond",TargetProbe.verified(snap(DIAMOND,2)))).rejected().get("diamond").code()));
        test("index.unknown-value-unavailable", () -> {
            var i=CatalogIndex.build(1,defs(List.of(target("x",k("minecraft:emerald"))),List.of()),values(),Map.of("x",TargetProbe.verified(snap(k("minecraft:emerald"),1))));
            eq("VALUE_UNKNOWN_VALUE",i.rejected().get("x").code());
        });
        test("index.risky-target-unavailable", () -> {
            var f=new ItemFacts(DIAMOND,1,0,0,Map.of(),"",Set.of(ItemFacts.Risk.NESTED_CONTENTS));
            var i=CatalogIndex.build(1,defs(targets(),List.of()),values(),Map.of("diamond",TargetProbe.verified(snapshot(f))));
            eq("VALUE_UNSAFE_METADATA",i.rejected().get("diamond").code());
        });
        test("index.blocked-target-unavailable", () -> {
            var v=new ValueDefinitions(values().manual(),List.of(),Map.of(),Map.of(),Set.of(DIAMOND),ModifierSettings.none(),RecipeIndex.empty(),ValueLimits.defaults());
            var i=CatalogIndex.build(1,defs(targets(),List.of()),v,probes(targets())); eq("VALUE_BLOCKED_ITEM",i.rejected().get("diamond").code());
        });
        test("index.disabled-even-with-probe", () -> {
            var t=custom("diamond",DIAMOND,1,"materials",Set.of(),"",Set.of(),Set.of(),0,false);
            eq("DISABLED",CatalogIndex.build(1,defs(List.of(t),List.of()),values(),probes(List.of(t))).rejected().get("diamond").code());
        });
        test("index.quantity-valued-as-total", () -> {
            var t=custom("diamond",DIAMOND,3,"materials",Set.of(),"",Set.of(),Set.of(),0,true);
            amount("2700",index(List.of(t),List.of()).ready().get("diamond").value().totalValue());
        });
        test("index.native-metadata-modifiers-priced", () -> {
            var f=new ItemFacts(DIAMOND,1,0,0,Map.of("minecraft:luck",2),"",Set.of());
            var modifiers=new ModifierSettings(Map.of("minecraft:luck",d("50")),Map.of(),false,d("0"));
            var v=new ValueDefinitions(values().manual(),List.of(),Map.of(),Map.of(),Set.of(),modifiers,RecipeIndex.empty(),ValueLimits.defaults());
            var i=CatalogIndex.build(1,defs(targets(),List.of()),v,Map.of("diamond",TargetProbe.verified(snapshot(f))));
            amount("1000",i.ready().get("diamond").value().totalValue());
        });
        test("index.inclusive-price-range", () -> eq(List.of("gold","diamond"),index(targets(),List.of()).between(d("180"),d("900")).stream().map(r->r.definition().id()).toList()));
        test("index.inverted-range-rejected", () -> illegal(() -> index(targets(),List.of()).between(d("901"),d("900"))));
        test("index.category-provider-indexes", () -> {
            var i=index(targets(),List.of()); eq(2,i.byCategory().get("materials").size()); eq(2,i.byProvider().get("minecraft").size());
            expect(UnsupportedOperationException.class,()->i.byCategory().get("materials").clear());
        });
        test("index.ready-and-rejected-immutable", () -> {
            var i=index(targets(),List.of()); expect(UnsupportedOperationException.class,()->i.ready().clear());
            expect(UnsupportedOperationException.class,()->i.rejected().clear());
        });
        test("index.16mib-payload-cap", () -> {
            var list=new ArrayList<TargetDefinition>(); var probes=new HashMap<String,TargetProbe>(); var prices=new HashMap<ItemKey,BigDecimal>();
            byte[] payload=new byte[1_048_576];
            for(int n=0;n<17;n++){var key=k("minecraft:payload_"+n);var t=target("t"+n,key);list.add(t);prices.put(key,d("10"));probes.put(t.id(),TargetProbe.verified(new ItemSnapshot(ItemFacts.clean(key,1),payload)));}
            var limits=new ValueLimits(6,64,1_048_576,10,d("10000"),d("1000000"),24,2048);
            var values=new ValueDefinitions(prices,List.of(),Map.of(),Map.of(),Set.of(),ModifierSettings.none(),RecipeIndex.empty(),limits);
            illegal(()->CatalogIndex.build(1,defs(list,List.of()),values,probes));
        });
    }
    private static void discoveryTests() {
        test("browse.default-preferred-ratio", () -> eq(List.of("gold","diamond"),ids(browse(index(targets(),List.of())))));
        test("browse.locked-path-excludes-catalog", () -> eq(List.of("diamond"),ids(browse(index(targets(),List.of(path("lock",1,UpgradePath.Mode.LOCKED,List.of("diamond"),"",Set.of())))))));
        test("browse.open-path-prioritizes-but-extends", () -> eq(List.of("diamond","gold"),ids(browse(index(targets(),List.of(path("open",1,UpgradePath.Mode.OPEN,List.of("diamond"),"",Set.of())))))));
        test("browse.permission-denied-path-no-fallback", () -> {
            var i=index(targets(),List.of(path("restricted",10,UpgradePath.Mode.LOCKED,List.of("diamond"),"vip",Set.of()),path("fallback",1,UpgradePath.Mode.OPEN,List.of("gold"),"",Set.of())));
            eq(CatalogResult.Status.PATH_DENIED,browse(i).status());
            eq(List.of("diamond"),ids(SERVICE.browse(snap(IRON,1),i,access(Set.of("vip"),Set.of()),CatalogQuery.firstPage(12))));
        });
        test("browse.unknown-condition-fail-closed", () -> eq(CatalogResult.Status.PATH_DENIED,browse(index(targets(),List.of(path("p",1,UpgradePath.Mode.OPEN,List.of("diamond"),"",Set.of("level50"))))).status()));
        test("browse.condition-evidence-all-required", () -> {
            var i=index(targets(),List.of(path("p",1,UpgradePath.Mode.OPEN,List.of("diamond"),"",Set.of("a","b"))));
            eq(CatalogResult.Status.PATH_DENIED,SERVICE.recommend(snap(IRON,1),i,access(Set.of(),Set.of("a"))).status());
            eq(CatalogResult.Status.OK,SERVICE.recommend(snap(IRON,1),i,access(Set.of(),Set.of("a","b"))).status());
        });
        test("browse.require-path-option", () -> {
            var def=new CatalogDefinitions(settings("1.25","12","2",false),targets(),List.of());
            var i=CatalogIndex.build(1,def,values(),probes(targets())); eq(CatalogResult.Status.NO_PATH,browse(i).status());
        });
        test("browse.target-permission", () -> {
            var t=custom("diamond",DIAMOND,1,"materials",Set.of(),"vip",Set.of(),Set.of(),0,true);
            eq(List.of("gold"),ids(browse(index(List.of(target("gold",GOLD),t),List.of()))));
        });
        test("browse.target-condition", () -> {
            var t=custom("diamond",DIAMOND,1,"materials",Set.of(),"",Set.of("quest"),Set.of(),0,true);
            eq(CatalogResult.Status.NO_TARGETS,browse(index(List.of(t),List.of())).status());
        });
        test("browse.allowed-source-restriction", () -> {
            var t=custom("diamond",DIAMOND,1,"materials",Set.of(),"",Set.of(),Set.of(GOLD),0,true);
            eq(CatalogResult.Status.NO_TARGETS,browse(index(List.of(t),List.of())).status());
        });
        test("browse.path-cannot-bypass-target-permission", () -> {
            var t=custom("diamond",DIAMOND,1,"materials",Set.of(),"vip",Set.of(),Set.of(),0,true);
            eq(CatalogResult.Status.NO_TARGETS,browse(index(List.of(t),List.of(path("p",1,UpgradePath.Mode.LOCKED,List.of("diamond"),"",Set.of())))).status());
        });
        test("browse.no-same-key-quantity-reshuffle", () -> {
            var t=custom("iron2",IRON,2,"materials",Set.of(),"",Set.of(),Set.of(),0,true);
            eq(CatalogResult.Status.NO_TARGETS,browse(index(List.of(t),List.of())).status());
        });
        test("browse.source-total-not-unit", () -> eq(CatalogResult.Status.NO_TARGETS,SERVICE.recommend(snap(IRON,10),index(targets(),List.of()),access()).status()));
        test("browse.equality-not-upgrade", () -> {
            var v=ValueDefinitions.manualOnly(Map.of(IRON,d("900"),DIAMOND,d("900")));
            var def=new CatalogDefinitions(settings("1","12","2",true),List.of(target("diamond",DIAMOND)),List.of());
            eq(CatalogResult.Status.NO_TARGETS,browse(CatalogIndex.build(1,def,v,probes(def.targets().values().stream().toList()))).status());
        });
        test("browse.boundaries-exact-decimals", () -> {
            var a=target("lo",k("minecraft:lo")); var b=target("hi",k("minecraft:hi"));var below=target("below",k("minecraft:below"));var above=target("above",k("minecraft:above"));
            var ts=List.of(a,b,below,above);var v=ValueDefinitions.manualOnly(Map.of(IRON,d("100"),a.item(),d("125"),b.item(),d("1200"),below.item(),d("124.999999"),above.item(),d("1200.000001")));
            eq(Set.of("lo","hi"),new HashSet<>(ids(browse(CatalogIndex.build(1,defs(ts,List.of()),v,probes(ts))))));
        });
        test("browse.unknown-source-rejected", () -> eq(CatalogResult.Status.SOURCE_REJECTED,SERVICE.recommend(snap(k("minecraft:unknown"),1),index(targets(),List.of()),access()).status()));
        test("browse.risky-source-rejected", () -> {
            var f=new ItemFacts(IRON,1,0,0,Map.of(),"",Set.of(ItemFacts.Risk.UNKNOWN_CUSTOM_METADATA));
            eq(CatalogResult.Status.SOURCE_REJECTED,SERVICE.recommend(snapshot(f),index(targets(),List.of()),access()).status());
        });
        test("browse.literal-search", () -> {
            var i=index(targets(),List.of());eq(List.of("diamond"),ids(SERVICE.browse(snap(IRON,1),i,access(),query(1,12,"","DIAMOND",CatalogQuery.Sort.ID))));
            eq(CatalogResult.Status.NO_TARGETS,SERVICE.browse(snap(IRON,1),i,access(),query(1,12,"",".*",CatalogQuery.Sort.ID)).status());
        });
        test("browse.search-does-not-parse-name-tags", () -> {
            var i=index(targets(),List.of());eq(CatalogResult.Status.NO_TARGETS,SERVICE.browse(snap(IRON,1),i,access(),query(1,12,"","<gold>",CatalogQuery.Sort.ID)).status());
        });
        test("browse.category-filter", () -> eq(CatalogResult.Status.NO_TARGETS,SERVICE.browse(snap(IRON,1),index(targets(),List.of()),access(),query(1,12,"weapons","",CatalogQuery.Sort.ID)).status()));
        test("browse.provider-filter", () -> eq(CatalogResult.Status.NO_TARGETS,SERVICE.browse(snap(IRON,1),index(targets(),List.of()),access(),new CatalogQuery(1,12,"","nexo",Set.of(),"",CatalogQuery.Sort.ID)).status()));
        test("browse.tags-and-semantics", () -> {
            var t=custom("diamond",DIAMOND,1,"materials",Set.of("gem","vanilla"),"",Set.of(),Set.of(),0,true);var i=index(List.of(t),List.of());
            eq(List.of("diamond"),ids(SERVICE.browse(snap(IRON,1),i,access(),new CatalogQuery(1,12,"","",Set.of("gem","vanilla"),"",CatalogQuery.Sort.ID))));
            eq(CatalogResult.Status.NO_TARGETS,SERVICE.browse(snap(IRON,1),i,access(),new CatalogQuery(1,12,"","",Set.of("gem","epic"),"",CatalogQuery.Sort.ID)).status());
        });
        test("browse.sort-value-desc-id", () -> {
            var i=index(targets(),List.of());eq(List.of("diamond","gold"),ids(SERVICE.browse(snap(IRON,1),i,access(),query(1,12,"","",CatalogQuery.Sort.VALUE_DESC))));
            eq(List.of("diamond","gold"),ids(SERVICE.browse(snap(IRON,1),i,access(),query(1,12,"","",CatalogQuery.Sort.ID))));
        });
        test("browse.explicit-rank-before-priority", () -> {
            var a=custom("gold",GOLD,1,"materials",Set.of(),"",Set.of(),Set.of(),999,true);
            eq(List.of("diamond","gold"),ids(browse(index(List.of(a,target("diamond",DIAMOND)),List.of(path("p",1,UpgradePath.Mode.OPEN,List.of("diamond"),"",Set.of()))))));
        });
        test("browse.priority-before-price-distance", () -> {
            var t=custom("diamond",DIAMOND,1,"materials",Set.of(),"",Set.of(),Set.of(),1,true);
            eq(List.of("diamond","gold"),ids(browse(index(List.of(target("gold",GOLD),t),List.of()))));
        });
        test("browse.pagination-no-duplicate", () -> {
            var i=index(targets(),List.of());var p1=SERVICE.browse(snap(IRON,1),i,access(),query(1,1,"","",CatalogQuery.Sort.VALUE_ASC));
            var p2=SERVICE.browse(snap(IRON,1),i,access(),query(2,1,"","",CatalogQuery.Sort.VALUE_ASC));
            eq(List.of("gold"),ids(p1));eq(List.of("diamond"),ids(p2));eq(2,p1.totalPages());eq(2,p2.totalMatches());
        });
        test("browse.page-bounds-before-integer-multiply", () -> {
            var r=SERVICE.browse(snap(IRON,1),index(targets(),List.of()),access(),query(Integer.MAX_VALUE,45,"","",CatalogQuery.Sort.ID));
            eq(CatalogResult.Status.PAGE_OUT_OF_RANGE,r.status());eq(2,r.totalMatches());check(r.rows().isEmpty());
        });
        test("browse.access-filter-before-pagination", () -> {
            var restricted=custom("diamond",DIAMOND,1,"materials",Set.of(),"vip",Set.of(),Set.of(),0,true);
            var r=SERVICE.browse(snap(IRON,1),index(List.of(target("gold",GOLD),restricted),List.of()),access(),query(1,1,"","",CatalogQuery.Sort.ID));
            eq(List.of("gold"),ids(r));eq(1,r.totalPages());eq(1,r.totalMatches());
        });
    }
    private static void selectionTests() {
        test("selection.valid-is-preview-only", () -> {var i=index(targets(),List.of());var s=selection(i,access());eq(TargetSelectionService.Check.VALID_PREVIEW,validate(s,i,access(),snap(IRON,1),NOW));});
        test("selection.wrong-viewer", () -> {var i=index(targets(),List.of());eq(TargetSelectionService.Check.WRONG_VIEWER,validate(selection(i,access()),i,new CatalogAccess(UUID.randomUUID(),Set.of(),Set.of()),snap(IRON,1),NOW));});
        test("selection.wrong-session", () -> {var i=index(targets(),List.of());eq(TargetSelectionService.Check.WRONG_SESSION,new TargetSelectionService().validate(selection(i,access()),UUID.randomUUID(),snap(IRON,1),i,access(),NOW));});
        test("selection.reload-revision-stale", () -> {var i=index(targets(),List.of());var next=CatalogIndex.build(2,i.definitions(),i.values(),probes(targets()));eq(TargetSelectionService.Check.STALE_REVISION,validate(selection(i,access()),next,access(),snap(IRON,1),NOW));});
        test("selection.same-revision-rebuild-stale", () -> {var i=index(targets(),List.of());eq(TargetSelectionService.Check.STALE_CATALOG,validate(selection(i,access()),index(targets(),List.of()),access(),snap(IRON,1),NOW));});
        test("selection.expiry-inclusive", () -> {var i=index(targets(),List.of());eq(TargetSelectionService.Check.EXPIRED,validate(selection(i,access()),i,access(),snap(IRON,1),NOW.plusSeconds(30)));});
        test("selection.before-expiry", () -> {var i=index(targets(),List.of());eq(TargetSelectionService.Check.VALID_PREVIEW,validate(selection(i,access()),i,access(),snap(IRON,1),NOW.plusSeconds(29)));});
        test("selection.negative-duration", () -> {var i=index(targets(),List.of());illegal(()->new TargetSelectionService().select(SESSION,snap(IRON,1),i,access(),"diamond",NOW,Duration.ofSeconds(-1)));});
        test("selection.zero-duration", () -> {var i=index(targets(),List.of());illegal(()->new TargetSelectionService().select(SESSION,snap(IRON,1),i,access(),"diamond",NOW,Duration.ZERO));});
        test("selection.overlong-duration", () -> {var i=index(targets(),List.of());illegal(()->new TargetSelectionService().select(SESSION,snap(IRON,1),i,access(),"diamond",NOW,Duration.ofMinutes(6)));});
        test("selection.source-quantity-changed", () -> {var i=index(targets(),List.of());eq(TargetSelectionService.Check.SOURCE_CHANGED,validate(selection(i,access()),i,access(),snap(IRON,2),NOW));});
        test("selection.source-payload-changed", () -> {var i=index(targets(),List.of());eq(TargetSelectionService.Check.SOURCE_CHANGED,validate(selection(i,access()),i,access(),new ItemSnapshot(ItemFacts.clean(IRON,1),new byte[]{9}),NOW));});
        test("selection.source-facts-checked-too", () -> {
            var i=index(targets(),List.of());var changed=new ItemFacts(IRON,1,0,0,Map.of("minecraft:luck",1),"",Set.of());
            eq(snap(IRON,1).fingerprint(),snapshot(changed).fingerprint());
            eq(TargetSelectionService.Check.SOURCE_CHANGED,validate(selection(i,access()),i,access(),snapshot(changed),NOW));
        });
        test("selection.permission-revoked", () -> {
            var t=custom("diamond",DIAMOND,1,"materials",Set.of(),"vip",Set.of(),Set.of(),0,true);var i=index(List.of(t),List.of());
            eq(TargetSelectionService.Check.NO_LONGER_ELIGIBLE,validate(selection(i,access(Set.of("vip"),Set.of())),i,access(),snap(IRON,1),NOW));
        });
        test("selection.condition-revoked", () -> {
            var i=index(targets(),List.of(path("p",1,UpgradePath.Mode.OPEN,List.of("diamond"),"",Set.of("quest"))));
            eq(TargetSelectionService.Check.NO_LONGER_ELIGIBLE,validate(selection(i,access(Set.of(),Set.of("quest"))),i,access(),snap(IRON,1),NOW));
        });
        test("selection.target-fingerprint-checked", () -> {
            var i=index(targets(),List.of());var s=selection(i,access());
            var corrupt=new TargetSelectionService.Selection(s.viewerId(),s.sessionId(),s.revision(),s.catalogGeneration(),s.sourceFingerprint(),s.sourceFacts(),s.targetId(),"0".repeat(64),s.expiresAt());
            eq(TargetSelectionService.Check.TARGET_CHANGED,validate(corrupt,i,access(),snap(IRON,1),NOW));
        });
        test("selection.cannot-select-locked-out-target", () -> {
            var i=index(targets(),List.of(path("p",1,UpgradePath.Mode.LOCKED,List.of("gold"),"",Set.of())));illegal(()->selection(i,access()));
        });
        test("selection.exact-target-id-not-substring", () -> {var i=index(targets(),List.of());illegal(()->new TargetSelectionService().select(SESSION,snap(IRON,1),i,access(),"diam",NOW,Duration.ofSeconds(30)));});
    }
    private static void argumentTests() {
        test("arguments.defaults", () -> eq(CatalogQuery.firstPage(12),CatalogArguments.parse(List.of(),12)));
        test("arguments.valid-page-category-sort", () -> eq(new CatalogQuery(2,12,"materials","",Set.of(),"",CatalogQuery.Sort.VALUE_DESC),CatalogArguments.parse(List.of("2","materials","value_desc"),12)));
        test("arguments.all-means-no-category-filter", () -> eq("",CatalogArguments.parse(List.of("1","all"),12).category()));
        for(String bad:List.of("0","-1","+1","01","1.0","1e2","NaN","9999999999","1\n"))
            test("arguments.reject-page-"+bad.replace("\n","newline"),()->illegal(()->CatalogArguments.parse(List.of(bad),12)));
        test("arguments.reject-fourth-argument",()->illegal(()->CatalogArguments.parse(List.of("1","all","id","extra"),12)));
        test("arguments.reject-sort-injection",()->illegal(()->CatalogArguments.parse(List.of("1","all","id;op"),12)));
        test("query.literal-no-control",()->illegal(()->query(1,12,"","x\ny",CatalogQuery.Sort.ID)));
        test("query.literal-length-bound",()->illegal(()->query(1,12,"","x".repeat(65),CatalogQuery.Sort.ID)));
        test("query.tags-bound",()->illegal(()->new CatalogQuery(1,12,"","",Set.of("bad tag"),"",CatalogQuery.Sort.ID)));
        test("query.page-zero",()->illegal(()->query(0,12,"","",CatalogQuery.Sort.ID)));
        test("query.size-over-45",()->illegal(()->query(1,46,"","",CatalogQuery.Sort.ID)));
    }

    private static void requestGateTests() {
        test("gate.single-request-per-viewer", () -> {var gate=new PreviewRequestGate(2,Duration.ofSeconds(30),()->0L);check(gate.begin(VIEWER).isPresent());check(gate.begin(VIEWER).isEmpty());});
        test("gate.capacity-bounded", () -> {var gate=new PreviewRequestGate(1,Duration.ofSeconds(30),()->0L);check(gate.begin(VIEWER).isPresent());check(gate.begin(UUID.randomUUID()).isEmpty());eq(1,gate.size());});
        test("gate.timeout-frees-lost-callback", () -> {
            var clock=new AtomicLong();var gate=new PreviewRequestGate(1,Duration.ofSeconds(30),clock::get);var old=gate.begin(VIEWER).orElseThrow();
            clock.set(30_000_000_000L);check(!gate.isCurrent(old));check(gate.begin(VIEWER).isPresent());
        });
        test("gate.old-callback-cannot-finish-new-job", () -> {
            var clock=new AtomicLong();var gate=new PreviewRequestGate(1,Duration.ofSeconds(30),clock::get);var old=gate.begin(VIEWER).orElseThrow();
            gate.forget(VIEWER);var next=gate.begin(VIEWER).orElseThrow();check(!gate.finish(old));check(gate.isCurrent(next));
        });
        test("gate.finish-idempotent", () -> {var gate=new PreviewRequestGate(1,Duration.ofSeconds(30),()->0L);var t=gate.begin(VIEWER).orElseThrow();check(gate.finish(t));check(!gate.finish(t));eq(0,gate.size());});
        test("gate.stopped-rejects-and-drops-late-results", () -> {var gate=new PreviewRequestGate(1,Duration.ofSeconds(30),()->0L);var t=gate.begin(VIEWER).orElseThrow();gate.close();check(!gate.isCurrent(t));check(gate.begin(VIEWER).isEmpty());eq(0,gate.size());});
        test("gate.nano-time-wrap-safe", () -> {var clock=new AtomicLong(Long.MAX_VALUE-100);var gate=new PreviewRequestGate(1,Duration.ofMillis(100),clock::get);var t=gate.begin(VIEWER).orElseThrow();clock.addAndGet(100_000_000);check(!gate.isCurrent(t));});
        test("gate.invalid-settings", () -> {illegal(()->new PreviewRequestGate(0,Duration.ofSeconds(1),()->0L));illegal(()->new PreviewRequestGate(1,Duration.ZERO,()->0L));});
    }
    private static void invariantTests() {
        test("invariant.config-invalid-keeps-old-catalog", () -> {
            var store=new RuntimeStore<CatalogDefinitions>();var valid=defs(targets(),List.of());check(store.commit(store.beginReload().orElseThrow(),valid));
            var ticket=store.beginReload().orElseThrow();try {defs(targets(),List.of(path("bad",1,UpgradePath.Mode.OPEN,List.of("ghost"),"",Set.of())));throw new AssertionError("expected failure");}
            catch(IllegalArgumentException expected){check(store.reject(ticket));}
            eq(1L,store.snapshot().orElseThrow().revision());check(store.snapshot().orElseThrow().value()==valid);
        });
        test("invariant.order-independent-128-shuffles", () -> {
            var list=new ArrayList<TargetDefinition>(); var prices=new HashMap<ItemKey,BigDecimal>();prices.put(IRON,d("100"));
            for(int n=0;n<20;n++){var t=target("x"+String.format(java.util.Locale.ROOT,"%02d",n),k("minecraft:x"+n));list.add(t);prices.put(t.item(),d("200"));}
            var expected=list.stream().map(TargetDefinition::id).toList();var random=new Random(192);
            for(int n=0;n<128;n++){Collections.shuffle(list,random);var i=CatalogIndex.build(1,defs(list,List.of()),ValueDefinitions.manualOnly(prices),probes(list));eq(expected,ids(SERVICE.browse(snap(IRON,1),i,access(),CatalogQuery.firstPage(45))));}
        });
        test("invariant.selection-not-truncated-to-first-45", () -> {
            var list=new ArrayList<TargetDefinition>();var prices=new HashMap<ItemKey,BigDecimal>();prices.put(IRON,d("100"));
            for(int n=0;n<60;n++){var t=target("diamond_"+n,k("minecraft:x"+n));list.add(t);prices.put(t.item(),d("200"));}
            var exact=target("diamond",DIAMOND);list.add(exact);prices.put(DIAMOND,d("200"));
            var i=CatalogIndex.build(1,defs(list,List.of()),ValueDefinitions.manualOnly(prices),probes(list));
            var s=new TargetSelectionService().select(SESSION,snap(IRON,1),i,access(),"diamond_59",NOW,Duration.ofSeconds(10));
            eq("diamond_59",s.targetId());eq(TargetSelectionService.Check.VALID_PREVIEW,validate(s,i,access(),snap(IRON,1),NOW));
        });
        test("invariant.250-random-catalogs-independent-oracle", () -> {
            var random=new Random(9171);
            for(int round=0;round<250;round++) {
                var list=new ArrayList<TargetDefinition>();var prices=new HashMap<ItemKey,BigDecimal>();prices.put(IRON,d("100"));
                for(int n=0;n<80;n++) {
                    var key=k("minecraft:p"+n);var t=custom("t"+n,key,1+random.nextInt(3),n%2==0?"materials":"weapons",Set.of(),n%3==0?"vip":"",Set.of(),Set.of(),0,true);
                    list.add(t);prices.put(key,BigDecimal.valueOf(20+random.nextInt(1200)));
                }
                int qty=1+random.nextInt(8); boolean vip=random.nextBoolean(); boolean locked=random.nextBoolean();
                List<String> lockedIds=list.subList(0,30).stream().map(TargetDefinition::id).toList();
                var paths=locked?List.of(path("lock",1,UpgradePath.Mode.LOCKED,lockedIds,"",Set.of())):List.<UpgradePath>of();
                var i=CatalogIndex.build(1,defs(list,paths),ValueDefinitions.manualOnly(prices),probes(list));
                var source=snap(IRON,qty);var ctx=access(vip?Set.of("vip"):Set.of(),Set.of());var min=BigDecimal.valueOf(100L*qty).multiply(d("1.25"));var max=BigDecimal.valueOf(1200L*qty);
                Set<String> expected=new HashSet<>();
                for(var t:list) {
                    var price=prices.get(t.item()).multiply(BigDecimal.valueOf(t.amount()));
                    if(price.compareTo(min)>=0 && price.compareTo(max)<=0 && (vip||t.permission().isEmpty()) && (!locked||lockedIds.contains(t.id())))expected.add(t.id());
                }
                int pageSize=1+random.nextInt(20);var q=query(1,pageSize,"","",CatalogQuery.Sort.VALUE_ASC);var first=SERVICE.browse(source,i,ctx,q);
                eq(expected.size(),first.totalMatches());Set<String> seen=new HashSet<>();BigDecimal previous=BigDecimal.ZERO;
                for(int page=1;page<=first.totalPages();page++) {
                    var result=SERVICE.browse(source,i,ctx,query(page,pageSize,"","",CatalogQuery.Sort.VALUE_ASC));eq(CatalogResult.Status.OK,result.status());
                    for(var row:result.rows()){check(seen.add(row.definition().id()));check(row.value().totalValue().compareTo(previous)>=0);previous=row.value().totalValue();}
                }
                eq(expected,seen);
            }
        });
        test("invariant.concurrent-shared-index-256-queries", () -> {
            var i=index(targets(),List.of());var executor=Executors.newFixedThreadPool(4);
            try {
                List<Callable<List<String>>> calls=new ArrayList<>();for(int n=0;n<256;n++)calls.add(()->ids(browse(i)));
                for(var future:executor.invokeAll(calls,10,TimeUnit.SECONDS))eq(List.of("gold","diamond"),future.get());
            } finally {executor.shutdownNow();check(executor.awaitTermination(5,TimeUnit.SECONDS));}
        });
    }

    private static void test(String name,Checked body){TESTS.add(new Test(name,body));}
    private static ItemKey k(String key){return ItemKey.of(key);}
    private static BigDecimal d(String value){return new BigDecimal(value);}
    private static TargetDefinition target(String id,ItemKey key){return custom(id,key,1,"materials",Set.of(),"",Set.of(),Set.of(),0,true);}
    private static TargetDefinition custom(String id,ItemKey key,int count,String category,Set<String> tags,String permission,Set<String> conditions,Set<ItemKey> allowed,int priority,boolean enabled){return new TargetDefinition(id,key,count,"<gold>"+id+"</gold>",category,tags,enabled,priority,permission,conditions,allowed);}
    private static List<TargetDefinition> targets(){return List.of(target("gold",GOLD),target("diamond",DIAMOND));}
    private static UpgradePath path(String id,int priority,UpgradePath.Mode mode,List<String> targets,String permission,Set<String> conditions){return new UpgradePath(id,Set.of(IRON),priority,mode,targets,true,permission,conditions);}
    private static CatalogDefinitions defs(List<TargetDefinition> targets,List<UpgradePath> paths){return new CatalogDefinitions(CatalogSettings.defaults(),targets,paths);}
    private static CatalogSettings settings(String min,String max,String preferred,boolean unpathed){return new CatalogSettings(d(min),d(max),d(preferred),12,3,2048,512,unpathed);}
    private static ValueDefinitions values(){return ValueDefinitions.manualOnly(Map.of(IRON,d("90"),GOLD,d("180"),DIAMOND,d("900")));}
    private static ItemSnapshot snapshot(ItemFacts facts){return new ItemSnapshot(facts,new byte[]{1,2,3,4});}
    private static ItemSnapshot snap(ItemKey key,int amount){return snapshot(ItemFacts.clean(key,amount));}
    private static Map<String,TargetProbe> probes(List<TargetDefinition> targets){var probes=new HashMap<String,TargetProbe>();for(var target:targets)probes.put(target.id(),TargetProbe.verified(snap(target.item(),target.amount())));return probes;}
    private static CatalogIndex index(List<TargetDefinition> targets,List<UpgradePath> paths){return CatalogIndex.build(1,defs(targets,paths),values(),probes(targets));}
    private static CatalogAccess access(){return access(Set.of(),Set.of());}
    private static CatalogAccess access(Set<String> permissions,Set<String> conditions){return new CatalogAccess(VIEWER,permissions,conditions);}
    private static CatalogResult browse(CatalogIndex i){return SERVICE.browse(snap(IRON,1),i,access(),CatalogQuery.firstPage(12));}
    private static CatalogQuery query(int page,int size,String category,String search,CatalogQuery.Sort sort){return new CatalogQuery(page,size,category,"",Set.of(),search,sort);}
    private static List<String> ids(CatalogResult result){return result.rows().stream().map(row->row.definition().id()).toList();}
    private static TargetSelectionService.Selection selection(CatalogIndex i,CatalogAccess a){return new TargetSelectionService().select(SESSION,snap(IRON,1),i,a,"diamond",NOW,Duration.ofSeconds(30));}
    private static TargetSelectionService.Check validate(TargetSelectionService.Selection s,CatalogIndex i,CatalogAccess a,ItemSnapshot source,Instant at){return new TargetSelectionService().validate(s,SESSION,source,i,a,at);}
    private static void illegal(Runnable body) { expect(IllegalArgumentException.class,body); }
    private static void expect(Class<? extends Throwable> type,Runnable body) { assertions++; try { body.run(); } catch(Throwable error) { if(type.isInstance(error)) return; throw new AssertionError("Expected " + type + " but got " + error,error); } throw new AssertionError("Expected " + type.getSimpleName()); }
    private static void check(boolean condition) { assertions++; if(!condition) throw new AssertionError("condition failed"); }
    private static void eq(Object expected,Object actual) { assertions++; if(!java.util.Objects.equals(expected,actual)) throw new AssertionError("Expected " + expected + "; got " + actual); }
    private static void amount(String expected,BigDecimal actual) { assertions++; if(d(expected).compareTo(actual)!=0) throw new AssertionError("Expected decimal " + expected + "; got " + actual); }
    private static String escape(String text) { return text.replace("&","&amp;").replace("\"","&quot;").replace("<","&lt;").replace(">","&gt;"); }
}
