package vn.ledat.itemupgrader.demo;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import vn.ledat.itemupgrader.item.*;
import vn.ledat.itemupgrader.recipe.*;
import vn.ledat.itemupgrader.value.*;

/** Developer CLI, not player-facing messages and not a Bukkit ItemStack serializer. */
public final class ValueDemo {
    private ValueDemo() {}
    public static void main(String[] args) {
        ItemKey iron = ItemKey.of("minecraft:iron_ingot");
        ItemKey block = ItemKey.of("minecraft:iron_block");
        RecipeIndex recipes = new RecipeIndex(List.of(new RecipeDefinition("iron_block", block, 1,
                List.of(new RecipeDefinition.Ingredient(List.of(iron), 9)))));
        ValueDefinitions config = new ValueDefinitions(Map.of(iron, new BigDecimal("90")), List.of(), Map.of(),
                Map.of(), Set.of(), ModifierSettings.none(), recipes, ValueLimits.defaults());
        for (ItemFacts facts : List.of(ItemFacts.clean(iron, 1), ItemFacts.clean(iron, 16), ItemFacts.clean(block, 1))) {
            ItemSnapshot snapshot = new ItemSnapshot(facts, ("test-payload:" + facts.key()).getBytes(StandardCharsets.UTF_8));
            System.out.println(facts.key() + " x" + facts.amount() + " -> " + new ItemValueService().evaluate(snapshot, config, 1));
        }
    }
}
