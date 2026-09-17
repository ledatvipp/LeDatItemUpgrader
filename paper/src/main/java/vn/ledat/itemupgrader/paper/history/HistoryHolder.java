package vn.ledat.itemupgrader.paper.history;
import java.util.*;
import org.bukkit.inventory.*;
import org.bukkit.Bukkit;
import net.kyori.adventure.text.Component;
/** Inventory identity is the holder, never its title, lore or displayed transaction id. */
public final class HistoryHolder implements InventoryHolder {
    final UUID viewer,session;final long revision;private final Inventory inventory;
    HistoryHolder(UUID viewer,UUID session,long revision,int size,Component title) {
        this.viewer=viewer;this.session=session;this.revision=revision;inventory=Bukkit.createInventory(this,size,title);
    }
    @Override public Inventory getInventory(){return inventory;}
}
