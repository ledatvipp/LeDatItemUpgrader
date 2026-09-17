package vn.ledat.itemupgrader.gui;

/** Pure allow-list. Every recognized GUI click remains cancelled, even when a selection is dispatched. */
public final class GuiClickPolicy {
    public enum Click { LEFT, RIGHT, SHIFT_LEFT, SHIFT_RIGHT, NUMBER_KEY, SWAP_OFFHAND, DOUBLE_CLICK, MIDDLE, DROP, CONTROL_DROP, CREATIVE, UNKNOWN }
    public enum Effect { PICKUP_ALL, PICKUP_HALF, NOTHING, OTHER }
    public enum Route { IGNORE, TOP, SOURCE_REFERENCE }
    public record Input(boolean ownedTop, boolean currentView, boolean busy, boolean alreadyCancelled,
                        boolean cursorEmpty, boolean creativeEvent, int topSize, int rawSlot, int storageSlot, Click click, Effect effect) {}
    public record Decision(boolean cancel, Route route, int slot) {}
    public Decision decide(Input input) {
        if (!input.ownedTop()) return new Decision(false, Route.IGNORE, -1);
        var blocked = new Decision(true, Route.IGNORE, -1);
        if (!input.currentView() || input.busy() || input.alreadyCancelled() || !input.cursorEmpty() || input.creativeEvent()
                || input.topSize()<9 || input.topSize()>54 || input.topSize()%9!=0) return blocked;
        boolean left=input.click()==Click.LEFT&&(input.effect()==Effect.PICKUP_ALL||input.effect()==Effect.NOTHING);
        boolean right=input.click()==Click.RIGHT&&(input.effect()==Effect.PICKUP_HALF||input.effect()==Effect.NOTHING);
        if (!left && !right) return blocked;
        if(input.rawSlot()>=0 && input.rawSlot()<input.topSize()) return new Decision(true,Route.TOP,input.rawSlot());
        if(left && input.rawSlot()>=input.topSize() && input.rawSlot()<input.topSize()+36
                && input.storageSlot()>=0 && input.storageSlot()<36) return new Decision(true,Route.SOURCE_REFERENCE,input.storageSlot());
        return blocked;
    }
    /** Bottom drags are blocked too: a source reference must not move inside a locked preview. */
    public boolean cancelDrag(boolean ownedTop) { return ownedTop; }
}
