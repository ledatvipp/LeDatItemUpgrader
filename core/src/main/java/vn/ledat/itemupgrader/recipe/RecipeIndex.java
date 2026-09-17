package vn.ledat.itemupgrader.recipe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import vn.ledat.itemupgrader.item.ItemKey;

public final class RecipeIndex {
    private final Map<ItemKey, List<RecipeDefinition>> byResult;
    public RecipeIndex(List<RecipeDefinition> recipes) {
        if (recipes.size() > 10000) throw new IllegalArgumentException("too many recipes");
        Map<ItemKey, List<RecipeDefinition>> index = new HashMap<>();
        Set<String> ids = new HashSet<>();
        for (RecipeDefinition recipe : recipes) {
            if (!ids.add(recipe.id())) throw new IllegalArgumentException("duplicate recipe id: " + recipe.id());
            List<RecipeDefinition> list = index.computeIfAbsent(recipe.result(), ignored -> new ArrayList<>());
            list.add(recipe);
            if (list.size() > 64) throw new IllegalArgumentException("too many recipes for " + recipe.result());
        }
        Map<ItemKey, List<RecipeDefinition>> copy = new HashMap<>();
        index.forEach((key, list) -> copy.put(key, List.copyOf(list)));
        byResult = Map.copyOf(copy);
    }
    public List<RecipeDefinition> recipesFor(ItemKey item) { return byResult.getOrDefault(item, List.of()); }
    public Set<ItemKey> resultKeys() { return byResult.keySet(); }
    public static RecipeIndex empty() { return new RecipeIndex(List.of()); }
}
