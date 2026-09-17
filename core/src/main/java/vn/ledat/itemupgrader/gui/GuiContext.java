package vn.ledat.itemupgrader.gui;

import java.util.List;
import java.util.Objects;
import vn.ledat.itemupgrader.catalog.CatalogQuery;

/** UI selection only: a storage slot REFERENCE, never an escrow or item owner. */
public record GuiContext(Screen screen, int page, CatalogQuery.Sort sort, String category,
                         int sourceSlot, String targetId, String profileId, List<String> boosts) {
    public enum Screen { MAIN, CATALOG, PROFILES, BOOSTS }
    public GuiContext {
        Objects.requireNonNull(screen); Objects.requireNonNull(sort);
        if (page < 1 || page > 10000 || sourceSlot < -1 || sourceSlot > 35) throw new IllegalArgumentException("invalid GUI page/source slot");
        for (String id : List.of(category, targetId, profileId))
            if (!id.isEmpty() && !id.matches("[a-z0-9][a-z0-9_.-]{0,63}")) throw new IllegalArgumentException("invalid GUI selection id");
        boosts = List.copyOf(boosts);
        if (boosts.size() > 8 || boosts.stream().distinct().count() != boosts.size()
                || boosts.stream().anyMatch(id -> !id.matches("[a-z0-9][a-z0-9_.-]{0,63}"))) throw new IllegalArgumentException("invalid GUI boosts");
        boosts = boosts.stream().sorted().toList();
    }
    public static GuiContext initial(int slot) { return new GuiContext(Screen.MAIN, 1, CatalogQuery.Sort.RECOMMENDED, "", slot, "", "", List.of()); }
    public GuiContext screen(Screen next) { return new GuiContext(next, 1, sort, category, sourceSlot, targetId, profileId, boosts); }
    public GuiContext page(int next) { return new GuiContext(screen, next, sort, category, sourceSlot, targetId, profileId, boosts); }
    public GuiContext target(String id) { return new GuiContext(screen, page, sort, category, sourceSlot, id, profileId, boosts); }
    public GuiContext profile(String id) { return new GuiContext(screen, page, sort, category, sourceSlot, targetId, id, boosts); }
    public GuiContext boosts(List<String> ids) { return new GuiContext(screen, page, sort, category, sourceSlot, targetId, profileId, ids); }
    public GuiContext sort(CatalogQuery.Sort next) { return new GuiContext(screen, 1, next, category, sourceSlot, targetId, profileId, boosts); }
    public GuiContext category(String next) { return new GuiContext(screen, 1, sort, next, sourceSlot, targetId, profileId, boosts); }
}
