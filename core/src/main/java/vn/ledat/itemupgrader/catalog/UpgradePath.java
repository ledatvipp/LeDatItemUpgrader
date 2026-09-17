package vn.ledat.itemupgrader.catalog;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import vn.ledat.itemupgrader.item.ItemKey;

/** Choose the highest-priority STRUCTURAL match before permission/condition checks. No denied-path fallback. */
public record UpgradePath(String id, Set<ItemKey> sources, int priority, Mode mode, List<String> targets,
                          boolean enabled, String permission, Set<String> requiredConditions) {
    public enum Mode { LOCKED, OPEN }
    public UpgradePath {
        CatalogValidation.id(id, "path.id"); sources = Set.copyOf(sources);
        if (sources.isEmpty() || sources.size() > 256) throw new IllegalArgumentException("path.sources: require 1..256 keys");
        Objects.requireNonNull(mode, "path.mode"); targets = List.copyOf(targets);
        if (targets.isEmpty() || targets.size() > 256 || new HashSet<>(targets).size() != targets.size())
            throw new IllegalArgumentException("path.targets: require 1..256 unique target ids");
        targets.forEach(target -> CatalogValidation.id(target, "path.target"));
        if (priority < -100000 || priority > 100000) throw new IllegalArgumentException("path.priority: out of range");
        CatalogValidation.permission(permission);
        requiredConditions = CatalogValidation.ids(requiredConditions, 32, "path.conditions");
    }
}
