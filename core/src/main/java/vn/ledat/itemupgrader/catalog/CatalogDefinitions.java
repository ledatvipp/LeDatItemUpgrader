package vn.ledat.itemupgrader.catalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import vn.ledat.itemupgrader.item.ItemKey;

/** Immutable structural indexes built at config load; no provider enumeration on a GUI refresh. */
public final class CatalogDefinitions {
    private final CatalogSettings settings;
    private final Map<String, TargetDefinition> targets;
    private final Map<ItemKey, UpgradePath> selectedPaths;
    private final List<UpgradePath> paths;
    private final Set<String> permissions;
    private final Set<String> conditions;
    private final List<String> categories;
    public CatalogDefinitions(CatalogSettings settings, List<TargetDefinition> targets, List<UpgradePath> paths) {
        this.settings = Objects.requireNonNull(settings, "settings");
        if (targets.size() > settings.maximumTargets() || paths.size() > settings.maximumPaths())
            throw new IllegalArgumentException("catalog/path count exceeds configured limit");
        Map<String, TargetDefinition> entries = new HashMap<>();
        Set<String> physicalTemplates = new HashSet<>();
        Set<String> nodes = new TreeSet<>(); Set<String> checks = new TreeSet<>();
        for (TargetDefinition target : targets) {
            if (entries.putIfAbsent(target.id(), target) != null)
                throw new IllegalArgumentException("catalog.targets: duplicate id " + target.id());
            // An identical provider key + quantity must not appear twice under different access policies.
            if (!physicalTemplates.add(target.item().value() + "#" + target.amount()))
                throw new IllegalArgumentException("catalog.targets: duplicate item/amount " + target.id());
            if (target.enabled()) { if (!target.permission().isEmpty()) nodes.add(target.permission()); checks.addAll(target.requiredConditions()); }
        }
        Map<ItemKey, List<UpgradePath>> matches = new HashMap<>();
        Set<String> pathIds = new HashSet<>();
        int references = 0;
        for (UpgradePath path : paths) {
            if (!pathIds.add(path.id())) throw new IllegalArgumentException("paths: duplicate id " + path.id());
            for (String target : path.targets()) if (!entries.containsKey(target))
                throw new IllegalArgumentException("paths." + path.id() + ": unknown target " + target);
            references += path.sources().size() + path.targets().size();
            if (references > 100000) throw new IllegalArgumentException("paths: reference budget exceeded");
            if (!path.enabled()) continue;
            if (!path.permission().isEmpty()) nodes.add(path.permission()); checks.addAll(path.requiredConditions());
            for (ItemKey key : path.sources()) matches.computeIfAbsent(key, unused -> new ArrayList<>()).add(path);
        }
        Map<ItemKey, UpgradePath> selected = new HashMap<>();
        matches.forEach((key, list) -> {
            Set<Integer> priorities = new HashSet<>();
            for (UpgradePath path : list) if (!priorities.add(path.priority()))
                throw new IllegalArgumentException("paths: ambiguous priority for " + key + " at " + path.priority());
            list.sort(Comparator.comparingInt(UpgradePath::priority).reversed().thenComparing(UpgradePath::id));
            selected.put(key, list.getFirst());
        });
        if (nodes.size() > 12000 || checks.size() > 12000) throw new IllegalArgumentException("catalog: access-node budget exceeded");
        this.categories = entries.values().stream().filter(TargetDefinition::enabled).map(TargetDefinition::category).distinct().sorted().toList();
        this.targets = Map.copyOf(entries); this.paths = List.copyOf(paths); this.selectedPaths = Map.copyOf(selected);
        this.permissions = Set.copyOf(nodes); this.conditions = Set.copyOf(checks);
    }
    public CatalogSettings settings() { return settings; }
    public Map<String, TargetDefinition> targets() { return targets; }
    public List<UpgradePath> paths() { return paths; }
    public Optional<UpgradePath> pathFor(ItemKey source) { return Optional.ofNullable(selectedPaths.get(source)); }
    public Set<String> permissionNodes() { return permissions; }
    public List<String> categories() { return categories; }
    public Set<String> conditionIds() { return conditions; }
    public static CatalogDefinitions empty() { return new CatalogDefinitions(CatalogSettings.defaults(), List.of(), List.of()); }
}
