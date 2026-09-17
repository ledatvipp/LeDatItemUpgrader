package vn.ledat.itemupgrader.demo;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import vn.ledat.itemupgrader.boost.BoostDefinition;
import vn.ledat.itemupgrader.boost.PermissionBonus;
import vn.ledat.itemupgrader.catalog.CatalogAccess;
import vn.ledat.itemupgrader.catalog.CatalogDefinitions;
import vn.ledat.itemupgrader.catalog.CatalogIndex;
import vn.ledat.itemupgrader.catalog.CatalogSettings;
import vn.ledat.itemupgrader.catalog.TargetDefinition;
import vn.ledat.itemupgrader.catalog.TargetProbe;
import vn.ledat.itemupgrader.chance.ChanceFormula;
import vn.ledat.itemupgrader.cost.CostEntry;
import vn.ledat.itemupgrader.cost.CostPlanner;
import vn.ledat.itemupgrader.cost.CostResource;
import vn.ledat.itemupgrader.cost.ResourceSnapshot;
import vn.ledat.itemupgrader.item.ItemFacts;
import vn.ledat.itemupgrader.item.ItemKey;
import vn.ledat.itemupgrader.item.ItemSnapshot;
import vn.ledat.itemupgrader.profile.RiskProfile;
import vn.ledat.itemupgrader.quote.QuoteRequest;
import vn.ledat.itemupgrader.quote.QuoteSettings;
import vn.ledat.itemupgrader.quote.UpgradeQuoteService;
import vn.ledat.itemupgrader.quote.UpgradeRules;
import vn.ledat.itemupgrader.util.Decimals;
import vn.ledat.itemupgrader.value.ValueDefinitions;

/** Detached fixture only: no Paper, YAML loader, provider calls, inventory effects or upgrade outcome RNG. */
public final class QuoteDemo {
    private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final CostResource VAULT = CostResource.currency(CostResource.Currency.VAULT);
    private QuoteDemo() {}
    public static void main(String[] args) {
        ItemKey iron = ItemKey.of("minecraft:iron_ingot"), diamond = ItemKey.of("minecraft:diamond");
        ItemKey emerald = ItemKey.of("minecraft:emerald"), paper = ItemKey.of("minecraft:paper");
        var target = new TargetDefinition("diamond", diamond, 1, "Diamond", "materials", Set.of(), true, 0, "", Set.of(), Set.of());
        var catalog = new CatalogDefinitions(CatalogSettings.defaults(), List.of(target), List.of());
        var values = ValueDefinitions.manualOnly(Map.of(iron, decimal("90"), diamond, decimal("900")));
        var index = CatalogIndex.build(1, catalog, values, Map.of("diamond", TargetProbe.verified(snapshot(diamond, 1))));
        var rules = new UpgradeRules(catalog, QuoteSettings.defaults(), Map.of("ratio", new ChanceFormula.Ratio(decimal("0.9"))),
                List.of(profile("standard", "1", "1", RiskProfile.FailureMode.DESTROY, List.of()),
                        profile("safe", "0.60", "1.75", RiskProfile.FailureMode.KEEP, List.of(cost(VAULT, "25", CostEntry.ConsumeWhen.ON_ATTEMPT))),
                        profile("risky", "1.30", "0.75", RiskProfile.FailureMode.DESTROY, List.of(cost(VAULT, "10", CostEntry.ConsumeWhen.ON_ATTEMPT)))),
                List.of(new BoostDefinition("lucky_shard", true, "luck", Set.of("standard", "risky"), decimal("1"), decimal("5"),
                                BoostDefinition.Protection.NONE, List.of(cost(CostResource.item(emerald), "2", CostEntry.ConsumeWhen.ON_ATTEMPT)), "", Set.of()),
                        new BoostDefinition("protection_scroll", true, "protection", Set.of("standard", "risky"), decimal("1"), decimal("0"),
                                BoostDefinition.Protection.KEEP_SOURCE_ON_FAILURE, List.of(cost(CostResource.item(paper), "1", CostEntry.ConsumeWhen.ON_FAILURE)), "", Set.of())),
                List.of(new PermissionBonus("vip", true, "rank", 10, "ledatitemupgrader.bonus.vip", Set.of(), decimal("1.05"), decimal("0"))), List.of(), List.of());
        // These bonuses are enabled ONLY in this synthetic demo; bundled boost/permission YAML defaults are disabled.
        var source = snapshot(iron, 1);
        var resources = new ResourceSnapshot(VIEWER, Map.of(VAULT, decimal("1000")), Set.of(emerald, paper), List.of(
                new ResourceSnapshot.ItemSupply(1, emerald, 4, snapshot(emerald, 4).fingerprint()),
                new ResourceSnapshot.ItemSupply(2, paper, 1, snapshot(paper, 1).fingerprint())), Set.of(0));
        var service = new UpgradeQuoteService();
        System.out.println("SOURCE iron x1 = 90; TARGET diamond x1 = 900. All inputs are synthetic.");
        for (String profile : List.of("standard", "safe", "risky")) show(service, source, index, rules, resources, profile, List.of(), false);
        show(service, source, index, rules, resources, "standard", List.of(), true);
        show(service, source, index, rules, resources, "standard", List.of("lucky_shard"), false);
        show(service, source, index, rules, resources, "standard", List.of("lucky_shard"), true);
        show(service, source, index, rules, resources, "standard", List.of("protection_scroll"), false);
        show(service, source, index, rules, ResourceSnapshot.empty(VIEWER, 0), "safe", List.of(), false);
        var mutuallyExclusive = new CostPlanner().plan(List.of(cost(VAULT, "10", CostEntry.ConsumeWhen.ON_ATTEMPT),
                cost(VAULT, "20", CostEntry.ConsumeWhen.ON_SUCCESS), cost(VAULT, "30", CostEntry.ConsumeWhen.ON_FAILURE)), decimal("1"), List.of()).lines().getFirst();
        System.out.println("OUTCOME COST DEMO reserve=" + Decimals.display(mutuallyExclusive.reserve()) + "; success="
                + Decimals.display(mutuallyExclusive.consumed(true)) + "; failure=" + Decimals.display(mutuallyExclusive.consumed(false)));
        System.out.println("READ-ONLY: no outcome draw, debit, item removal, reward, GUI or actual reservation occurred.");
    }
    private static void show(UpgradeQuoteService service, ItemSnapshot source, CatalogIndex index, UpgradeRules rules,
                             ResourceSnapshot resources, String profile, List<String> boosts, boolean vip) {
        var access = new CatalogAccess(VIEWER, vip ? Set.of("ledatitemupgrader.bonus.vip") : Set.of(), Set.of());
        var request = new QuoteRequest(SESSION, "diamond", profile, boosts, 0);
        var result = service.quote(request, source, index, access, rules, resources, Instant.parse("2026-09-16T00:00:00Z"));
        var quote = result.quote().orElseThrow();
        System.out.println("QUOTE profile=" + profile + "; boosts=" + boosts + "; vip=" + vip + "; chance="
                + Decimals.display(quote.chance().probability().percent()) + "%; failure=" + quote.terms().failure()
                + "; resources=" + quote.resources().status());
        for (var line : quote.terms().costs().lines()) System.out.println("  COST " + line.resource().key() + "; reserve="
                + Decimals.display(line.reserve()) + "; success=" + Decimals.display(line.consumed(true)) + "; failure=" + Decimals.display(line.consumed(false)));
    }
    private static RiskProfile profile(String id, String chance, String fee, RiskProfile.FailureMode failure, List<CostEntry> costs) {
        return new RiskProfile(id, true, "ratio", decimal(chance), decimal(fee), failure, costs, "", Set.of());
    }
    private static CostEntry cost(CostResource resource, String amount, CostEntry.ConsumeWhen consume) { return new CostEntry(resource, decimal(amount), consume); }
    private static BigDecimal decimal(String value) { return new BigDecimal(value); }
    private static ItemSnapshot snapshot(ItemKey key, int amount) { return new ItemSnapshot(ItemFacts.clean(key, amount), new byte[]{1,2,3}); }
}
