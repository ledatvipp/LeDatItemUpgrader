package vn.ledat.itemupgrader.paper.config;

import java.time.Duration;
import java.util.HashMap;
import vn.ledat.itemupgrader.gui.GuiSettings;

/** Strict config; source custody and live transaction flags cannot be enabled through GUI settings. */
final class GuiConfigLoader {
    private GuiConfigLoader() {}
    static GuiSettings settings(YamlNode root, RegistrySnapshot registry) {
        root.allow("config-version","enabled","source-mode","close-behavior","maximum-sessions","idle-timeout-seconds",
                "request-timeout-seconds","click-cooldown-ms","open-cooldown-ms","maximum-icon-bytes","sounds");
        root.integer("config-version",1,1);
        if(!root.string("source-mode").equals("REFERENCE_ONLY") || !root.string("close-behavior").equals("DISCARD_SELECTION"))
            throw new IllegalArgumentException("menus/settings.yml: only REFERENCE_ONLY + DISCARD_SELECTION is available before native escrow/delivery");
        var sounds=new HashMap<String,GuiSettings.Cue>();
        root.section("sounds").entries().forEach((id,raw)->{
            var n=YamlNode.from(raw,"menus.settings.sounds."+id); n.allow("key","volume","pitch");
            String key=n.string("key");
            if(!key.isEmpty()&&!registry.sounds().contains(key)) throw new IllegalArgumentException(n.at("key")+": unknown sound");
            sounds.put(id,new GuiSettings.Cue(key,n.decimal("volume").floatValue(),n.decimal("pitch").floatValue()));
        });
        return new GuiSettings(root.bool("enabled"),root.integer("maximum-sessions",1,256),
                Duration.ofSeconds(root.integer("idle-timeout-seconds",30,1800)),Duration.ofSeconds(root.integer("request-timeout-seconds",1,30)),
                Duration.ofMillis(root.integer("click-cooldown-ms",100,3000)),Duration.ofMillis(root.integer("open-cooldown-ms",250,10000)),
                root.integer("maximum-icon-bytes",1024,65536),sounds);
    }
}
