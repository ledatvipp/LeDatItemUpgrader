package vn.ledat.itemupgrader.gui;

import java.util.Objects;
import java.util.UUID;

/** Stable identity of one native GUI container; intentionally excludes per-click sequence. */
public record GuiTitleToken(UUID viewer, UUID session, UUID view, long revision) {
    public GuiTitleToken {
        Objects.requireNonNull(viewer); Objects.requireNonNull(session); Objects.requireNonNull(view);
        if (revision < 1) throw new IllegalArgumentException("invalid GUI title revision");
    }
    public static GuiTitleToken of(GuiSessionStore.Handle handle) {
        return new GuiTitleToken(handle.viewer(), handle.session(), handle.view(), handle.revision());
    }
    public boolean matches(GuiSessionStore.Handle handle) {
        return viewer.equals(handle.viewer()) && session.equals(handle.session()) && view.equals(handle.view()) && revision == handle.revision();
    }
}
