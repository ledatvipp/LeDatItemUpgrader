package vn.ledat.itemupgrader.paper.message;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import vn.ledat.itemupgrader.runtime.RuntimeStore;
import vn.ledat.itemupgrader.runtime.UpgraderRuntime;

/** Config keys retain {placeholder}; dynamic text uses unparsed MiniMessage resolvers, never recursive replace. */
public final class Messages {
    private static final Pattern PARAMETER = Pattern.compile("\\{([a-zA-Z0-9_-]+)\\}");
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final RuntimeStore<UpgraderRuntime> runtime;
    public Messages(RuntimeStore<UpgraderRuntime> runtime) { this.runtime = runtime; }
    public void send(CommandSender sender, String key) { send(sender, key, Map.of()); }
    public void send(CommandSender sender, String key, Map<String, String> parameters) { sender.sendMessage(component(key, parameters)); }
    public Component component(String key, Map<String, String> parameters) {
        Map<String, String> catalog = runtime.snapshot().map(snapshot -> snapshot.value().messages()).orElse(DefaultMessages.ALL);
        String template = catalog.getOrDefault(key, DefaultMessages.ALL.get("missing-message"));
        Map<String, String> supplied = new HashMap<>(parameters); supplied.putIfAbsent("key", key);
        return render(template, supplied, catalog);
    }
    public Component template(String trustedAdminTemplate, Map<String,String> parameters) {
        return render(trustedAdminTemplate, parameters, runtime.snapshot().map(s -> s.value().messages()).orElse(DefaultMessages.ALL));
    }
    private Component render(String template, Map<String,String> supplied, Map<String,String> catalog) {
        Matcher matcher = PARAMETER.matcher(template);
        StringBuilder safeTemplate = new StringBuilder();
        TagResolver.Builder tags = TagResolver.builder();
        int index = 0;
        while (matcher.find()) {
            String name = matcher.group(1);
            String tag = "upgrader_param_" + index++;
            if (name.equals("prefix")) tags.resolver(Placeholder.component(tag,
                    miniMessage.deserialize(catalog.getOrDefault("prefix", DefaultMessages.ALL.get("prefix")))));
            else tags.resolver(Placeholder.unparsed(tag, sanitize(supplied.getOrDefault(name, "{" + name + "}"))));
            matcher.appendReplacement(safeTemplate, Matcher.quoteReplacement("<" + tag + ">"));
        }
        matcher.appendTail(safeTemplate);
        return miniMessage.deserialize(safeTemplate.toString(), tags.build());
    }
    private static String sanitize(String value) {
        String bounded = value.length() > 512 ? value.substring(0, 512) : value;
        return bounded.replaceAll("[\\p{Cntrl}&&[^\\t]]", " ");
    }
}
