package vn.ledat.itemupgrader.value;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import vn.ledat.itemupgrader.item.ItemFacts;
import vn.ledat.itemupgrader.item.ItemKey;
import vn.ledat.itemupgrader.recipe.RecipeDefinition;

/** Pure resolver chain. No Bukkit, network or live provider access, even during recursive recipe valuation. */
final class BaseValueResolver {
    record Base(BigDecimal value, ValueResult.Source source, String rule) {}
    static final class Rejected extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final ValueResult.Status status;
        Rejected(ValueResult.Status status, String message) { super(message); this.status = status; }
    }
    private final ValueDefinitions definitions;
    private final Set<ItemKey> path = new HashSet<>();
    private final List<String> diagnostics = new ArrayList<>();
    private int visited;
    BaseValueResolver(ValueDefinitions definitions) { this.definitions = definitions; }
    List<String> diagnostics() { return List.copyOf(diagnostics); }
    Optional<Base> resolve(ItemFacts item) { return resolve(item, 0); }
    private Optional<Base> resolve(ItemFacts item, int depth) {
        if (++visited > definitions.limits().recipeMaxNodes() || depth > definitions.limits().recipeMaxDepth())
            throw new Rejected(ValueResult.Status.RECIPE_LIMIT, "recipe traversal budget exceeded");
        ItemKey key = item.key();
        if (definitions.blockedItems().contains(key)) return Optional.empty();
        BigDecimal manual = definitions.manual().get(key);
        if (manual != null) return Optional.of(new Base(manual, ValueResult.Source.MANUAL, key.value()));
        ValueRule best = null;
        boolean ambiguous = false;
        for (ValueRule rule : definitions.rules()) {
            if (!rule.matches(item)) continue;
            if (best == null || rule.priority() > best.priority()) { best = rule; ambiguous = false; }
            else if (rule.priority() == best.priority()) ambiguous = true;
        }
        if (ambiguous) throw new Rejected(ValueResult.Status.AMBIGUOUS_RULE, "equal-priority value rules match " + key);
        if (best != null) return Optional.of(new Base(best.baseValue(), ValueResult.Source.ITEM_RULE, best.id()));
        BigDecimal provider = definitions.providerDefaults().get(key.provider());
        if (provider != null) return Optional.of(new Base(provider, ValueResult.Source.PROVIDER, key.provider()));
        if (!path.add(key)) {
            note("cycle skipped: " + key);
            return Optional.empty();
        }
        Base cheapest = null;
        try {
            for (RecipeDefinition recipe : definitions.recipes().recipesFor(key)) {
                BigDecimal total = BigDecimal.ZERO;
                boolean complete = true;
                for (RecipeDefinition.Ingredient ingredient : recipe.ingredients()) {
                    BigDecimal minimum = null;
                    for (ItemKey alternative : ingredient.alternatives()) {
                        Optional<Base> resolved = resolve(ItemFacts.clean(alternative, 1), depth + 1);
                        // An unpriced alternative might be effectively free. Never price the whole
                        // ingredient from only its expensive, known alternatives.
                        if (resolved.isEmpty()) { complete = false; break; }
                        if (minimum == null || resolved.get().value().compareTo(minimum) < 0)
                            minimum = resolved.get().value();
                    }
                    if (!complete || minimum == null) { complete = false; break; }
                    total = total.add(minimum.multiply(BigDecimal.valueOf(ingredient.amount())));
                }
                if (!complete) { note("incomplete recipe skipped: " + recipe.id()); continue; }
                BigDecimal unit = total.divide(BigDecimal.valueOf(recipe.outputAmount()), new MathContext(28, RoundingMode.DOWN));
                if (unit.signum() > 0 && (cheapest == null || unit.compareTo(cheapest.value()) < 0))
                    cheapest = new Base(unit, ValueResult.Source.RECIPE, recipe.id());
            }
        } finally { path.remove(key); }
        if (cheapest != null) return Optional.of(cheapest);
        BigDecimal rarity = definitions.rarityDefaults().get(item.rarity());
        return rarity == null ? Optional.empty() : Optional.of(new Base(rarity, ValueResult.Source.RARITY, item.rarity()));
    }
    private void note(String text) { if (diagnostics.size() < 32) diagnostics.add(text); }
}
