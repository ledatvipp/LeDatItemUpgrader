package vn.ledat.itemupgrader.paper.animation;

import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import vn.ledat.itemupgrader.animation.*;
import vn.ledat.itemupgrader.paper.message.Messages;

/** Native cosmetics only: never deserialize a provider item, no unique identity, no RNG or payment. */
public final class AnimationRenderer {
    private record Key(AnimationMenu.Palette palette, AnimationTimeline.Phase phase) {}
    private final Messages messages;
    private final AnimationConfiguration config;
    private final AnimationRequest request;
    private final String preset;
    // At most 9 palettes * 7 phases per bounded viewer; no cache indexed by tick/UUID/elapsed text.
    private final Map<Key, ItemStack> cache = new HashMap<>();
    public AnimationRenderer(Messages messages, AnimationConfiguration config, AnimationRequest request, String preset) {
        this.messages = messages; this.config = config; this.request = request; this.preset = preset;
    }
    public Component title() {
        return title(new AnimationTimeline.Frame(AnimationTimeline.Phase.INTRO, 0, 0, Optional.empty()), 0);
    }
    public Component title(AnimationTimeline.Frame frame, long titleFrame) {
        return messages.template(config.menu().titleFrame(titleFrame), parameters(frame.phase()))
                .append(messages.component(request.origin() == AnimationRequest.Origin.ADMIN_PREVIEW
                        ? "animation-title-preview" : "animation-title-committed", Map.of()));
    }
    public Map<Integer, ItemStack> render(AnimationTimeline.Frame frame) {
        Map<Integer, ItemStack> result = new HashMap<>();
        for (int slot = 0; slot < config.menu().size(); slot++) result.put(slot, new ItemStack(Material.AIR));
        config.menu().frame(frame).forEach((slot, palette) -> result.put(slot,
                cache.computeIfAbsent(new Key(palette, frame.phase()), this::icon)));
        return Map.copyOf(result); // cached templates are never passed directly into the native inventory
    }
    private ItemStack icon(Key key) {
        var icon = config.menu().icons().get(key.palette());
        ItemStack stack = new ItemStack(Material.valueOf(icon.material()));
        var meta = stack.getItemMeta(); var params = parameters(key.phase());
        meta.displayName(messages.template(icon.name(), params).decoration(TextDecoration.ITALIC, false));
        var lore = new ArrayList<Component>();
        icon.lore().forEach(line -> lore.add(messages.template(line, params).decoration(TextDecoration.ITALIC, false)));
        // Badge and result get an explicit origin reminder independent of the configurable icon text.
        if (Set.of(AnimationMenu.Palette.BADGE, AnimationMenu.Palette.WIN, AnimationMenu.Palette.LOSS).contains(key.palette()))
            lore.add(messages.component(request.origin() == AnimationRequest.Origin.ADMIN_PREVIEW
                    ? "animation-preview-lore" : "animation-committed-lore", Map.of()).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore); meta.setEnchantmentGlintOverride(icon.glow());
        if (!icon.itemModel().isEmpty()) meta.setItemModel(Objects.requireNonNull(NamespacedKey.fromString(icon.itemModel())));
        if (icon.customModelData() != null) {
            var data = meta.getCustomModelDataComponent(); data.setFloats(List.of(icon.customModelData().floatValue()));
            meta.setCustomModelDataComponent(data);
        }
        stack.setItemMeta(meta); return stack;
    }
    private Map<String, String> parameters(AnimationTimeline.Phase phase) {
        boolean revealing = phase == AnimationTimeline.Phase.REVEAL || phase == AnimationTimeline.Phase.DONE;
        return Map.of("preset", preset, "stage", plain("animation-stage-" + phase.name().toLowerCase(Locale.ROOT)),
                "outcome", revealing ? plain("animation-result-" + request.outcome().name().toLowerCase(Locale.ROOT)) : plain("animation-result-hidden"),
                "mode", plain(request.origin() == AnimationRequest.Origin.ADMIN_PREVIEW ? "animation-mode-preview" : "animation-mode-committed"),
                "chance", request.probability().map(p -> p.percent().toPlainString() + "%").orElseGet(() -> plain("animation-no-chance")));
    }
    private String plain(String key) { return PlainTextComponentSerializer.plainText().serialize(messages.component(key, Map.of())); }
}
