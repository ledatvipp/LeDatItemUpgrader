package vn.ledat.itemupgrader.animation;

import java.util.Optional;
import vn.ledat.itemupgrader.gui.GuiClickPolicy;

/** Common click allow-list narrowed to TOP LEFT SKIP/CLOSE. Bottom source-reference actions are never accepted. */
public final class AnimationClickPolicy {
    public record Decision(boolean cancel, Optional<AnimationMenu.Role> action) {}
    public Decision decide(GuiClickPolicy.Input input, AnimationMenu menu) {
        var result = new GuiClickPolicy().decide(input);
        if (!input.ownedTop() || input.topSize() != menu.size() || result.route() != GuiClickPolicy.Route.TOP
                || input.click() != GuiClickPolicy.Click.LEFT) return new Decision(result.cancel(), Optional.empty());
        var role = menu.slots().get(result.slot());
        return new Decision(true, role == AnimationMenu.Role.SKIP || role == AnimationMenu.Role.CLOSE
                ? Optional.of(role) : Optional.empty());
    }
}
