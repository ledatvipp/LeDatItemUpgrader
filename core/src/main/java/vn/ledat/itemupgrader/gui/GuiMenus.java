package vn.ledat.itemupgrader.gui;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import vn.ledat.itemupgrader.quote.UpgradeRules;

/** Compiled layout contract. Dynamic list roles bind IDs from a filtered page, not configured item names. */
public record GuiMenus(GuiSettings settings, Map<GuiContext.Screen, MenuCompiler.CompiledMenu> menus,
                       java.util.Optional<UpgraderTitleLayout> titleLayout) {
    public GuiMenus {
        Objects.requireNonNull(settings); Objects.requireNonNull(titleLayout); menus = Map.copyOf(menus);
        if (!menus.keySet().equals(java.util.Set.of(GuiContext.Screen.values()))) throw new IllegalArgumentException("GUI requires main/catalog/profiles/boosts menus");
        for (var entry : menus.entrySet()) {
            var screen = entry.getKey(); var menu = entry.getValue();
            if (screen == GuiContext.Screen.MAIN && menu.sourceSlot() < 0) throw new IllegalArgumentException("main GUI requires source selector");
            if (screen != GuiContext.Screen.MAIN && menu.sourceSlot() >= 0) throw new IllegalArgumentException("list menu cannot own source input");
            var role = entryRole(screen);
            long count = menu.slots().values().stream().filter(e -> e.role() == role).count();
            if (screen != GuiContext.Screen.MAIN && (count < 1 || count > 45)) throw new IllegalArgumentException("list requires 1..45 entry slots");
            for (var element : menu.slots().values()) {
                if (isEntry(element.role()) && element.role() != role) throw new IllegalArgumentException("entry role in wrong menu");
                if (element.action() == MenuDefinition.Action.SELECT_TARGET && element.role() != MenuDefinition.Role.CATALOG_ENTRY)
                    throw new IllegalArgumentException("target selection must bind a catalog entry");
            }
            if (screen != GuiContext.Screen.MAIN && menu.slots().values().stream().noneMatch(e -> e.action() == MenuDefinition.Action.BACK_MAIN))
                throw new IllegalArgumentException("list menu requires BACK_MAIN");
            if (menu.slots().values().stream().noneMatch(e -> e.action() == MenuDefinition.Action.CLOSE))
                throw new IllegalArgumentException("menu requires CLOSE");
        }
    }
    public GuiMenus(GuiSettings settings, Map<GuiContext.Screen, MenuCompiler.CompiledMenu> menus) {
        this(settings, menus, java.util.Optional.empty());
    }
    public MenuCompiler.CompiledMenu menu(GuiContext.Screen screen) { return menus.get(screen); }
    public List<Integer> entrySlots(GuiContext.Screen screen) {
        if (screen == GuiContext.Screen.MAIN) return List.of();
        return menu(screen).slots().entrySet().stream().filter(e -> e.getValue().role() == entryRole(screen))
                .map(Map.Entry::getKey).sorted().toList();
    }
    public int pageSize(GuiContext.Screen screen) { return Math.max(1, entrySlots(screen).size()); }
    public void validateReferences(UpgradeRules rules) {
        for (var menu : menus.values()) for (var e : menu.slots().values()) {
            if (e.action() == MenuDefinition.Action.SELECT_PROFILE && e.role() != MenuDefinition.Role.PROFILE_ENTRY
                    && !rules.profiles().containsKey(e.argument())) throw new IllegalArgumentException("GUI profile missing: " + e.argument());
            if (e.action() == MenuDefinition.Action.TOGGLE_BOOST && e.role() != MenuDefinition.Role.BOOST_ENTRY
                    && !rules.boosts().containsKey(e.argument())) throw new IllegalArgumentException("GUI boost missing: " + e.argument());
        }
    }
    public static boolean isEntry(MenuDefinition.Role r) { return r == MenuDefinition.Role.CATALOG_ENTRY || r == MenuDefinition.Role.PROFILE_ENTRY || r == MenuDefinition.Role.BOOST_ENTRY; }
    public static MenuDefinition.Role entryRole(GuiContext.Screen screen) { return switch (screen) {
        case MAIN -> MenuDefinition.Role.SOURCE_INPUT;
        case CATALOG -> MenuDefinition.Role.CATALOG_ENTRY;
        case PROFILES -> MenuDefinition.Role.PROFILE_ENTRY;
        case BOOSTS -> MenuDefinition.Role.BOOST_ENTRY;
    }; }
}
