package vn.ledat.itemupgrader.demo;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import vn.ledat.itemupgrader.boost.BoostDefinition;
import vn.ledat.itemupgrader.catalog.*;
import vn.ledat.itemupgrader.chance.ChanceFormula;
import vn.ledat.itemupgrader.condition.ConditionDefinition;
import vn.ledat.itemupgrader.cost.*;
import vn.ledat.itemupgrader.gui.*;
import vn.ledat.itemupgrader.item.*;
import vn.ledat.itemupgrader.profile.RiskProfile;
import vn.ledat.itemupgrader.quote.*;
import vn.ledat.itemupgrader.util.Decimals;
import vn.ledat.itemupgrader.value.ValueDefinitions;

/** Read-only detached demo. Item bytes are synthetic, never Minecraft NBT or native inventory evidence. */
public final class GuiDemo {
    public static final UUID VIEWER=new UUID(6,1), SESSION=new UUID(6,2);
    public static final Instant NOW=Instant.parse("2026-09-17T00:00:00Z");
    public static final ItemKey IRON=ItemKey.of("minecraft:iron_ingot"), GOLD=ItemKey.of("minecraft:gold_ingot"),
            DIAMOND=ItemKey.of("minecraft:diamond"), EMERALD=ItemKey.of("minecraft:emerald");
    public static final CostResource VAULT=CostResource.currency(CostResource.Currency.VAULT);
    public record Fixture(CatalogIndex index, UpgradeRules rules, ItemSnapshot source, CatalogAccess access, ResourceSnapshot resources) {}
    private GuiDemo() {}
    public static ItemSnapshot item(ItemKey key,int amount) { return new ItemSnapshot(ItemFacts.clean(key,amount),new byte[]{6,0,1}); }
    public static List<TargetDefinition> targets() { return List.of(target("gold",GOLD,"metals","",Set.of()),target("diamond",DIAMOND,"gems","",Set.of()),target("emerald",EMERALD,"gems","",Set.of())); }
    public static TargetDefinition target(String id,ItemKey key,String category,String permission,Set<String> conditions) { return new TargetDefinition(id,key,1,id,category,Set.of(),true,0,permission,conditions,Set.of()); }
    public static Fixture fixture() { return fixture(targets(),List.of(),List.of()); }
    public static Fixture fixture(List<TargetDefinition> targets,List<UpgradePath> paths,List<ConditionDefinition> conditions) {
        var catalog=new CatalogDefinitions(CatalogSettings.defaults(),targets,paths);
        var values=ValueDefinitions.manualOnly(Map.of(IRON,new BigDecimal("90"),GOLD,new BigDecimal("180"),DIAMOND,new BigDecimal("900"),EMERALD,new BigDecimal("450")));
        var probes=new HashMap<String,TargetProbe>(); for(var target:targets) probes.put(target.id(),TargetProbe.verified(item(target.item(),target.amount())));
        var index=CatalogIndex.build(6,catalog,values,probes);
        var cost=new CostEntry(VAULT,new BigDecimal("25"),CostEntry.ConsumeWhen.ON_ATTEMPT);
        var profiles=List.of(profile("standard",true,"1","1",RiskProfile.FailureMode.DESTROY,List.of(),""),
                profile("safe",true,"0.60","1.75",RiskProfile.FailureMode.KEEP,List.of(cost),""),
                profile("vip",true,"1.1","1",RiskProfile.FailureMode.DESTROY,List.of(),"gui.vip"),
                profile("disabled",false,"1","1",RiskProfile.FailureMode.DESTROY,List.of(),""));
        var shardCost=new CostEntry(CostResource.item(EMERALD),new BigDecimal("2"),CostEntry.ConsumeWhen.ON_ATTEMPT);
        var boosts=List.of(boost("lucky_shard",true,"",Set.of("standard"),List.of(shardCost)),boost("vip_shard",true,"gui.vip",Set.of("standard"),List.of(shardCost)),boost("disabled",false,"",Set.of(),List.of(shardCost)));
        var rules=new UpgradeRules(catalog,QuoteSettings.defaults(),Map.of("ratio",new ChanceFormula.Ratio(new BigDecimal("0.9"))),profiles,boosts,List.of(),conditions,List.of());
        var resources=new ResourceSnapshot(VIEWER,Map.of(VAULT,new BigDecimal("1000")),Set.of(EMERALD),
                List.of(new ResourceSnapshot.ItemSupply(1,EMERALD,4,item(EMERALD,4).fingerprint())),Set.of(0));
        return new Fixture(index,rules,item(IRON,1),new CatalogAccess(VIEWER,Set.of(),Set.of()),resources);
    }
    private static RiskProfile profile(String id,boolean enabled,String chance,String fee,RiskProfile.FailureMode failure,List<CostEntry> costs,String permission) {
        return new RiskProfile(id,enabled,"ratio",new BigDecimal(chance),new BigDecimal(fee),failure,costs,permission,Set.of());
    }
    private static BoostDefinition boost(String id,boolean enabled,String permission,Set<String> profiles,List<CostEntry> costs) {
        return new BoostDefinition(id,enabled,"luck",profiles,BigDecimal.ONE,new BigDecimal("5"),BoostDefinition.Protection.NONE,costs,permission,Set.of());
    }
    public static GuiPreviewService.Preview preview(Fixture f,GuiContext c,int pageSize) {
        return new GuiPreviewService().calculate(SESSION,c,Optional.of(f.source()),f.index(),f.access(),f.rules(),f.resources(),pageSize,NOW);
    }
    public static void main(String[] args) {
        var f=fixture(); var main=preview(f,GuiContext.initial(0),2);
        System.out.println("MAIN source=iron x1 value=90; recommended="+main.context().targetId()+"; status="+main.status());
        var catalog=preview(f,main.context().screen(GuiContext.Screen.CATALOG),2);
        System.out.println("CATALOG page=1/"+catalog.pages()+"; targets="+catalog.entries().stream().map(GuiPreviewService.Entry::id).toList());
        var second=preview(f,catalog.context().page(2),2);
        System.out.println("CATALOG page=2/"+second.pages()+"; targets="+second.entries().stream().map(GuiPreviewService.Entry::id).toList());
        var chosen=preview(f,main.context().target("diamond"),2).quote().orElseThrow().quote().orElseThrow();
        System.out.println("SELECT diamond: chance="+Decimals.display(chosen.chance().probability().percent())+"%; sourceSlot="+chosen.request().sourceSlot());
        var boosted=preview(f,main.context().target("diamond").boosts(List.of("lucky_shard")),2).quote().orElseThrow().quote().orElseThrow();
        System.out.println("BOOST lucky_shard: chance="+Decimals.display(boosted.chance().probability().percent())+"%; reserve emerald="+Decimals.display(boosted.terms().costs().lines().getFirst().reserve()));
        System.out.println("READ-ONLY: preview is not escrow, RNG outcome, reward or native GUI/server verification.");
    }
}
