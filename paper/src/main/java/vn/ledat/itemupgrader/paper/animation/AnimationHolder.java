package vn.ledat.itemupgrader.paper.animation;

import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import vn.ledat.itemupgrader.animation.AnimationSessionStore;

/** Separate from the upgrader selection GUI: every slot is cosmetic and locked. */
public final class AnimationHolder implements InventoryHolder {
    private final UUID owner;
    private final AnimationSessionStore.Token token;
    private final Inventory inventory;
    public AnimationHolder(UUID owner, AnimationSessionStore.Token token, int size, Component title) {
        this.owner = Objects.requireNonNull(owner); this.token = Objects.requireNonNull(token);
        this.inventory = Bukkit.createInventory(this, size, title);
    }
    public AnimationSessionStore.Token token() { return token; }
    public boolean owned(UUID owner) { return this.owner.equals(owner); }
    @Override public Inventory getInventory() { return inventory; }
}
