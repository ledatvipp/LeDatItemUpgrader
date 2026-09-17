package vn.ledat.itemupgrader.paper.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import vn.ledat.itemupgrader.boost.BoostDefinition;
import vn.ledat.itemupgrader.boost.PermissionBonus;
import vn.ledat.itemupgrader.catalog.CatalogDefinitions;
import vn.ledat.itemupgrader.chance.ChanceFormula;
import vn.ledat.itemupgrader.condition.ConditionDefinition;
import vn.ledat.itemupgrader.cost.CostEntry;
import vn.ledat.itemupgrader.cost.CostResource;
import vn.ledat.itemupgrader.item.ItemKey;
import vn.ledat.itemupgrader.profile.RiskProfile;
import vn.ledat.itemupgrader.quote.PathProfileRule;
import vn.ledat.itemupgrader.quote.QuoteSettings;
import vn.ledat.itemupgrader.quote.UpgradeRules;

/** All rule parsing is detached and transactional: a bad candidate never replaces the live runtime. */
final class UpgradeConfigLoader {
    private final RegistrySnapshot registry;
    UpgradeConfigLoader(RegistrySnapshot registry) { this.registry = registry; }
    UpgradeRules load(CatalogDefinitions catalog, YamlNode chance, YamlNode profiles, YamlNode boosts,
                      YamlNode conditions, Set<ItemKey> identities, vn.ledat.itemupgrader.output.OutputRules outputs) {
        chance.allow("config-version", "default-profile", "minimum-percent", "maximum-percent", "maximum-selected-boosts", "quote-lifetime-seconds", "allow-free-protection", "formulas", "permission-bonuses");
        profiles.allow("config-version", "profiles", "path-profile-rules");
        boosts.allow("config-version", "boosts"); conditions.allow("config-version", "conditions");
        for (YamlNode node : List.of(chance, profiles, boosts, conditions)) node.integer("config-version", 1, 1);
        var settings = new QuoteSettings(chance.string("default-profile"), chance.decimal("minimum-percent"), chance.decimal("maximum-percent"),
                chance.integer("maximum-selected-boosts", 0, 8), Duration.ofSeconds(chance.integer("quote-lifetime-seconds", 1, 300)), chance.bool("allow-free-protection"));
        var formulas = new HashMap<String, ChanceFormula>();
        for (YamlNode node : chance.sections("formulas")) {
            ChanceFormula formula;
            switch (node.string("type")) {
                case "RATIO" -> { node.allow("id", "type", "multiplier"); formula = new ChanceFormula.Ratio(node.decimal("multiplier")); }
                case "POWER" -> { node.allow("id", "type", "multiplier", "exponent"); formula = new ChanceFormula.Power(node.decimal("multiplier"), node.integer("exponent", 1, 8)); }
                case "TABLE", "CURVE" -> {
                    node.allow("id", "type", "points"); var points = new ArrayList<ChanceFormula.Point>();
                    for (YamlNode point : node.sections("points")) {
                        point.allow("ratio", "percent"); points.add(new ChanceFormula.Point(point.decimal("ratio"), point.decimal("percent")));
                    }
                    formula = node.string("type").equals("TABLE") ? new ChanceFormula.Table(points) : new ChanceFormula.Curve(points);
                }
                default -> throw new IllegalArgumentException(node.at("type") + ": only RATIO, POWER, TABLE, CURVE supported");
            }
            if (formulas.putIfAbsent(node.string("id"), formula) != null) throw new IllegalArgumentException(node.at("id") + ": duplicate formula");
        }
        var permissionBonuses = new ArrayList<PermissionBonus>();
        for (YamlNode node : chance.sections("permission-bonuses")) {
            node.allow("id", "enabled", "group", "priority", "permission", "conditions", "chance-multiplier", "bonus-percentage-points");
            permissionBonuses.add(new PermissionBonus(node.string("id"), node.bool("enabled"), node.string("group"), node.integer("priority", -100000, 100000),
                    node.string("permission"), ids(node, "conditions"), node.decimal("chance-multiplier"), node.decimal("bonus-percentage-points")));
        }
        var riskProfiles = new ArrayList<RiskProfile>();
        for (YamlNode node : profiles.sections("profiles")) {
            node.allow("id", "enabled", "formula", "chance-multiplier", "fee-multiplier", "failure", "costs", "permission", "conditions");
            riskProfiles.add(new RiskProfile(node.string("id"), node.bool("enabled"), node.string("formula"), node.decimal("chance-multiplier"),
                    node.decimal("fee-multiplier"), enumeration(node, "failure", RiskProfile.FailureMode.class), costs(node, identities), node.string("permission"), ids(node, "conditions")));
        }
        var pathRules = new ArrayList<PathProfileRule>();
        for (YamlNode node : profiles.sections("path-profile-rules")) {
            node.allow("path", "allowed-profiles", "default-profile");
            pathRules.add(new PathProfileRule(node.string("path"), ids(node, "allowed-profiles"), node.string("default-profile")));
        }
        var boostRules = new ArrayList<BoostDefinition>();
        for (YamlNode node : boosts.sections("boosts")) {
            node.allow("id", "enabled", "exclusive-group", "allowed-profiles", "chance-multiplier", "bonus-percentage-points", "protection", "costs", "permission", "conditions");
            boostRules.add(new BoostDefinition(node.string("id"), node.bool("enabled"), node.string("exclusive-group"), ids(node, "allowed-profiles"),
                    node.decimal("chance-multiplier"), node.decimal("bonus-percentage-points"), enumeration(node, "protection", BoostDefinition.Protection.class),
                    costs(node, identities), node.string("permission"), ids(node, "conditions")));
        }
        var conditionRules = new ArrayList<ConditionDefinition>();
        for (YamlNode node : conditions.sections("conditions")) {
            node.allow("id", "placeholder", "type", "operator", "value");
            conditionRules.add(new ConditionDefinition(node.string("id"), node.string("placeholder"), enumeration(node, "type", ConditionDefinition.Type.class),
                    enumeration(node, "operator", ConditionDefinition.Operator.class), node.string("value")));
        }
        return new UpgradeRules(catalog, settings, formulas, riskProfiles, boostRules, permissionBonuses, conditionRules, pathRules, java.util.Optional.of(outputs));
    }
    private List<CostEntry> costs(YamlNode parent, Set<ItemKey> identities) {
        var result = new ArrayList<CostEntry>();
        for (YamlNode node : parent.sections("costs")) {
            CostResource resource;
            if (node.string("type").equals("ITEM")) {
                node.allow("type", "item", "amount", "consume");
                ItemKey key = ItemKey.of(node.string("item")); registry.validate(key, node.at("item")); identities.add(key);
                resource = CostResource.item(key);
            } else if (node.string("type").equals("CURRENCY")) {
                node.allow("type", "currency", "amount", "consume"); resource = CostResource.currency(enumeration(node, "currency", CostResource.Currency.class));
            } else throw new IllegalArgumentException(node.at("type") + ": only ITEM or CURRENCY");
            result.add(new CostEntry(resource, node.decimal("amount"), enumeration(node, "consume", CostEntry.ConsumeWhen.class)));
        }
        return List.copyOf(result);
    }
    private static Set<String> ids(YamlNode node, String field) {
        var list = node.strings(field); var set = Set.copyOf(list);
        if (list.size() != set.size()) throw new IllegalArgumentException(node.at(field) + ": duplicate entry");
        return set;
    }
    private static <E extends Enum<E>> E enumeration(YamlNode node, String key, Class<E> type) {
        try { return Enum.valueOf(type, node.string(key)); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException(node.at(key) + ": invalid " + type.getSimpleName(), error); }
    }
}
