package vn.ledat.itemupgrader.paper.config;

import java.time.Duration;
import java.util.*;
import vn.ledat.itemupgrader.animation.*;
import vn.ledat.itemupgrader.gui.GuiSettings;
import vn.ledat.itemupgrader.gui.MenuDefinition;

/** Async parser using captured registry strings only. Invalid candidate keeps the previous whole runtime. */
final class AnimationConfigLoader {
    private AnimationConfigLoader() {}
    static AnimationConfiguration load(YamlNode root, RegistrySnapshot registry) {
        root.allow("config-version", "preview-enabled", "maximum-sessions", "visits-per-tick", "open-cooldown-ms",
                "callback-timeout-ms", "pulse-interval-ms", "default-preset", "presets", "title-animation", "menu", "sounds");
        root.integer("config-version", 1, 1);
        Map<String, AnimationPreset> presets = new LinkedHashMap<>();
        root.section("presets").entries().forEach((id, raw) -> {
            var node = YamlNode.from(raw, root.at("presets") + "." + id);
            node.allow("kind", "intro-ms", "acceleration-ms", "cruise-ms", "deceleration-ms", "landing-ms", "reveal-hold-ms", "turns");
            presets.put(id, new AnimationPreset(id, AnimationPreset.Kind.valueOf(node.string("kind")),
                    ms(node, "intro-ms", 0, 10000), ms(node, "acceleration-ms", 0, 10000), ms(node, "cruise-ms", 0, 10000),
                    ms(node, "deceleration-ms", 0, 10000), ms(node, "landing-ms", 0, 10000),
                    ms(node, "reveal-hold-ms", 250, 5000), node.integer("turns", 0, 20)));
        });
        var menu = root.section("menu"); menu.allow("title", "matrix", "symbols", "track-order", "icons");
        List<String> titleFrames = List.of(menu.string("title"));
        int titleIntervalTicks = 1;
        if (root.has("title-animation")) {
            var animation = root.section("title-animation");
            animation.allow("titles", "interval-ticks");
            titleFrames = animation.strings("titles");
            titleIntervalTicks = animation.integer("interval-ticks", 1, 20);
            if (titleFrames.isEmpty() || titleFrames.size() > 200)
                throw new IllegalArgumentException(animation.at("titles") + ": expected 1..200 title frames");
            for (String frame : titleFrames) text(frame, animation.at("titles"));
        }
        Map<Character, AnimationMenu.Role> symbols = new HashMap<>();
        menu.section("symbols").entries().forEach((symbol, raw) -> {
            if (symbol.length() != 1 || !(raw instanceof String role))
                throw new IllegalArgumentException(menu.at("symbols") + ": one character and string role required");
            symbols.put(symbol.charAt(0), AnimationMenu.Role.valueOf(role));
        });
        Map<AnimationMenu.Palette, MenuDefinition.Element> icons = new EnumMap<>(AnimationMenu.Palette.class);
        menu.section("icons").entries().forEach((id, raw) -> {
            var n = YamlNode.from(raw, menu.at("icons") + "." + id);
            n.allow("material", "name", "lore", "item-model", "custom-model-data", "glow");
            String material = n.string("material");
            if (!registry.materials().contains(material)) throw new IllegalArgumentException(n.at("material") + ": unknown item material");
            var element = new MenuDefinition.Element(MenuDefinition.Role.FILLER, material, n.string("name"), n.strings("lore"),
                    n.string("item-model", ""), n.has("custom-model-data") ? n.integer("custom-model-data", 0, Integer.MAX_VALUE) : null,
                    n.bool("glow"), MenuDefinition.Action.NONE, "");
            text(element.name(), n.at("name")); element.lore().forEach(line -> text(line, n.at("lore")));
            icons.put(AnimationMenu.Palette.valueOf(id), element);
        });
        text(menu.string("title"), menu.at("title"));
        var layout = new AnimationMenu(menu.string("title"), titleFrames, titleIntervalTicks,
                menu.strings("matrix"), symbols, menu.integers("track-order", 0, 53), icons);
        Map<AnimationSessionStore.Cue, GuiSettings.Cue> sounds = new EnumMap<>(AnimationSessionStore.Cue.class);
        root.section("sounds").entries().forEach((id, raw) -> {
            var n = YamlNode.from(raw, root.at("sounds") + "." + id); n.allow("key", "volume", "pitch");
            String key = n.string("key");
            if (!key.isEmpty() && !registry.sounds().contains(key)) throw new IllegalArgumentException(n.at("key") + ": unknown sound");
            sounds.put(AnimationSessionStore.Cue.valueOf(id), new GuiSettings.Cue(key, n.decimal("volume").floatValue(), n.decimal("pitch").floatValue()));
        });
        try {
            return new AnimationConfiguration(root.bool("preview-enabled"), root.integer("maximum-sessions", 1, 128),
                    root.integer("visits-per-tick", 1, 32), ms(root, "open-cooldown-ms", 500, 30000),
                    ms(root, "callback-timeout-ms", 1000, 10000), ms(root, "pulse-interval-ms", 100, 1000),
                    root.string("default-preset"), presets, layout, sounds);
        } catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("menus/animation.yml: " + invalid.getMessage(), invalid); }
    }
    private static Duration ms(YamlNode n, String key, int min, int max) { return Duration.ofMillis(n.integer(key, min, max)); }
    private static void text(String text, String path) {
        ConfigLoader.validateText(text, path);
        var pattern = java.util.regex.Pattern.compile("\\{([^{}]+)}").matcher(text);
        while (pattern.find()) if (!Set.of("stage", "outcome", "mode", "chance", "preset", "prefix").contains(pattern.group(1)))
            throw new IllegalArgumentException(path + ": unsupported animation placeholder " + pattern.group());
    }
}
