package vn.ledat.itemupgrader.paper.gui;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import vn.ledat.itemupgrader.gui.GuiPreviewService;
import vn.ledat.itemupgrader.gui.GuiSessionStore;
import vn.ledat.itemupgrader.gui.UpgraderTitleLayout;

/** Owner-thread renderer for trusted CraftEngine/Nexo/VietHUD title templates in menus/upgrader.yml. */
final class UpgraderTitleRenderer {
    // Same neutral fallback contract used by VietHUD inventory titles: preserve pack markup as
    // component text when no optional glyph engine is installed. Title packets remain enabled.
    private static final TagResolver PACK_TAGS = TagResolver.builder()
            .tag("shift", (arguments, context) -> Tag.inserting(Component.text(
                    "<shift:" + arguments.popOr("shift requires pixels").value() + ">")))
            .tag("image", (arguments, context) -> {
                StringBuilder key = new StringBuilder(arguments.popOr("image requires key").value());
                while (arguments.hasNext()) key.append(':').append(arguments.pop().value());
                return Tag.inserting(Component.text("<image:" + key + ">"));
            }).build();
    record Data(String signature, String sourceValue, String targetValue, String amount, int amountState,
                String chance, int targetBar) {}

    private final JavaPlugin owner;
    private final MiniMessage miniMessage;
    private final MiniMessage fallbackMiniMessage;
    private Plugin craftEnginePlugin;
    private Method craftEngineMiniMessage;
    private Method craftEngineDeserialize;
    private boolean craftEngineUnavailable, craftEngineWarning, papiWarning, renderWarning;

    UpgraderTitleRenderer(JavaPlugin owner) {
        this.owner = owner;
        TagResolver.Builder tags = TagResolver.builder().resolver(StandardTags.defaults());
        var nexo = resolveNexo(); tags.resolver(nexo.orElse(PACK_TAGS));
        this.miniMessage = MiniMessage.builder().tags(tags.build()).build();
        this.fallbackMiniMessage = MiniMessage.builder().tags(TagResolver.builder()
                .resolver(StandardTags.defaults()).resolver(PACK_TAGS).build()).build();
    }

    Optional<Data> data(Player player, GuiSessionStore.State state, Optional<GuiPreviewService.Preview> preview,
                        UpgraderTitleLayout layout) {
        if (state.context().screen() != vn.ledat.itemupgrader.gui.GuiContext.Screen.MAIN || preview.isEmpty()) return Optional.empty();
        var p = preview.orElseThrow();
        var source = p.source();
        var quote = p.quote().flatMap(q -> q.quote());
        // A completed quote is the authoritative bundle for both displayed values and probability.
        // Do not make title visibility depend on the separate catalog/value presentation optionals:
        // those may be refreshed independently even though the bound quote is already usable.
        if (source.isEmpty() || quote.isEmpty()) return Optional.empty();
        int available = 0;
        if (state.context().sourceSlot() >= 0) {
            var stack = player.getInventory().getItem(state.context().sourceSlot());
            if (stack != null && !stack.getType().isAir()) available = stack.getAmount();
        }
        int selected = source.orElseThrow().facts().amount();
        int amountState = UpgraderTitleLayout.amountState(selected, available);
        var bound = quote.orElseThrow();
        var probability = bound.chance().probability();
        String sourceText = UpgraderTitleLayout.compactValue(bound.sourceTotal());
        String targetText = UpgraderTitleLayout.compactValue(bound.targetTotal());
        String chance = UpgraderTitleLayout.percentText(probability);
        String signature = source.orElseThrow().fingerprint() + '|' + bound.request().targetId() + '|'
                + probability.winningTickets() + '|' + selected + '|' + available;
        return Optional.of(new Data(signature, sourceText, targetText, Integer.toString(selected), amountState,
                chance, layout.barIndex(probability)));
    }

    Component empty(Player player, UpgraderTitleLayout layout) { return parse(player, layout.emptyTitle(), Map.of()); }

    Component selected(Player player, UpgraderTitleLayout layout, Data data) {
        return parse(player, layout.selectedTitle(), replacements(data, 0, layout.arrowStartShift()));
    }

    Component preview(Player player, UpgraderTitleLayout layout, Data data) {
        return parse(player, layout.barTitle(), replacements(data, data.targetBar(), layout.arrowStartShift()));
    }

    Component animated(Player player, UpgraderTitleLayout layout, Data data, UpgraderTitleLayout.Frame frame) {
        String template = frame.arrowShift().isPresent() ? layout.readyTitle() : layout.barTitle();
        return parse(player, template, replacements(data, frame.barIndex(), frame.arrowShift().orElse(layout.arrowStartShift())));
    }

    private static Map<String, String> replacements(Data data, int bar, int arrowShift) {
        return Map.of("source_value", data.sourceValue(), "target_value", data.targetValue(),
                "amount_state", Integer.toString(data.amountState()), "amount", data.amount(),
                "chance", data.chance(), "bar", Integer.toString(bar), "arrow_shift", Integer.toString(arrowShift));
    }

    private Component parse(Player player, String template, Map<String, String> replacements) {
        String expanded = template;
        for (var entry : replacements.entrySet()) expanded = expanded.replace("{" + entry.getKey() + "}", entry.getValue());
        String boundedFallback = expanded;
        if (owner.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try { expanded = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, expanded); }
            catch (RuntimeException | LinkageError error) {
                if (!papiWarning) {
                    papiWarning = true;
                    owner.getLogger().log(Level.WARNING, "VietHUD title placeholders failed; keeping the bounded raw title", error);
                }
            }
        }
        if (expanded == null || expanded.length() > 32768) expanded = boundedFallback;
        var craftEngine = parseCraftEngine(expanded);
        if (craftEngine.isPresent()) return craftEngine.orElseThrow();
        try { return miniMessage.deserialize(expanded); }
        catch (RuntimeException | LinkageError primaryFailure) {
            if (!renderWarning) {
                renderWarning = true;
                owner.getLogger().log(Level.WARNING,
                        "Resource-pack title parsing failed; using a safe literal fallback without closing the GUI", primaryFailure);
            }
            try { return fallbackMiniMessage.deserialize(expanded); }
            catch (RuntimeException | LinkageError fallbackFailure) { return Component.text(expanded); }
        }
    }

    /**
     * CraftEngine owns the configured {@code <shift>} and {@code <image>} tags. Its current API uses
     * Sparrow MiniMessage rather than Adventure MiniMessage, so a Kyori TagResolver cannot be mixed
     * into our builder. Keep the integration optional and invoke its public parser through its own
     * plugin class loader; the result is still the shared Adventure Component type.
     */
    private Optional<Component> parseCraftEngine(String input) {
        if (craftEngineUnavailable) return Optional.empty();
        Plugin plugin = owner.getServer().getPluginManager().getPlugin("CraftEngine");
        if (plugin == null || !plugin.isEnabled()) return Optional.empty();
        try {
            if (craftEnginePlugin != plugin || craftEngineMiniMessage == null || craftEngineDeserialize == null) {
                ClassLoader loader = plugin.getClass().getClassLoader();
                Class<?> helper = Class.forName("net.momirealms.craftengine.core.util.AdventureHelper", true, loader);
                Class<?> parser = Class.forName("net.momirealms.sparrow.message.MiniMessage", true, loader);
                craftEngineMiniMessage = helper.getMethod("miniMessage");
                craftEngineDeserialize = parser.getMethod("deserialize", String.class);
                craftEnginePlugin = plugin;
            }
            Object parser = craftEngineMiniMessage.invoke(null);
            Object rendered = craftEngineDeserialize.invoke(parser, input);
            if (rendered instanceof Component component) return Optional.of(component);
            throw new ReflectiveOperationException("CraftEngine parser returned "
                    + (rendered == null ? "null" : rendered.getClass().getName()));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            craftEngineUnavailable = true;
            if (!craftEngineWarning) {
                craftEngineWarning = true;
                owner.getLogger().log(Level.WARNING,
                        "CraftEngine title tags are unavailable; falling back to Nexo/literal pack tags", error);
            }
            return Optional.empty();
        }
    }

    private Optional<TagResolver> resolveNexo() {
        Plugin plugin = owner.getServer().getPluginManager().getPlugin("Nexo");
        if (plugin == null || !plugin.isEnabled()) return Optional.empty();
        try {
            Class<?> type = Class.forName("com.nexomc.nexo.glyphs.GlyphTag", true, plugin.getClass().getClassLoader());
            Field instance = type.getField("INSTANCE");
            Method resolver = type.getMethod("getRESOLVER");
            Object value = resolver.invoke(instance.get(null));
            return value instanceof TagResolver tags ? Optional.of(tags) : Optional.empty();
        } catch (ReflectiveOperationException | LinkageError error) {
            owner.getLogger().warning("Nexo is enabled but its glyph resolver is unavailable; resource-pack title disabled safely: "
                    + error.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
