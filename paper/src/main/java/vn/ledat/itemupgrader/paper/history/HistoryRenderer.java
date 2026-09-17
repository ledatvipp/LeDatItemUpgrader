package vn.ledat.itemupgrader.paper.history;
import java.util.*;
import java.time.format.DateTimeFormatter;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import vn.ledat.itemupgrader.history.*;
import vn.ledat.itemupgrader.gui.MenuDefinition;
import vn.ledat.itemupgrader.paper.message.Messages;
/** Icons are freshly built from config. The read model contains no real ItemStacks to copy or deliver. */
final class HistoryRenderer {
    private final Messages messages;
    HistoryRenderer(Messages messages){this.messages=messages;}
    void render(HistoryHolder holder,HistorySettings settings,HistorySessionStore.View view,String status) {
        var entries=settings.entrySlots();var page=view.page();
        var filler=settings.menu().slots().values().stream().filter(e->e.role()==MenuDefinition.Role.FILLER).findFirst().orElseThrow();
        for(int slot=0;slot<settings.menu().size();slot++) {
            var element=settings.menu().slots().get(slot);Map<String,String> p=new HashMap<>();
            p.put("page",Integer.toString(view.pageNumber()));p.put("player",view.subject().toString());p.put("status",plain(status));
            p.put("filter",plain("history-filter-"+view.filter().name().toLowerCase(Locale.ROOT)));
            int index=entries.indexOf(slot);
            if(index>=0) {
                if(page.isEmpty()||index>=page.orElseThrow().rows().size())element=filler;
                else {
                    var row=page.orElseThrow().rows().get(index);
                    p.put("transaction",row.transactionId().toString());p.put("source",row.sourceKey().value());p.put("source_amount",Integer.toString(row.sourceAmount()));
                    p.put("target",row.targetKey().value());p.put("target_amount",Integer.toString(row.targetAmount()));p.put("target_id",row.targetId());
                    p.put("source_value",row.sourceValue().toPlainString());p.put("target_value",row.targetValue().toPlainString());
                    p.put("chance",row.probability().percent().stripTrailingZeros().toPlainString());p.put("profile",row.profile());
                    p.put("outcome",plain("history-outcome-"+row.outcome().name().toLowerCase(Locale.ROOT).replace('_','-')));
                    p.put("state",plain("history-state-"+row.state().name().toLowerCase(Locale.ROOT).replace('_','-')));
                    p.put("created_at",DateTimeFormatter.ISO_INSTANT.format(row.createdAt()));p.put("updated_at",DateTimeFormatter.ISO_INSTANT.format(row.updatedAt()));
                }
            }
            var item=new ItemStack(Material.valueOf(element.material()));var meta=item.getItemMeta();
            meta.displayName(messages.template(element.name(),p).decoration(TextDecoration.ITALIC,false));
            meta.lore(element.lore().stream().map(l->messages.template(l,p).decoration(TextDecoration.ITALIC,false)).toList());
            meta.setEnchantmentGlintOverride(element.glow());
            if(!element.itemModel().isEmpty())meta.setItemModel(NamespacedKey.fromString(element.itemModel()));
            if(element.customModelData()!=null){var model=meta.getCustomModelDataComponent();model.setFloats(List.of(element.customModelData().floatValue()));meta.setCustomModelDataComponent(model);}
            item.setItemMeta(meta);
            if(!Objects.equals(holder.getInventory().getItem(slot),item))holder.getInventory().setItem(slot,item);
        }
    }
    private String plain(String key){return PlainTextComponentSerializer.plainText().serialize(messages.component(key,Map.of()));}
}
