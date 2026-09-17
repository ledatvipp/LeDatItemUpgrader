package vn.ledat.itemupgrader.paper.config;

import java.util.*;
import vn.ledat.itemupgrader.failure.FailurePolicy;
import vn.ledat.itemupgrader.item.ItemKey;
import vn.ledat.itemupgrader.output.OutputRules;
import vn.ledat.itemupgrader.transfer.*;

/** Strict parse of full policies. Unknown/missing references reject the candidate runtime, not the active one. */
final class OutputConfigLoader {
    private final RegistrySnapshot registry;
    OutputConfigLoader(RegistrySnapshot registry){this.registry=registry;}
    OutputRules load(YamlNode root,Set<ItemKey> identities) {
        root.allow("config-version","default-transfer","transfer-policies","failure-policies","profile-failures","path-transfers");root.integer("config-version",1,1);
        Map<String,TransferPolicy> transfers=new HashMap<>();
        for(var node:root.sections("transfer-policies")){
            node.allow("id","custom-name","repair-cost","durability","enchantments","pdc");
            Map<String,Integer> enchants=new HashMap<>();
            for(var entry:node.sections("enchantments")){
                entry.allow("key","maximum-level");String key=entry.string("key");
                if(!registry.enchantments().contains(key))throw new IllegalArgumentException(entry.at("key")+": unknown enchantment");
                duplicate(enchants,key,entry.integer("maximum-level",1,255),entry.at("key"));
            }
            Map<String,PdcValue.Type> pdc=new HashMap<>();
            for(var entry:node.sections("pdc")){
                entry.allow("key","type");duplicate(pdc,entry.string("key"),enumeration(entry,"type",PdcValue.Type.class),entry.at("key"));
            }
            var policy=new TransferPolicy(node.string("id"),node.bool("custom-name"),node.bool("repair-cost"),enumeration(node,"durability",TransferPolicy.Durability.class),enchants,pdc);
            duplicate(transfers,policy.id(),policy,node.at("id"));
        }
        if(transfers.isEmpty()||transfers.size()>128)throw new IllegalArgumentException("transfer policies require 1..128 entries");
        Map<String,FailurePolicy> failures=new HashMap<>();
        for(var node:root.sections("failure-policies")){
            FailurePolicy policy=switch(node.string("type")){
                case "DESTROY"->{node.allow("id","type");yield new FailurePolicy.Destroy();}
                case "KEEP"->{node.allow("id","type");yield new FailurePolicy.Keep();}
                case "DAMAGE"->{node.allow("id","type","basis-points","on-break");yield new FailurePolicy.Damage(node.integer("basis-points",1,10000),enumeration(node,"on-break",FailurePolicy.BreakBehavior.class));}
                case "DOWNGRADE"->{
                    node.allow("id","type","item","amount","transfer");var key=ItemKey.of(node.string("item"));registry.validate(key,node.at("item"));identities.add(key);
                    yield new FailurePolicy.Downgrade(key,node.integer("amount",1,64),reference(transfers,node.string("transfer"),node.at("transfer")));
                }
                default->throw new IllegalArgumentException(node.at("type")+": expected DESTROY/KEEP/DAMAGE/DOWNGRADE");
            };
            vn.ledat.itemupgrader.transaction.model.AttemptPlan.id(node.string("id"));
            duplicate(failures,node.string("id"),policy,node.at("id"));
        }
        if(failures.size()>128)throw new IllegalArgumentException("too many failure policies");
        Map<String,FailurePolicy> profiles=new HashMap<>();
        for(var node:root.sections("profile-failures")){
            node.allow("profile","failure");duplicate(profiles,node.string("profile"),reference(failures,node.string("failure"),node.at("failure")),node.at("profile"));
        }
        Map<String,TransferPolicy> paths=new HashMap<>();
        for(var node:root.sections("path-transfers")){
            node.allow("path","transfer");duplicate(paths,node.string("path"),reference(transfers,node.string("transfer"),node.at("transfer")),node.at("path"));
        }
        return new OutputRules(reference(transfers,root.string("default-transfer"),root.at("default-transfer")),paths,profiles);
    }
    private static <T>T reference(Map<String,T> map,String id,String path){T value=map.get(id);if(value==null)throw new IllegalArgumentException(path+": unknown reference "+id);return value;}
    private static <T>void duplicate(Map<String,T> map,String id,T value,String path){if(map.putIfAbsent(id,value)!=null)throw new IllegalArgumentException(path+": duplicate id");}
    private static <E extends Enum<E>>E enumeration(YamlNode node,String key,Class<E> type){try{return Enum.valueOf(type,node.string(key));}catch(IllegalArgumentException e){throw new IllegalArgumentException(node.at(key)+": invalid "+type.getSimpleName(),e);}}
}
