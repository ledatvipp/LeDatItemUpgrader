package vn.ledat.itemupgrader.paper.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import vn.ledat.itemupgrader.catalog.CatalogDefinitions;
import vn.ledat.itemupgrader.catalog.CatalogSettings;
import vn.ledat.itemupgrader.catalog.TargetDefinition;
import vn.ledat.itemupgrader.catalog.UpgradePath;
import vn.ledat.itemupgrader.item.ItemKey;

/** Only detached registry strings and YAML data enter this loader; safe for Platform config workers. */
final class CatalogConfigLoader {
    private final RegistrySnapshot registry;
    CatalogConfigLoader(RegistrySnapshot registry) { this.registry = registry; }
    CatalogDefinitions load(YamlNode catalog, YamlNode paths, Set<ItemKey> identities) {
        catalog.allow("config-version", "settings", "targets"); catalog.integer("config-version", 1, 1);
        paths.allow("config-version", "paths"); paths.integer("config-version", 1, 1);
        YamlNode settings = catalog.section("settings");
        settings.allow("minimum-ratio", "maximum-ratio", "preferred-ratio", "page-size", "recommendation-count", "maximum-targets", "maximum-paths", "allow-unpathed");
        CatalogSettings options = new CatalogSettings(settings.decimal("minimum-ratio"), settings.decimal("maximum-ratio"),
                settings.decimal("preferred-ratio"), settings.integer("page-size", 1, 45), settings.integer("recommendation-count", 1, 10),
                settings.integer("maximum-targets", 1, 10000), settings.integer("maximum-paths", 1, 2000), settings.bool("allow-unpathed"));
        List<TargetDefinition> targets = new ArrayList<>();
        for (YamlNode node : catalog.sections("targets")) {
            node.allow("id", "item", "amount", "name", "category", "tags", "enabled", "priority", "permission", "conditions", "allowed-sources");
            try {
                ItemKey item = key(node.string("item"), node.at("item"));
                Set<ItemKey> allowed = keys(node, "allowed-sources"); identities.add(item); identities.addAll(allowed);
                ConfigLoader.validateText(node.string("name"), node.at("name"));
                targets.add(new TargetDefinition(node.string("id"), item, node.integer("amount", 1, 64), node.string("name"),
                        node.string("category"), strings(node, "tags"), node.bool("enabled"), node.integer("priority", -100000, 100000),
                        node.string("permission"), strings(node, "conditions"), allowed));
            } catch (IllegalArgumentException error) { throw new IllegalArgumentException(node.at("id") + ": " + error.getMessage(), error); }
        }
        List<UpgradePath> definitions = new ArrayList<>();
        for (YamlNode node : paths.sections("paths")) {
            node.allow("id", "sources", "priority", "mode", "targets", "enabled", "permission", "conditions");
            try {
                Set<ItemKey> sources = keys(node, "sources"); identities.addAll(sources);
                definitions.add(new UpgradePath(node.string("id"), sources, node.integer("priority", -100000, 100000),
                        UpgradePath.Mode.valueOf(node.string("mode")), node.strings("targets"), node.bool("enabled"),
                        node.string("permission"), strings(node, "conditions")));
            } catch (IllegalArgumentException error) { throw new IllegalArgumentException(node.at("id") + ": " + error.getMessage(), error); }
        }
        return new CatalogDefinitions(options, targets, definitions);
    }
    private ItemKey key(String raw, String path) {
        ItemKey key = ItemKey.of(raw); registry.validate(key, path); return key;
    }
    private Set<ItemKey> keys(YamlNode node, String field) {
        Set<ItemKey> result = new HashSet<>();
        for (String raw : node.strings(field)) if (!result.add(key(raw, node.at(field))))
            throw new IllegalArgumentException(node.at(field) + ": duplicate key " + raw);
        return Set.copyOf(result);
    }
    private static Set<String> strings(YamlNode node, String field) {
        List<String> values = node.strings(field); Set<String> result = Set.copyOf(values);
        if (result.size() != values.size()) throw new IllegalArgumentException(node.at(field) + ": duplicate value");
        return result;
    }
}
