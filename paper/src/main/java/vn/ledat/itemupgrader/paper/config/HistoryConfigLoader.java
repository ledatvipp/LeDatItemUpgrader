package vn.ledat.itemupgrader.paper.config;
import java.time.Duration;
import java.util.*;
import vn.ledat.itemupgrader.history.HistorySettings;
import vn.ledat.itemupgrader.pity.*;
import vn.ledat.itemupgrader.gui.MenuCompiler;
import vn.ledat.itemupgrader.quote.UpgradeRules;
/** Parsed on the configuration worker; pity cannot be switched on before native quote/commit wiring. */
final class HistoryConfigLoader {
    private HistoryConfigLoader(){}
    static HistorySettings load(YamlNode root,YamlNode pity,MenuCompiler.CompiledMenu menu,UpgradeRules rules,RegistrySnapshot registry) {
        root.allow("config-version","enabled","placeholders-enabled","maximum-sessions","maximum-queries","maximum-pages", "cache-size",
                "cache-ttl-seconds","request-timeout-seconds","session-timeout-seconds","retention-days","retention-batch","sounds","close-behavior");root.integer("config-version",1,1);
        if(!root.string("close-behavior").equals("DISCARD_VIEW"))throw new IllegalArgumentException("history.close-behavior: only DISCARD_VIEW; history owns no items");
        Map<String,vn.ledat.itemupgrader.gui.GuiSettings.Cue> sounds=new HashMap<>();
        root.section("sounds").entries().forEach((id,raw)->{
            var n=YamlNode.from(raw,"history.sounds."+id);n.allow("key","volume","pitch");String key=n.string("key");
            if(!key.isEmpty()&&!registry.sounds().contains(key))throw new IllegalArgumentException(n.at("key")+": unknown sound");
            sounds.put(id,new vn.ledat.itemupgrader.gui.GuiSettings.Cue(key,n.decimal("volume").floatValue(),n.decimal("pitch").floatValue()));
        });
        pity.allow("config-version","enabled","policies");pity.integer("config-version",1,1);
        List<PityPolicy> policies=new ArrayList<>();
        for(var n:pity.sections("policies")) {
            n.allow("id","epoch","paths","targets","profiles","minimum-source-value","increment-points","maximum-points");
            var p=new PityPolicy(n.string("id"),n.integer("epoch",1,1000000),set(n,"paths"),set(n,"targets"),set(n,"profiles"),
                    n.decimal("minimum-source-value"),n.decimal("increment-points"),n.decimal("maximum-points"));
            for(String target:p.targets())if(!rules.catalog().targets().containsKey(target))throw new IllegalArgumentException(n.at("targets")+": unknown target "+target);
            for(String profile:p.profiles())if(!rules.profiles().containsKey(profile))throw new IllegalArgumentException(n.at("profiles")+": unknown profile "+profile);
            // Path reference is validated against compiled path objects, not source key strings.
            for(String path:p.paths())if(rules.catalog().paths().stream().noneMatch(v->v.id().equals(path)))
                throw new IllegalArgumentException(n.at("paths")+": unknown path "+path);
            policies.add(p);
        }
        var compiled=new PityPolicies(pity.bool("enabled"),policies);
        if(compiled.enabled())throw new IllegalArgumentException("upgrades/pity.yml.enabled: native pity quote + atomic journal hook are not bootstrapped; keep false");
        return new HistorySettings(root.bool("enabled"),root.bool("placeholders-enabled"),root.integer("maximum-sessions",1,128),
            root.integer("maximum-queries",1,32),root.integer("maximum-pages",1,100),root.integer("cache-size",1,10000),
            Duration.ofSeconds(root.integer("cache-ttl-seconds",5,1800)),Duration.ofSeconds(root.integer("request-timeout-seconds",1,30)),
            Duration.ofSeconds(root.integer("session-timeout-seconds",30,600)),root.integer("retention-days",1,3650),root.integer("retention-batch",1,1000),menu,compiled,sounds);
    }
    private static Set<String> set(YamlNode n,String key) {
        var values=n.strings(key);var set=Set.copyOf(values);if(set.size()!=values.size())throw new IllegalArgumentException(n.at(key)+": duplicate id");return set;
    }
}
