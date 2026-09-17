package vn.ledat.itemupgrader.paper.item;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import vn.ledat.itemupgrader.item.ItemFacts;
import vn.ledat.itemupgrader.item.ItemKey;
import vn.ledat.itemupgrader.item.ItemSnapshot;

public final class PaperItemSnapshotFactory {
    public ItemSnapshot capture(ItemStack source, ItemKey verifiedKey) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Snapshot capture requires Paper main thread");
        if (source == null || source.getType().isAir() || source.getAmount() < 1) throw new IllegalArgumentException("empty source");
        ItemStack copy = source.clone();
        ItemMeta meta = copy.getItemMeta();
        Set<ItemFacts.Risk> risks = new HashSet<>();
        if (copy.getAmount() > copy.getMaxStackSize()) risks.add(ItemFacts.Risk.ILLEGAL_STACK);
        // Conservatively reject container data instead of accidentally discarding nested valuable items.
        if (meta instanceof BlockStateMeta || meta instanceof BundleMeta) risks.add(ItemFacts.Risk.NESTED_CONTENTS);
        if (verifiedKey.vanilla() && (!copy.getPersistentDataContainer().getKeys().isEmpty()
                || meta.hasCustomModelData() || meta.hasItemModel() || meta.hasLore()))
            risks.add(ItemFacts.Risk.UNKNOWN_CUSTOM_METADATA);
        Map<String, Integer> enchants = new HashMap<>();
        copy.getEnchantments().forEach((enchantment, level) -> enchants.put(enchantment.key().asString(), level));
        if (meta instanceof EnchantmentStorageMeta book) book.getStoredEnchants().forEach((enchantment, level) ->
                enchants.merge(enchantment.key().asString(), level, Math::max));
        int damage = 0, maximumDamage = 0;
        if (meta instanceof Damageable durability) {
            damage = durability.getDamage();
            maximumDamage = durability.hasMaxDamage() ? durability.getMaxDamage() : copy.getType().getMaxDurability();
        }
        // Provider rarity/stats are NOT inferred from lore. The supplied neutral API has no such accessor contract.
        ItemFacts facts = new ItemFacts(verifiedKey, copy.getAmount(), damage, maximumDamage, enchants, "", risks);
        return new ItemSnapshot(facts, copy.serializeAsBytes());
    }
}
