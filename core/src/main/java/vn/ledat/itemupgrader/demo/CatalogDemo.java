package vn.ledat.itemupgrader.demo;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import vn.ledat.itemupgrader.catalog.*;
import vn.ledat.itemupgrader.item.*;
import vn.ledat.itemupgrader.util.Decimals;
import vn.ledat.itemupgrader.value.ValueDefinitions;

/** Console-only backend demonstration with synthetic payloads; does not launch a Minecraft server. */
public final class CatalogDemo {
    private CatalogDemo() {}
    public static void main(String[] args) {
        ItemKey iron = ItemKey.of("minecraft:iron_ingot"); ItemKey gold = ItemKey.of("minecraft:gold_ingot");
        ItemKey diamond = ItemKey.of("minecraft:diamond"); ItemKey sword = ItemKey.of("minecraft:diamond_sword");
        var targets = List.of(target("gold_ingot", gold, "materials"), target("diamond", diamond, "materials"), target("diamond_sword", sword, "weapons"));
        var definitions = new CatalogDefinitions(CatalogSettings.defaults(), targets, List.of(
                new UpgradePath("iron_progression", Set.of(iron), 100, UpgradePath.Mode.LOCKED, List.of("gold_ingot", "diamond"), true, "", Set.of()),
                new UpgradePath("gold_progression", Set.of(gold), 100, UpgradePath.Mode.OPEN, List.of("diamond", "diamond_sword"), true, "", Set.of())));
        var values = ValueDefinitions.manualOnly(Map.of(iron, new BigDecimal("90"), gold, new BigDecimal("180"), diamond, new BigDecimal("900"), sword, new BigDecimal("1800")));
        Map<String, TargetProbe> probes = new HashMap<>();
        for (var target : targets) probes.put(target.id(), TargetProbe.verified(snapshot(target.item(), target.amount())));
        CatalogIndex catalog = CatalogIndex.build(1, definitions, values, probes);
        CatalogAccess access = new CatalogAccess(UUID.fromString("00000000-0000-0000-0000-000000000001"), Set.of(), Set.of());
        for (ItemSnapshot source : List.of(snapshot(iron, 1), snapshot(gold, 1), snapshot(iron, 10))) {
            var result = new CatalogService().recommend(source, catalog, access);
            System.out.println("SOURCE " + source.facts().key() + " x" + source.facts().amount() + " -> " + result.status()
                    + "; path=" + result.path().map(UpgradePath::id).orElse("none"));
            for (var row : result.rows()) System.out.println("  TARGET " + row.definition().id() + "; total-value=" + Decimals.display(row.value().totalValue()));
        }
        System.out.println("Read-only detached demo: no server, inventory, packets, chance, fee, or reward execution.");
    }
    private static TargetDefinition target(String id, ItemKey item, String category) {
        return new TargetDefinition(id, item, 1, id, category, Set.of(), true, 0, "", Set.of(), Set.of());
    }
    private static ItemSnapshot snapshot(ItemKey key, int amount) { return new ItemSnapshot(ItemFacts.clean(key, amount), new byte[]{1,2,3}); }
}
