package vn.ledat.itemupgrader.paper.gui;

import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import vn.ledat.itemupgrader.gui.*;
import vn.ledat.itemupgrader.item.ItemSnapshot;
import vn.ledat.itemupgrader.paper.message.Messages;
import vn.ledat.itemupgrader.runtime.UpgraderRuntime;
import vn.ledat.itemupgrader.util.Decimals;

/** Owner-thread native presentation. No provider create(), cursor writes, source changes, RNG or payment. */
public final class GuiRenderer {
    public record Binding(MenuDefinition.Action action, String argument) {}
    public record Frame(Map<Integer,ItemStack> items, Map<Integer,Binding> actions) {
        public Frame { items=Map.copyOf(items); actions=Map.copyOf(actions); }
    }
    private final Messages messages;
    private long revision;
    private final Map<MenuDefinition.Element,ItemStack> staticItems=new HashMap<>();
    public GuiRenderer(Messages messages) { this.messages=messages; }
    public Frame render(long activeRevision, UpgraderRuntime runtime, GuiSessionStore.State state,
                        Optional<GuiPreviewService.Preview> preview, String statusKey) {
        if(revision!=activeRevision) { revision=activeRevision; staticItems.clear(); }
        GuiMenus gui=runtime.gui().orElseThrow(); var menu=gui.menu(state.context().screen());
        Map<String,String> shared=parameters(runtime,state,preview,statusKey);
        var filler=menu.slots().entrySet().stream().sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue)
                .filter(e->e.role()==MenuDefinition.Role.FILLER).findFirst();
        Map<Integer,ItemStack> items=new HashMap<>(); Map<Integer,Binding> actions=new HashMap<>();
        Map<Integer,GuiPreviewService.Entry> entries=new HashMap<>();
        var positions=gui.entrySlots(state.context().screen());
        preview.ifPresent(p->{for(int i=0;i<Math.min(positions.size(),p.entries().size());i++) entries.put(positions.get(i),p.entries().get(i));});
        int iconBytes=0;
        for(int slot=0;slot<menu.size();slot++) {
            var configured=menu.slots().get(slot);
            if(configured==null) { items.put(slot,new ItemStack(Material.AIR)); continue; }
            var e=configured;
            var row=entries.get(slot); Map<String,String> params=new HashMap<>(shared);
            Optional<ItemSnapshot> icon=Optional.empty(); boolean selected=false; String argument=e.argument();
            if(GuiMenus.isEntry(e.role())) {
                if(row==null) {
                    if(filler.isEmpty()) { items.put(slot,new ItemStack(Material.AIR)); continue; }
                    e=filler.orElseThrow();
                }
                else {
                    argument=row.id(); params.put("id",row.id());
                    if(e.role()==MenuDefinition.Role.CATALOG_ENTRY) {
                        var target=row.target().orElseThrow(); icon=Optional.of(target.snapshot());
                        params.put("item",target.definition().item().value()); params.put("amount",String.valueOf(target.definition().amount()));
                        params.put("value",Decimals.display(target.value().totalValue())); selected=state.context().targetId().equals(row.id());
                    } else if(e.role()==MenuDefinition.Role.PROFILE_ENTRY) {
                        var profile=runtime.upgradeRules().profiles().get(row.id());
                        params.put("multiplier",Decimals.display(profile.chanceMultiplier())); params.put("fee_multiplier",Decimals.display(profile.feeMultiplier()));
                        params.put("failure",plain("quote-failure-"+profile.failure().name().toLowerCase(Locale.ROOT)));
                        selected=Objects.equals(params.get("profile"),profile.id());
                    } else {
                        var boost=runtime.upgradeRules().boosts().get(row.id());
                        params.put("multiplier",Decimals.display(boost.chanceMultiplier())); params.put("points",Decimals.display(boost.bonusPercentagePoints()));
                        selected=state.context().boosts().contains(row.id());
                    }
                }
            } else if(e.role()==MenuDefinition.Role.SOURCE_INPUT||e.role()==MenuDefinition.Role.SOURCE_PREVIEW) icon=preview.flatMap(GuiPreviewService.Preview::source);
            else if(e.role()==MenuDefinition.Role.TARGET) icon=preview.flatMap(GuiPreviewService.Preview::target).map(t->t.snapshot());
            if(e.action()==MenuDefinition.Action.SELECT_PROFILE&&!GuiMenus.isEntry(e.role())) selected=Objects.equals(params.get("profile"),e.argument());
            if(e.action()==MenuDefinition.Action.TOGGLE_BOOST&&!GuiMenus.isEntry(e.role())) selected=state.context().boosts().contains(e.argument());
            params.put("selected",plain(selected?"gui-selected":"gui-not-selected"));
            boolean iconFallback=icon.isPresent()&&(icon.orElseThrow().byteSize()>gui.settings().maximumIconBytes()
                    || iconBytes+icon.orElseThrow().byteSize()>524288);
            if(icon.isPresent()&&!iconFallback) iconBytes+=icon.orElseThrow().byteSize();
            var rendered=item(e,params,iconFallback?Optional.empty():icon,selected,iconFallback,runtime.upgradesEnabled());
            if(e.role()==MenuDefinition.Role.INFO && preview.isPresent()) {
                var details=new ArrayList<Component>(Optional.ofNullable(rendered.getItemMeta().lore()).orElse(List.of()));
                preview.orElseThrow().quote().ifPresent(result->{
                    if(result.quote().isEmpty()) details.add(messages.component("quote-denied-"+result.status().name().toLowerCase(Locale.ROOT).replace('_','-'),Map.of()));
                    result.quote().ifPresent(q->{
                        for(var line:q.terms().costs().lines()) details.add(messages.component("gui-cost-line",Map.of(
                                "resource",line.resource().key(),"reserve",Decimals.display(line.reserve()),
                                "success",Decimals.display(line.consumed(true)),"failure",Decimals.display(line.consumed(false)))));
                        if(!q.resources().available()) details.add(messages.component("gui-resources-warning",Map.of()));
                    });
                });
                var detailsMeta=rendered.getItemMeta(); detailsMeta.lore(details.stream().map(c->c.decoration(TextDecoration.ITALIC,false)).toList()); rendered.setItemMeta(detailsMeta);
            }
            items.put(slot,rendered);
            boolean enabled=!state.busy() || e.action()==MenuDefinition.Action.CLOSE;
            if(e.action()==MenuDefinition.Action.NEXT_PAGE) enabled&=preview.map(p->state.context().page()<p.pages()).orElse(false);
            if(e.action()==MenuDefinition.Action.PREVIOUS_PAGE) enabled&=state.context().page()>1;
            if(e.action()==MenuDefinition.Action.TOGGLE_BOOST) {
                var b=runtime.upgradeRules().boosts().get(argument); enabled&=b!=null&&b.enabled();
                // Selected boost can always be removed even if access/profile changed since selection.
                if(state.context().boosts().contains(argument)) enabled=!state.busy();
            }
            if(e.action()==MenuDefinition.Action.SELECT_PROFILE) { var p=runtime.upgradeRules().profiles().get(argument); enabled&=p!=null&&p.enabled(); }
            if(enabled&&e.action()!=MenuDefinition.Action.NONE) actions.put(slot,new Binding(e.action(),argument));
        }
        return new Frame(items,actions);
    }
    public Map<String,String> parameters(UpgraderRuntime runtime, GuiSessionStore.State state, Optional<GuiPreviewService.Preview> p, String statusKey) {
        Map<String,String> data=new HashMap<>(); String unset=plain("gui-unset");
        for(String key:List.of("chance","source_value","target_value","source","target","profile","fee","failure","source_slot","item","amount","value","id","multiplier","fee_multiplier","points")) data.put(key,unset);
        data.put("page",String.valueOf(state.context().page())); data.put("pages",String.valueOf(p.map(GuiPreviewService.Preview::pages).orElse(0)));
        data.put("category",state.context().category().isEmpty()?plain("gui-all-categories"):state.context().category());
        data.put("sort",plain("gui-sort-"+state.context().sort().name().toLowerCase(Locale.ROOT).replace('_','-')));
        data.put("boosts",state.context().boosts().isEmpty()?plain("gui-no-boosts"):String.join(", ",state.context().boosts()));
        data.put("status",plain(statusKey));
        p.ifPresent(preview->{
            preview.source().ifPresent(source->{data.put("source",source.facts().key().value());data.put("source_slot",String.valueOf(state.context().sourceSlot()));
                data.put("amount",String.valueOf(source.facts().amount()));
                data.put("profile",runtime.upgradeRules().profileFor(runtime.catalog().pathFor(source.facts().key()),state.context().profileId()));});
            preview.value().flatMap(v->v.quote()).ifPresent(v->data.put("source_value",Decimals.display(v.totalValue())));
            preview.target().ifPresent(t->{data.put("target",t.definition().id());data.put("target_value",Decimals.display(t.value().totalValue()));});
            preview.quote().flatMap(q->q.quote()).ifPresent(q->{
                data.put("chance",Decimals.display(q.chance().probability().percent())+"%");
                data.put("profile",q.terms().profileId()); data.put("failure",plain("quote-failure-"+q.terms().failure().name().toLowerCase(Locale.ROOT)));
                data.put("fee",q.terms().costs().lines().isEmpty()?plain("gui-no-fees"):q.terms().costs().lines().stream()
                        .map(line->line.resource().key()+" ×"+Decimals.display(line.reserve())).collect(java.util.stream.Collectors.joining("; ")));
            });
        });
        return data;
    }
    private ItemStack item(MenuDefinition.Element e, Map<String,String> params, Optional<ItemSnapshot> preview, boolean selected, boolean fallback,boolean upgradesEnabled) {
        boolean cacheable=preview.isEmpty()&&!selected&&!fallback&&e.role()==MenuDefinition.Role.FILLER
                &&!e.name().contains("{")&&e.lore().stream().noneMatch(s->s.contains("{"));
        if(cacheable&&staticItems.containsKey(e)) return staticItems.get(e).clone();
        ItemStack item=new ItemStack(Material.valueOf(e.material()));
        boolean unavailable=fallback;
        if(preview.isPresent()) {
            try {
                var snapshot=preview.orElseThrow();
                var decoded=ItemStack.deserializeBytes(snapshot.bytes());
                if(decoded.getType().isAir()) throw new IllegalArgumentException("air preview");
                item=visualProjection(decoded,snapshot.facts().amount());
            }
            catch(RuntimeException invalid) { item=new ItemStack(Material.valueOf(e.material())); unavailable=true; }
        }
        var meta=item.getItemMeta();
        meta.displayName(messages.template(e.name(),params).decoration(TextDecoration.ITALIC,false));
        var lore=new ArrayList<Component>();
        for(String line:e.lore()) lore.add(messages.template(line,params).decoration(TextDecoration.ITALIC,false));
        if(unavailable) lore.add(messages.component("gui-icon-fallback",Map.of()).decoration(TextDecoration.ITALIC,false));
        if(e.role()==MenuDefinition.Role.SOURCE_INPUT||e.action()==MenuDefinition.Action.UPGRADE&&!upgradesEnabled)
            lore.add(messages.component("gui-preview-only-lore",Map.of()).decoration(TextDecoration.ITALIC,false));
        if(e.action()==MenuDefinition.Action.UPGRADE) lore.add(messages.component(upgradesEnabled?"gui-fee-live-warning":"gui-fee-warning",Map.of()).decoration(TextDecoration.ITALIC,false));
        meta.lore(lore); meta.setEnchantmentGlintOverride(e.glow()||selected);
        // Dynamic source/target/catalog previews keep their real material and model. The configured
        // PAPER + empty model is only a fallback/button presentation, never an override of real items.
        if(preview.isEmpty()&&!e.itemModel().isEmpty()) meta.setItemModel(NamespacedKey.fromString(e.itemModel()));
        if(preview.isEmpty()&&e.customModelData()!=null) meta.setCustomModelData(e.customModelData());
        item.setItemMeta(meta);
        if(cacheable&&staticItems.size()<256) staticItems.put(e,item.clone());
        return item;
    }
    /**
     * A GUI icon is a new visual projection, NOT a second provider/unique item.
     * Only material/quantity/item-model/custom-model-data cross this boundary. No PDC, provider identity,
     * nested container content, attributes, effects, original lore or player-supplied Components are copied.
     * More visual features (heads/dyed armor/potions) need explicit projection adapters + native tests.
     */
    private static ItemStack visualProjection(ItemStack decoded,int amount) {
        var icon=new ItemStack(decoded.getType(),Math.max(1,Math.min(64,amount)));
        var meta=icon.getItemMeta(); var original=decoded.getItemMeta();
        meta.setItemModel(original.getItemModel());
        meta.setCustomModelDataComponent(original.getCustomModelDataComponent());
        icon.setItemMeta(meta); return icon;
    }
    private String plain(String key) { return PlainTextComponentSerializer.plainText().serialize(messages.component(key,Map.of())); }
    public void clear() { staticItems.clear(); }
}
