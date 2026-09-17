package vn.ledat.itemupgrader.demo;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import vn.ledat.itemupgrader.catalog.*;
import vn.ledat.itemupgrader.chance.*;
import vn.ledat.itemupgrader.cost.*;
import vn.ledat.itemupgrader.item.*;
import vn.ledat.itemupgrader.output.OutputRules;
import vn.ledat.itemupgrader.pity.*;
import vn.ledat.itemupgrader.profile.RiskProfile;
import vn.ledat.itemupgrader.quote.*;
import vn.ledat.itemupgrader.value.ValueDefinitions;

/** Detached demonstration only: no platform calls, RNG, history insert, debit, escrow or reward. */
public final class ProgressDemo {
    public static final UUID PLAYER=new UUID(0,1),SESSION=new UUID(0,2);
    public static final Instant NOW=Instant.parse("2026-09-17T00:00:00Z");
    public record Fixture(ItemSnapshot source,CatalogIndex index,CatalogAccess access,UpgradeRules rules,
                          QuoteRequest request,ResourceSnapshot resources,PityPolicies policies) {
        public QuoteResult quote(long version,long failures) {
            return new PityQuoteService().quote(request,source,index,access,rules,resources,NOW,policies,
                    (player,scope)->new PitySnapshot(player,scope,version,failures));
        }
    }
    private ProgressDemo(){}
    public static Fixture fixture() {
        var iron=ItemKey.of("minecraft:iron_ingot");var diamond=ItemKey.of("minecraft:diamond");
        var target=new TargetDefinition("diamond",diamond,1,"Diamond","materials",Set.of(),true,0,"",Set.of(),Set.of());
        var path=new UpgradePath("iron_progression",Set.of(iron),10,UpgradePath.Mode.LOCKED,List.of("diamond"),true,"",Set.of());
        var catalog=new CatalogDefinitions(CatalogSettings.defaults(),List.of(target),List.of(path));
        var values=ValueDefinitions.manualOnly(Map.of(iron,new BigDecimal("90"),diamond,new BigDecimal("900")));
        var source=new ItemSnapshot(ItemFacts.clean(iron,1),new byte[]{1,2,3});
        var template=new ItemSnapshot(ItemFacts.clean(diamond,1),new byte[]{4,5,6});
        var index=CatalogIndex.build(1,catalog,values,Map.of("diamond",TargetProbe.verified(template)));
        var profile=new RiskProfile("standard",true,"ratio",BigDecimal.ONE,BigDecimal.ONE,RiskProfile.FailureMode.DESTROY,List.of(),"",Set.of());
        var rules=new UpgradeRules(catalog,QuoteSettings.defaults(),Map.of("ratio",new ChanceFormula.Ratio(new BigDecimal("0.9"))),
                List.of(profile),List.of(),List.of(),List.of(),List.of(),Optional.of(OutputRules.defaults()));
        var policy=new PityPolicy("diamond_progress",1,Set.of(path.id()),Set.of(target.id()),Set.of(profile.id()),
                new BigDecimal("90"),new BigDecimal("1.25"),new BigDecimal("10"));
        return new Fixture(source,index,new CatalogAccess(PLAYER,Set.of(),Set.of()),rules,
                new QuoteRequest(SESSION,target.id(),profile.id(),List.of(),0),ResourceSnapshot.empty(PLAYER,0),new PityPolicies(true,List.of(policy)));
    }
    public static void main(String[] args) {
        var f=fixture();String scope="";
        for(int failures:List.of(0,1,4,8,100)) {
            var q=f.quote(failures,failures).quote().orElseThrow();var stamp=q.terms().pity().orElseThrow();scope=stamp.scope();
            System.out.println("SYNTHETIC PREVIEW failures="+failures+"; points="+stamp.bonusPoints().toPlainString()
                    +"; chance="+q.terms().probability().percent().toPlainString()+"%; state=NOT_RESERVED");
        }
        var old=f.quote(4,4).quote().orElseThrow();
        var validation=new PityQuoteService().revalidate(old,SESSION,f.source(),f.index(),f.access(),f.rules(),f.resources(),NOW,
                f.policies(),(p,s)->new PitySnapshot(p,s,5,5));
        System.out.println("STALE VERSION: "+validation.status()+"; scope="+scope);
        var won=new PitySnapshot(PLAYER,scope,4,4).complete(true);
        System.out.println("PURE COUNTER MODEL after completed win: version="+won.version()+"; failures="+won.failures());
        System.out.println("Pity config remains DISABLED in Paper. No transaction or SQL write occurred in this demo.");
    }
}
