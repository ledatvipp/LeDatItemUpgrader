package vn.ledat.itemupgrader.paper.gui;

import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import vn.ledat.itemupgrader.gui.GuiSessionStore;

/** Native container is the authority. This inventory contains PREVIEW copies only, never player custody. */
public final class UpgraderGuiHolder implements InventoryHolder {
    private final UUID ownerToken;
    private final GuiSessionStore.Handle identity;
    private final Inventory inventory;
    public UpgraderGuiHolder(UUID ownerToken, GuiSessionStore.Handle identity, int size, Component title) {
        this.ownerToken=ownerToken; this.identity=identity;
        this.inventory=Bukkit.createInventory(this,size,title);
    }
    @Override public Inventory getInventory() { return inventory; }
    public GuiSessionStore.Handle identity() { return identity; }
    public boolean owned(UUID token) { return ownerToken.equals(token); }
}
