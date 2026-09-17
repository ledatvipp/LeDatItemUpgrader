package vn.ledat.itemupgrader.paper.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import vn.ledat.itemupgrader.gui.*;
import vn.ledat.itemupgrader.item.ItemKey;
import vn.ledat.itemupgrader.recipe.*;
import vn.ledat.itemupgrader.runtime.UpgraderRuntime;
import vn.ledat.itemupgrader.value.*;
import vn.ledat.itemupgrader.paper.message.DefaultMessages;

/** Reads local files only on the Platform worker. Every loaded model is immutable before publication. */
public final class ConfigLoader {
    private final RegistrySnapshot registry;
    private final java.util.function.Consumer<String> warning;
    private final Set<String> missingMessageWarnings = new HashSet<>(); // Reload is single-flight.
    public ConfigLoader(RegistrySnapshot registry, java.util.function.Consumer<String> warning) {
        this.registry = registry; this.warning = warning;
    }
    public UpgraderRuntime load(Path folder) throws IOException {
        YamlNode config = loadYaml(folder.resolve("config.yml"));
        config.allow("config-version", "features", "performance", "storage", "limits"); version(config, "config-version");
        YamlNode features = config.section("features"); features.allow("upgrades-enabled", "packet-renderer-enabled");
        if (features.bool("upgrades-enabled") || features.bool("packet-renderer-enabled"))
            throw new IllegalArgumentException("config.yml.features: native escrow/ledger/delivery adapters are not released in Phase 0-9A; keep false");
        YamlNode performance = config.section("performance"); performance.allow("identity-probe-batch", "command-cooldown-ms");
        YamlNode storage = config.section("storage"); storage.allow("initialize-schema", "mode");
        if (!storage.string("mode").equals("PLATFORM_SHARED"))
            throw new IllegalArgumentException("storage.mode: Phase 0 uses documented shared Platform SQL only; isolated sqliteStore signature is not supplied");
        YamlNode l = config.section("limits");
        l.allow("scale", "max-amount", "max-snapshot-bytes", "max-enchant-level", "max-unit-value", "max-total-value", "recipe-max-depth", "recipe-max-nodes");
        ValueLimits limits = new ValueLimits(l.integer("scale", 0, 8), l.integer("max-amount", 1, 4096),
                l.integer("max-snapshot-bytes", 256, 1048576), l.integer("max-enchant-level", 1, 255),
                l.decimal("max-unit-value"), l.decimal("max-total-value"),
                l.integer("recipe-max-depth", 1, 64), l.integer("recipe-max-nodes", 1, 100000));
        Set<ItemKey> identities = new TreeSet<>();
        RecipeIndex recipes = recipes(loadYaml(folder.resolve("upgrades/recipes.yml")), identities);
        ValueDefinitions values = values(loadYaml(folder.resolve("upgrades/values.yml")), limits, recipes, identities);
        var catalog = new CatalogConfigLoader(registry).load(loadYaml(folder.resolve("upgrades/catalog.yml")),
                loadYaml(folder.resolve("upgrades/paths.yml")), identities);
        var outputs = new OutputConfigLoader(registry).load(loadYaml(folder.resolve("upgrades/outputs.yml")), identities);
        var upgradeRules = new UpgradeConfigLoader(registry).load(catalog,
                loadYaml(folder.resolve("upgrades/chance.yml")), loadYaml(folder.resolve("upgrades/profiles.yml")),
                loadYaml(folder.resolve("upgrades/boosts.yml")), loadYaml(folder.resolve("upgrades/conditions.yml")), identities, outputs);
        Map<String, String> messages = messages(loadYaml(folder.resolve("messages.yml")));
        MenuCompiler.CompiledMenu menu = menu(loadYaml(folder.resolve("menus/upgrader.yml")), true);
        GuiMenus gui = new GuiMenus(GuiConfigLoader.settings(loadYaml(folder.resolve("menus/settings.yml")), registry), Map.of(
                GuiContext.Screen.MAIN, menu,
                GuiContext.Screen.CATALOG, menu(loadYaml(folder.resolve("menus/catalog.yml")), false),
                GuiContext.Screen.PROFILES, menu(loadYaml(folder.resolve("menus/profiles.yml")), false),
                GuiContext.Screen.BOOSTS, menu(loadYaml(folder.resolve("menus/boosts.yml")), false)));
        gui.validateReferences(upgradeRules);
        var animation = AnimationConfigLoader.load(loadYaml(folder.resolve("menus/animation.yml")), registry);
        var history = HistoryConfigLoader.load(loadYaml(folder.resolve("history.yml")),loadYaml(folder.resolve("upgrades/pity.yml")),
                menu(loadYaml(folder.resolve("menus/history.yml")),false),upgradeRules,registry);
        var storageManagement = StorageManagementConfigLoader.load(loadYaml(folder.resolve("storage-management.yml")), history);
        identities.removeIf(ItemKey::vanilla);
        return new UpgraderRuntime(values, catalog, upgradeRules, messages, menu, List.copyOf(identities),
                performance.integer("identity-probe-batch", 1, 32), performance.integer("command-cooldown-ms", 100, 60000),
                storage.bool("initialize-schema"), java.util.Optional.of(gui), java.util.Optional.of(animation), java.util.Optional.of(history), storageManagement);
    }
    private ValueDefinitions values(YamlNode root, ValueLimits limits, RecipeIndex recipes, Set<ItemKey> identities) {
        root.allow("config-version", "manual", "rules", "provider-defaults", "rarity-defaults", "blocked-items", "modifiers"); version(root, "config-version");
        Map<ItemKey, BigDecimal> manual = new HashMap<>();
        for (YamlNode node : root.sections("manual")) {
            node.allow("item", "value"); ItemKey key = key(node.string("item"), node.at("item"));
            if (manual.putIfAbsent(key, node.decimal("value")) != null) throw new IllegalArgumentException(node.at("item") + ": duplicate item");
            identities.add(key);
        }
        List<ValueRule> rules = new ArrayList<>();
        for (YamlNode node : root.sections("rules")) {
            node.allow("id", "priority", "provider", "item", "rarity", "value");
            ItemKey exact = node.has("item") ? key(node.string("item"), node.at("item")) : null;
            if (exact != null) identities.add(exact);
            rules.add(new ValueRule(node.string("id"), node.integer("priority", -100000, 100000),
                    node.string("provider", ""), exact, node.string("rarity", ""), node.decimal("value")));
        }
        Map<String, BigDecimal> providers = decimalMap(root.sections("provider-defaults"), "provider", "value");
        Map<String, BigDecimal> rarities = decimalMap(root.sections("rarity-defaults"), "rarity", "value");
        Set<ItemKey> blocked = new HashSet<>();
        for (String raw : root.strings("blocked-items")) {
            if (!blocked.add(key(raw, root.at("blocked-items")))) throw new IllegalArgumentException("duplicate blocked item");
        }
        YamlNode mods = root.section("modifiers"); mods.allow("enchantments", "rarity", "durability");
        Map<String, BigDecimal> enchant = decimalMap(mods.sections("enchantments"), "key", "per-level");
        for (String id : enchant.keySet()) if (!registry.enchantments().contains(id)) throw new IllegalArgumentException("modifiers.enchantments: unknown registry key " + id);
        Map<String, BigDecimal> rarity = decimalMap(mods.sections("rarity"), "rarity", "multiplier");
        YamlNode durability = mods.section("durability"); durability.allow("enabled", "minimum-factor");
        ModifierSettings modifiers = new ModifierSettings(enchant, rarity, durability.bool("enabled"), durability.decimal("minimum-factor"));
        return new ValueDefinitions(manual, rules, providers, rarities, blocked, modifiers, recipes, limits);
    }
    private RecipeIndex recipes(YamlNode root, Set<ItemKey> identities) {
        root.allow("config-version", "recipes"); version(root, "config-version");
        List<RecipeDefinition> recipes = new ArrayList<>();
        for (YamlNode node : root.sections("recipes")) {
            node.allow("id", "result", "output-amount", "ingredients");
            ItemKey result = key(node.string("result"), node.at("result")); identities.add(result);
            List<RecipeDefinition.Ingredient> ingredients = new ArrayList<>();
            for (YamlNode ingredient : node.sections("ingredients")) {
                ingredient.allow("alternatives", "amount");
                List<ItemKey> alternatives = ingredient.strings("alternatives").stream()
                        .map(raw -> key(raw, ingredient.at("alternatives"))).toList();
                identities.addAll(alternatives);
                ingredients.add(new RecipeDefinition.Ingredient(alternatives, ingredient.integer("amount", 1, 64)));
            }
            recipes.add(new RecipeDefinition(node.string("id"), result, node.integer("output-amount", 1, 64), ingredients));
        }
        return new RecipeIndex(recipes);
    }
    private Map<String, BigDecimal> decimalMap(List<YamlNode> nodes, String key, String value) {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        for (YamlNode node : nodes) {
            node.allow(key, value);
            if (values.putIfAbsent(node.string(key), node.decimal(value)) != null) throw new IllegalArgumentException(node.at(key) + ": duplicate key");
        }
        return Map.copyOf(values);
    }
    private Map<String, String> messages(YamlNode root) {
        version(root, "messages-version");
        Map<String, String> result = new HashMap<>(DefaultMessages.ALL);
        for (String key : DefaultMessages.ALL.keySet()) {
            if (!root.has(key) && missingMessageWarnings.add(key))
                warning.accept("messages.yml." + key + ": missing key; using bundled fallback");
        }
        root.entries().forEach((key, value) -> {
            if (key.equals("messages-version")) return;
            if (!(value instanceof String text)) throw new IllegalArgumentException(root.at(key) + ": expected MiniMessage string");
            if (!DefaultMessages.ALL.containsKey(key)) throw new IllegalArgumentException(root.at(key) + ": unknown message key");
            if (text.length() > 8192) throw new IllegalArgumentException(root.at(key) + ": message too long");
            result.put(key, text);
        });
        if (result.get("prefix").contains("{prefix}")) throw new IllegalArgumentException("messages.prefix: recursive prefix");
        result.forEach((key, text) -> validateText(text, "messages." + key));
        return Map.copyOf(result);
    }
    private MenuCompiler.CompiledMenu menu(YamlNode root, boolean requireSource) {
        root.allow("config-version", "id", "title", "matrix", "symbols"); version(root, "config-version");
        Map<Character, MenuDefinition.Element> symbols = new LinkedHashMap<>();
        for (var entry : root.section("symbols").entries().entrySet()) {
            if (entry.getKey().length() != 1) throw new IllegalArgumentException("menu symbol must be one ASCII character");
            YamlNode n = YamlNode.from(entry.getValue(), "menus.upgrader.symbols." + entry.getKey());
            n.allow("role", "material", "name", "lore", "item-model", "custom-model-data", "glow", "action", "argument");
            String material = n.string("material");
            if (!registry.materials().contains(material)) throw new IllegalArgumentException(n.at("material") + ": unknown item material");
            MenuDefinition.Element element = new MenuDefinition.Element(MenuDefinition.Role.valueOf(n.string("role")), material,
                    n.string("name"), n.strings("lore"), n.string("item-model", ""),
                    n.has("custom-model-data") ? n.integer("custom-model-data", 0, Integer.MAX_VALUE) : null,
                    n.bool("glow"), MenuDefinition.Action.valueOf(n.string("action")), n.string("argument", ""));
            validateText(element.name(), n.at("name"));
            for (String line : element.lore()) validateText(line, n.at("lore"));
            symbols.put(entry.getKey().charAt(0), element);
        }
        validateText(root.string("title"), root.at("title"));
        return new MenuCompiler().compile(new MenuDefinition(root.string("id"), root.string("title"), root.strings("matrix"), symbols), requireSource);
    }
    static void validateText(String text, String path) {
        try { net.kyori.adventure.text.minimessage.MiniMessage.builder().strict(true).build().deserialize(text); }
        catch (RuntimeException error) { throw new IllegalArgumentException(path + ": invalid strict MiniMessage", error); }
    }
    private ItemKey key(String raw, String path) {
        ItemKey key;
        try { key = ItemKey.of(raw); } catch (IllegalArgumentException error) { throw new IllegalArgumentException(path + ": " + error.getMessage(), error); }
        registry.validate(key, path); return key;
    }
    private static void version(YamlNode node, String key) { node.integer(key, 1, 1); }
    private static YamlNode loadYaml(Path path) throws IOException {
        if (Files.size(path) > 1_048_576) throw new IOException(path.getFileName() + ": file larger than 1 MiB");
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setAllowRecursiveKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(32);
        options.setCodePointLimit(262144);
        byte[] raw;
        try (var input = Files.newInputStream(path)) { raw = input.readNBytes(1_048_577); }
        if (raw.length > 1_048_576) throw new IOException(path.getFileName() + ": file grew beyond limit during read");
        String text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(raw)).toString();
        return YamlNode.from(new Yaml(new SafeConstructor(options)).load(text), path.getFileName().toString());
    }
}
