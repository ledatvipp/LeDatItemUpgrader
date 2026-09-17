package vn.ledat.itemupgrader.gui;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Presentation blueprint only. No inventory or transaction side effects live in this model. */
public record MenuDefinition(String id, String title, List<String> matrix, Map<Character, Element> symbols) {
    public enum Role { FILLER, SOURCE_INPUT, SOURCE_PREVIEW, TARGET, CHANCE, INFO, BUTTON, CATALOG_ENTRY, PROFILE_ENTRY, BOOST_ENTRY }
    public enum Action { NONE, SOURCE_INPUT, OPEN_CATALOG, SELECT_PROFILE, TOGGLE_BOOST, UPGRADE, CLOSE, NEXT_PAGE, PREVIOUS_PAGE, SELECT_TARGET, BACK_MAIN, OPEN_PROFILES, OPEN_BOOSTS, REFRESH, CLEAR_BOOSTS, CYCLE_SORT, CYCLE_CATEGORY }
    public record Element(Role role, String material, String name, List<String> lore,
                          String itemModel, Integer customModelData, boolean glow, Action action, String argument) {
        public Element {
            Objects.requireNonNull(role, "role"); Objects.requireNonNull(material, "material");
            Objects.requireNonNull(name, "name"); Objects.requireNonNull(itemModel, "item model");
            Objects.requireNonNull(action, "action"); Objects.requireNonNull(argument, "argument");
            lore = List.copyOf(lore);
            if (!material.matches("[A-Z][A-Z0-9_]{0,63}")) throw new IllegalArgumentException("invalid material syntax");
            if (!itemModel.isEmpty() && !itemModel.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
                throw new IllegalArgumentException("invalid item model key");
            if (customModelData != null && customModelData < 0) throw new IllegalArgumentException("negative custom model data");
            if (name.length() > 2048 || lore.size() > 32 || lore.stream().anyMatch(s -> s.length() > 2048)
                    || argument.length() > 128) throw new IllegalArgumentException("menu text too long");
            if (role == Role.SOURCE_INPUT && action != Action.SOURCE_INPUT)
                throw new IllegalArgumentException("source role must use source action");
            if (action == Action.SOURCE_INPUT && role != Role.SOURCE_INPUT)
                throw new IllegalArgumentException("source action requires source role");
            if ((role == Role.FILLER || role == Role.SOURCE_PREVIEW) && action != Action.NONE)
                throw new IllegalArgumentException("read-only role cannot own actions");
            if ((action == Action.SELECT_PROFILE || action == Action.TOGGLE_BOOST) && !GuiMenus.isEntry(role) && argument.isBlank())
                throw new IllegalArgumentException("selection action needs an explicit id");
            if (role == Role.CATALOG_ENTRY && action != Action.SELECT_TARGET
                    || role == Role.PROFILE_ENTRY && action != Action.SELECT_PROFILE
                    || role == Role.BOOST_ENTRY && action != Action.TOGGLE_BOOST)
                throw new IllegalArgumentException("dynamic entry/action mismatch");
            if (GuiMenus.isEntry(role) && !argument.isEmpty()) throw new IllegalArgumentException("dynamic entry argument must be empty");
        }
    }
    public MenuDefinition {
        Objects.requireNonNull(id, "id"); Objects.requireNonNull(title, "title");
        if (!id.matches("[a-z0-9_-]{1,64}") || title.length() > 2048) throw new IllegalArgumentException("invalid menu header");
        matrix = List.copyOf(matrix); symbols = Map.copyOf(symbols);
    }
}
