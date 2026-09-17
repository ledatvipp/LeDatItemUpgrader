package vn.ledat.itemupgrader.value;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import vn.ledat.itemupgrader.item.ItemKey;
import vn.ledat.itemupgrader.recipe.RecipeIndex;
import vn.ledat.itemupgrader.util.Decimals;

public record ValueDefinitions(Map<ItemKey, BigDecimal> manual, List<ValueRule> rules,
                               Map<String, BigDecimal> providerDefaults, Map<String, BigDecimal> rarityDefaults,
                               Set<ItemKey> blockedItems, ModifierSettings modifiers,
                               RecipeIndex recipes, ValueLimits limits) {
    public ValueDefinitions {
        manual = Map.copyOf(manual);
        rules = List.copyOf(rules);
        providerDefaults = Map.copyOf(providerDefaults);
        rarityDefaults = Map.copyOf(rarityDefaults);
        blockedItems = Set.copyOf(blockedItems);
        Objects.requireNonNull(modifiers, "modifiers");
        Objects.requireNonNull(recipes, "recipes");
        Objects.requireNonNull(limits, "limits");
        if (manual.size() > 10000 || rules.size() > 2000 || blockedItems.size() > 10000)
            throw new IllegalArgumentException("too many value definitions");
        Set<String> ids = new HashSet<>();
        for (ValueRule rule : rules) if (!ids.add(rule.id())) throw new IllegalArgumentException("duplicate rule id");
        manual.forEach((key, value) -> Decimals.positive(value, key.toString()));
        providerDefaults.forEach((key, value) -> {
            if (!Set.of("minecraft", "mmoitems", "itemsadder", "oraxen", "nexo").contains(key))
                throw new IllegalArgumentException("unknown provider default");
            Decimals.positive(value, key);
        });
        rarityDefaults.forEach((key, value) -> {
            if (!key.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("invalid rarity");
            Decimals.positive(value, key);
        });
        java.util.stream.Stream<BigDecimal> bases = java.util.stream.Stream.concat(
                java.util.stream.Stream.concat(manual.values().stream(), rules.stream().map(ValueRule::baseValue)),
                java.util.stream.Stream.concat(providerDefaults.values().stream(), rarityDefaults.values().stream()));
        if (bases.anyMatch(value -> value.compareTo(limits.maxUnitValue()) > 0))
            throw new IllegalArgumentException("base value above configured limit");
    }
    public static ValueDefinitions manualOnly(Map<ItemKey, BigDecimal> manual) {
        return new ValueDefinitions(manual, List.of(), Map.of(), Map.of(), Set.of(),
                ModifierSettings.none(), RecipeIndex.empty(), ValueLimits.defaults());
    }
}
