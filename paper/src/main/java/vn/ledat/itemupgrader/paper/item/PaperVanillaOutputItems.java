package vn.ledat.itemupgrader.paper.item;

import java.util.*;
import java.util.function.BiPredicate;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.Repairable;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import vn.ledat.itemupgrader.item.*;
import vn.ledat.itemupgrader.output.OutputRejectedException;
import vn.ledat.itemupgrader.transfer.*;
import vn.ledat.itemupgrader.transaction.storage.JournalCodec;

/** Owner-thread native implementation, NOT an async scheduler or a custom-item adapter.
 * It deliberately rejects custom PDC/models/lore via the existing snapshot factory. PDC writes need a separate
 * provider adapter that attests which keys are NOT identities. No NBT/reflection or copying whole ItemMeta.
 * identityVerifier must use the actual Platform identity bridge; Material alone is not proof of Vanilla identity.
 */
public final class PaperVanillaOutputItems {
    private final PaperItemSnapshotFactory snapshots;
    private final BiPredicate<ItemStack,ItemKey> identityVerifier;
    public PaperVanillaOutputItems(PaperItemSnapshotFactory snapshots,BiPredicate<ItemStack,ItemKey> identityVerifier){
        this.snapshots=Objects.requireNonNull(snapshots);this.identityVerifier=Objects.requireNonNull(identityVerifier);
    }
    public ItemDocument inspect(ItemSnapshot snapshot){
        owner();vanilla(snapshot.facts().key());
        ItemStack item=ItemStack.deserializeBytes(snapshot.bytes());var doc=capture(item,snapshot.facts().key());
        if(!doc.snapshot().facts().equals(snapshot.facts())||!doc.snapshot().fingerprint().equals(snapshot.fingerprint()))
            throw rejected("native snapshot round-trip changed; version migration requires review");
        return doc;
    }
    public ItemDocument createFresh(ItemKey key,int amount){
        owner();vanilla(key);Material material=Material.matchMaterial(key.value());
        if(material==null||!material.isItem()||material.isAir()||amount<1||amount>material.getMaxStackSize())throw rejected("unsupported item/quantity");
        return capture(ItemStack.of(material,amount),key);
    }
    public ItemDocument apply(ItemDocument before,MetadataPatch patch){
        owner();vanilla(before.snapshot().facts().key());inspect(before.snapshot());
        if(patch.empty())return before;
        if(!patch.pdc().isEmpty())throw rejected("vanilla output adapter does not attest custom PDC writes");
        ItemStack copy=ItemStack.deserializeBytes(before.snapshot().bytes());var meta=copy.getItemMeta();
        if(meta instanceof EnchantmentStorageMeta)throw rejected("stored enchantment mutation needs a dedicated adapter");
        patch.customName().ifPresent(name->meta.displayName(Component.text(name))); // Never parse player text as MiniMessage.
        if(patch.repairCost().isPresent()){
            if(!(meta instanceof Repairable repair))throw rejected("target is not repairable");repair.setRepairCost(patch.repairCost().getAsInt());
        }
        if(patch.damage().isPresent()){
            if(!(meta instanceof Damageable damage))throw rejected("target is not damageable");damage.setDamage(patch.damage().getAsInt());
        }
        if(patch.enchantments().isPresent()){
            for(var enchant:new HashSet<>(meta.getEnchants().keySet()))meta.removeEnchant(enchant);
            for(var entry:patch.enchantments().orElseThrow().entrySet()){
                var key=NamespacedKey.fromString(entry.getKey());var enchant=key==null?null:Registry.ENCHANTMENT.get(key);
                if(enchant==null||!enchant.canEnchantItem(copy)||entry.getValue()<1||entry.getValue()>enchant.getMaxLevel())throw rejected("invalid/inapplicable enchantment");
                if(!meta.addEnchant(enchant,entry.getValue(),false))throw rejected("enchantment application was rejected");
            }
        }
        if(!copy.setItemMeta(meta))throw rejected("item metadata was rejected");
        var result=capture(copy,before.snapshot().facts().key());TransferPlanner.verify(before,result,patch);return result;
    }
    private ItemDocument capture(ItemStack item,ItemKey key){
        if(!identityVerifier.test(item,key))throw rejected("Platform did not attest Vanilla identity");
        ItemSnapshot snapshot=snapshots.capture(item,key);var meta=item.getItemMeta();
        Map<String,Integer> enchants=new TreeMap<>();Set<MutationCapabilities.Conflict> conflicts=new HashSet<>();
        var all=Registry.ENCHANTMENT.stream().toList();
        for(var enchant:all)if(enchant.canEnchantItem(item))enchants.put(enchant.key().asString(),enchant.getMaxLevel());
        for(int i=0;i<all.size();i++)for(int j=i+1;j<all.size();j++)if(all.get(i).conflictsWith(all.get(j)))
            conflicts.add(new MutationCapabilities.Conflict(all.get(i).key().asString(),all.get(j).key().asString()));
        var capabilities=new MutationCapabilities("minecraft",true,!(meta instanceof EnchantmentStorageMeta),meta instanceof Damageable,
                item.getMaxStackSize(),false,enchants,conflicts,Map.of(),Set.of());
        Optional<String> name=Optional.ofNullable(meta.displayName()).map(c->PlainTextComponentSerializer.plainText().serialize(c));
        int repairCost=meta instanceof Repairable repair?repair.getRepairCost():0;
        // Normalize ONLY fields that this adapter can intentionally write, to verify all remaining data stays intact.
        ItemStack protectedCopy=item.clone();var normalized=protectedCopy.getItemMeta();normalized.displayName(null);
        if(normalized instanceof Damageable damage)damage.setDamage(0);
        if(normalized instanceof Repairable repair)repair.setRepairCost(0);
        for(var enchant:new HashSet<>(normalized.getEnchants().keySet()))normalized.removeEnchant(enchant);
        if(!protectedCopy.setItemMeta(normalized))throw rejected("cannot normalize protected metadata");
        var view=new MetadataView(name,repairCost,meta.isUnbreakable(),Map.of(),JournalCodec.digest(protectedCopy.serializeAsBytes()),
                JournalCodec.digest(item.serializeAsBytes()),Set.of());
        return new ItemDocument(snapshot,view,capabilities);
    }
    private static void owner(){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("native output item operation requires Paper owner thread");}
    private static void vanilla(ItemKey key){if(!key.vanilla())throw rejected("custom provider adapter not registered");}
    private static OutputRejectedException rejected(String reason){return new OutputRejectedException(OutputRejectedException.Code.CAPABILITY_UNAVAILABLE,reason);}
}
