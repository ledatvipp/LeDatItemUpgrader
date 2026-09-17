package vn.ledat.itemupgrader.catalog;

import java.util.Objects;
import java.util.Set;
import vn.ledat.itemupgrader.item.ItemKey;

/** Configured reward TEMPLATE. Name is trusted admin text, never a player-provided action identifier. */
public record TargetDefinition(String id, ItemKey item, int amount, String name, String category,
                               Set<String> tags, boolean enabled, int priority, String permission,
                               Set<String> requiredConditions, Set<ItemKey> allowedSources) {
    public TargetDefinition {
        CatalogValidation.id(id, "target.id"); Objects.requireNonNull(item, "target.item");
        if (amount < 1 || amount > 64) throw new IllegalArgumentException("target.amount: must be 1..64");
        if (name == null || name.isBlank() || name.length() > 512 || name.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("target.name: require printable nonblank text, at most 512 characters");
        CatalogValidation.id(category, "target.category");
        tags = CatalogValidation.ids(tags, 32, "target.tags");
        if (priority < -100000 || priority > 100000) throw new IllegalArgumentException("target.priority: out of range");
        CatalogValidation.permission(permission);
        requiredConditions = CatalogValidation.ids(requiredConditions, 32, "target.conditions");
        allowedSources = Set.copyOf(allowedSources);
        if (allowedSources.size() > 256) throw new IllegalArgumentException("target.allowed-sources: too many items");
    }
}
