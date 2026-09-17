package vn.ledat.itemupgrader.quote;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Objects;
import java.util.function.Function;
import vn.ledat.itemupgrader.boost.BoostDefinition;
import vn.ledat.itemupgrader.boost.PermissionBonus;
import vn.ledat.itemupgrader.catalog.CatalogAccess;
import vn.ledat.itemupgrader.catalog.CatalogDefinitions;
import vn.ledat.itemupgrader.catalog.UpgradePath;
import vn.ledat.itemupgrader.chance.ChanceFormula;
import vn.ledat.itemupgrader.condition.ConditionDefinition;
import vn.ledat.itemupgrader.cost.CostPlanner;
import vn.ledat.itemupgrader.cost.CostResource;
import vn.ledat.itemupgrader.profile.RiskProfile;

/** Fully cross-validated, immutable rules, published together with the catalog in one runtime revision. */
public final class UpgradeRules {
    private final Optional<vn.ledat.itemupgrader.output.OutputRules> outputs;
    private final CatalogDefinitions catalog;
    private final QuoteSettings settings;
    private final Map<String, ChanceFormula> formulas;
    private final Map<String, RiskProfile> profiles;
    private final Map<String, BoostDefinition> boosts;
    private final List<PermissionBonus> bonuses;
    private final Map<String, ConditionDefinition> conditions;
    private final Map<String, PathProfileRule> pathRules;
    private final Set<String> permissionNodes;
    private final Set<String> conditionIds;
    public UpgradeRules(CatalogDefinitions catalog, QuoteSettings settings, Map<String, ChanceFormula> formulas,
                        List<RiskProfile> profiles, List<BoostDefinition> boosts, List<PermissionBonus> bonuses,
                        List<ConditionDefinition> conditions, List<PathProfileRule> pathRules) {
        this(catalog, settings, formulas, profiles, boosts, bonuses, conditions, pathRules, Optional.empty());
    }
    public UpgradeRules(CatalogDefinitions catalog, QuoteSettings settings, Map<String, ChanceFormula> formulas,
                        List<RiskProfile> profiles, List<BoostDefinition> boosts, List<PermissionBonus> bonuses,
                        List<ConditionDefinition> conditions, List<PathProfileRule> pathRules,
                        Optional<vn.ledat.itemupgrader.output.OutputRules> outputs) {
        this.outputs = Objects.requireNonNull(outputs);
        outputs.ifPresent(value -> value.validate(catalog, profiles));
        if (outputs.isEmpty() && profiles.stream().anyMatch(p -> p.failure() == RiskProfile.FailureMode.DAMAGE || p.failure() == RiskProfile.FailureMode.DOWNGRADE))
            throw new IllegalArgumentException("new failure modes require OutputRules");
        this.catalog = Objects.requireNonNull(catalog); this.settings = Objects.requireNonNull(settings);
        this.formulas = Map.copyOf(formulas);
        if (formulas.isEmpty() || formulas.size() > 32) throw new IllegalArgumentException("formulas: require 1..32 entries");
        formulas.keySet().forEach(id -> RuleValidation.id(id, "formula.id"));
        this.profiles = index(profiles, RiskProfile::id, 64, "profiles");
        this.boosts = index(boosts, BoostDefinition::id, 128, "boosts");
        var bonusIndex = index(bonuses, PermissionBonus::id, 128, "permission-bonuses");
        this.bonuses = bonusIndex.values().stream().sorted(Comparator.comparing(PermissionBonus::group).thenComparing(PermissionBonus::id)).toList();
        this.conditions = index(conditions, ConditionDefinition::id, 32, "conditions");
        this.pathRules = index(pathRules, PathProfileRule::pathId, 2000, "path-profile-rules");
        RiskProfile defaultProfile = this.profiles.get(settings.defaultProfile());
        if (defaultProfile == null || !defaultProfile.enabled()) throw new IllegalArgumentException("default profile must exist and be enabled");
        Set<String> nodes = new TreeSet<>(catalog.permissionNodes()); Set<String> conditionIds = new TreeSet<>(catalog.conditionIds());
        Set<CostResource> resources = new HashSet<>();
        for (var profile : profiles) {
            if (!formulas.containsKey(profile.formulaId())) throw new IllegalArgumentException("profiles." + profile.id() + ": missing formula " + profile.formulaId());
            var plan = new CostPlanner().plan(profile.costs(), profile.feeMultiplier(), List.of());
            if (profile.enabled() && profile.failure() == RiskProfile.FailureMode.KEEP && !settings.allowFreeProtection() && !plan.hasFailureLoss())
                throw new IllegalArgumentException("profiles." + profile.id() + ": KEEP without failure/attempt loss requires explicit allow-free-protection");
            profile.costs().forEach(cost -> resources.add(cost.resource()));
            collect(profile.permission(), profile.requiredConditions(), nodes, conditionIds);
        }
        for (var boost : boosts) {
            for (String profile : boost.allowedProfiles()) if (!this.profiles.containsKey(profile)) throw new IllegalArgumentException("boosts." + boost.id() + ": unknown profile " + profile);
            boost.costs().forEach(cost -> resources.add(cost.resource()));
            collect(boost.permission(), boost.requiredConditions(), nodes, conditionIds);
        }
        if (resources.size() > 32) throw new IllegalArgumentException("more than 32 distinct configured cost resources");
        Set<String> priorities = new HashSet<>(); Set<String> groups = new HashSet<>();
        for (var bonus : bonuses) {
            collect(bonus.permission(), bonus.requiredConditions(), nodes, conditionIds);
            if (bonus.enabled()) {
                groups.add(bonus.group());
                if (!priorities.add(bonus.group() + ":" + bonus.priority())) throw new IllegalArgumentException("permission bonus group has ambiguous priority");
            }
        }
        if (groups.size() > 16) throw new IllegalArgumentException("more than 16 permission bonus groups");
        Set<String> paths = catalog.paths().stream().map(UpgradePath::id).collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (var rule : pathRules) {
            if (!paths.contains(rule.pathId())) throw new IllegalArgumentException("path-profile-rules: unknown path " + rule.pathId());
            for (String profile : rule.allowedProfiles()) if (!this.profiles.containsKey(profile)) throw new IllegalArgumentException("path-profile-rules: unknown profile " + profile);
            if (!this.profiles.get(rule.defaultProfile()).enabled()) throw new IllegalArgumentException("path-profile-rules: default profile is disabled");
        }
        if (nodes.size() > 12000) throw new IllegalArgumentException("permission snapshot budget exceeded");
        for (String id : conditionIds) if (!this.conditions.containsKey(id)) throw new IllegalArgumentException("unknown required condition " + id);
        this.permissionNodes = Set.copyOf(nodes); this.conditionIds = Set.copyOf(conditionIds);
    }
    private static void collect(String permission, Set<String> required, Set<String> nodes, Set<String> checks) {
        if (!permission.isEmpty()) nodes.add(permission); checks.addAll(required);
    }
    private static <T> Map<String,T> index(List<T> rows, Function<T,String> key, int maximum, String label) {
        if (rows.size() > maximum) throw new IllegalArgumentException(label + ": too many entries");
        Map<String,T> result = new HashMap<>();
        for (T row : rows) if (result.putIfAbsent(key.apply(row), row) != null) throw new IllegalArgumentException(label + ": duplicate id " + key.apply(row));
        return Map.copyOf(result);
    }
    public String profileFor(Optional<UpgradePath> path, String requested) {
        if (!requested.isEmpty()) return requested;
        PathProfileRule rule = path.map(value -> pathRules.get(value.id())).orElse(null);
        return rule == null ? settings.defaultProfile() : rule.defaultProfile();
    }
    public boolean profileAllowed(Optional<UpgradePath> path, String profile) {
        PathProfileRule rule = path.map(value -> pathRules.get(value.id())).orElse(null);
        return rule == null || rule.allowedProfiles().contains(profile);
    }
    public List<PermissionBonus> permissionBonuses(CatalogAccess access) {
        Map<String, PermissionBonus> selected = new TreeMap<>();
        for (PermissionBonus bonus : bonuses) if (bonus.enabled() && access.allows(bonus.permission(), bonus.requiredConditions())) {
            PermissionBonus current = selected.get(bonus.group());
            if (current == null || bonus.priority() > current.priority()) selected.put(bonus.group(), bonus);
        }
        return List.copyOf(selected.values());
    }
    /** Configured resources for a request. Core still checks all access/selection gates before issuing a quote. */
    public Set<CostResource> resourcesFor(Optional<UpgradePath> path, QuoteRequest request) {
        Set<CostResource> resources = new HashSet<>();
        RiskProfile profile = profiles.get(profileFor(path, request.profileId()));
        if (profile != null) profile.costs().forEach(cost -> resources.add(cost.resource()));
        for (String id : request.boostIds()) {
            BoostDefinition boost = boosts.get(id);
            if (boost != null) boost.costs().forEach(cost -> resources.add(cost.resource()));
        }
        return Set.copyOf(resources);
    }
    public Optional<vn.ledat.itemupgrader.output.OutputSpec> outputSpec(Optional<UpgradePath> path, String profile, RiskProfile.FailureMode failure) {
        return outputs.map(value -> value.spec(path, profile, failure));
    }
    public CatalogDefinitions catalog() { return catalog; }
    public QuoteSettings settings() { return settings; }
    public Map<String, ChanceFormula> formulas() { return formulas; }
    public Map<String, RiskProfile> profiles() { return profiles; }
    public Map<String, BoostDefinition> boosts() { return boosts; }
    public List<PermissionBonus> allPermissionBonuses() { return bonuses; }
    public Map<String, ConditionDefinition> conditions() { return conditions; }
    public List<ConditionDefinition> requiredConditionDefinitions() { return conditionIds.stream().sorted().map(conditions::get).toList(); }
    public Set<String> permissionNodes() { return permissionNodes; }
    public static UpgradeRules defaults(CatalogDefinitions catalog) {
        return new UpgradeRules(catalog, QuoteSettings.defaults(), Map.of("ratio", new ChanceFormula.Ratio(new BigDecimal("0.9"))),
                List.of(new RiskProfile("standard", true, "ratio", BigDecimal.ONE, BigDecimal.ONE, RiskProfile.FailureMode.DESTROY, List.of(), "", Set.of())),
                List.of(), List.of(), List.of(), List.of());
    }
}
