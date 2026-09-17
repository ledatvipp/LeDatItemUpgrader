package vn.ledat.itemupgrader.recipe;

import java.util.List;
import java.util.Objects;
import vn.ledat.itemupgrader.item.ItemKey;

/** Explicit, reviewed conversion only. Runtime imports with remainders/complex ingredients must be excluded. */
public record RecipeDefinition(String id, ItemKey result, int outputAmount, List<Ingredient> ingredients) {
    public record Ingredient(List<ItemKey> alternatives, int amount) {
        public Ingredient {
            alternatives = List.copyOf(alternatives);
            if (alternatives.isEmpty() || alternatives.size() > 64 || amount < 1 || amount > 64)
                throw new IllegalArgumentException("invalid ingredient");
            if (alternatives.stream().distinct().count() != alternatives.size())
                throw new IllegalArgumentException("duplicate ingredient alternative");
        }
    }
    public RecipeDefinition {
        Objects.requireNonNull(id, "recipe id");
        Objects.requireNonNull(result, "result");
        if (!id.matches("[a-z0-9_:./-]{1,128}") || outputAmount < 1 || outputAmount > 64)
            throw new IllegalArgumentException("invalid recipe header");
        ingredients = List.copyOf(ingredients);
        if (ingredients.isEmpty() || ingredients.size() > 9) throw new IllegalArgumentException("invalid ingredient count");
    }
}
