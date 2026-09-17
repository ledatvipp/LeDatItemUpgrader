package vn.ledat.itemupgrader.paper.command;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import vn.ledat.itemupgrader.catalog.CatalogArguments;
import vn.ledat.itemupgrader.catalog.CatalogQuery;
import vn.ledat.itemupgrader.quote.QuoteArguments;
import vn.ledat.itemupgrader.quote.QuoteRequest;
import vn.ledat.itemupgrader.paper.service.QuotePreviewService;
import vn.ledat.itemupgrader.paper.message.Messages;
import vn.ledat.itemupgrader.paper.platform.PlatformAccess;
import vn.ledat.itemupgrader.paper.service.CatalogPreviewService;
import vn.ledat.itemupgrader.paper.service.ConfigurationService;
import vn.ledat.itemupgrader.paper.service.InspectionService;
import vn.ledat.itemupgrader.runtime.RuntimeStore;
import vn.ledat.itemupgrader.runtime.UpgraderRuntime;

/** Thin native adapter: permission/input checks then read-only services. No pricing, inventory writes or SQL. */
public final class UpgraderCommand implements TabExecutor {
    private static final Map<String, String> PERMISSIONS = Map.ofEntries(
            Map.entry("storage", "ledatitemupgrader.admin.storage"), Map.entry("help", "ledatitemupgrader.use"), Map.entry("menu", "ledatitemupgrader.use"), Map.entry("status", "ledatitemupgrader.admin.status"),
            Map.entry("value", "ledatitemupgrader.admin.value"), Map.entry("inspect", "ledatitemupgrader.admin.inspect"),
            Map.entry("reload", "ledatitemupgrader.admin.reload"), Map.entry("catalog", "ledatitemupgrader.admin.catalog"),
            Map.entry("recommend", "ledatitemupgrader.admin.catalog"), Map.entry("paths", "ledatitemupgrader.admin.catalog"),
            Map.entry("quote", "ledatitemupgrader.admin.quote"), Map.entry("profiles", "ledatitemupgrader.admin.quote"),
            Map.entry("history", "ledatitemupgrader.history"), Map.entry("statistics", "ledatitemupgrader.history"),
            Map.entry("diagnose", "ledatitemupgrader.admin.diagnostics"), Map.entry("pity", "ledatitemupgrader.admin.diagnostics"),
            Map.entry("boosts", "ledatitemupgrader.admin.quote"), Map.entry("animation", "ledatitemupgrader.admin.animation"));
    private final vn.ledat.itemupgrader.paper.history.HistoryUiService history;
    private final vn.ledat.itemupgrader.paper.storage.PlatformStorageService storage;
    private final RuntimeStore<UpgraderRuntime> runtime;
    private final Messages messages;
    private final ConfigurationService configs;
    private final InspectionService inspections;
    private final CatalogPreviewService catalogs;
    private final QuotePreviewService quotes;
    private final PlatformAccess platform;
    private final vn.ledat.itemupgrader.paper.animation.InventoryAnimationService animations;
    private final vn.ledat.itemupgrader.paper.gui.InventoryGuiService guis;
    public UpgraderCommand(RuntimeStore<UpgraderRuntime> runtime, Messages messages, ConfigurationService configs,
                           InspectionService inspections, CatalogPreviewService catalogs, QuotePreviewService quotes, PlatformAccess platform, vn.ledat.itemupgrader.paper.gui.InventoryGuiService guis, vn.ledat.itemupgrader.paper.animation.InventoryAnimationService animations, vn.ledat.itemupgrader.paper.history.HistoryUiService history, vn.ledat.itemupgrader.paper.storage.PlatformStorageService storage) {
        this.runtime = runtime; this.messages = messages; this.configs = configs; this.inspections = inspections;
        this.catalogs = catalogs; this.quotes = quotes; this.platform = platform; this.guis=guis; this.animations=animations; this.history=history; this.storage=storage;
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("ledatitemupgrader.use")) { messages.send(sender, "no-permission"); return true; }
        if (args.length > 4 || Arrays.stream(args).anyMatch(value -> value.length() > 600 || value.codePoints().anyMatch(Character::isISOControl))) {
            messages.send(sender, "invalid-usage"); return true;
        }
        if (args.length == 0) { if(sender instanceof Player player) guis.open(player); else messages.send(sender,"player-only"); return true; }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String permission = PERMISSIONS.get(sub);
        if (permission == null || (!List.of("catalog", "quote", "profiles", "boosts", "animation", "history", "diagnose", "pity", "storage").contains(sub) && args.length != 1)) { messages.send(sender, "invalid-usage"); return true; }
        if (!sender.hasPermission(permission)) { messages.send(sender, "no-permission"); return true; }
        switch (sub) {
            case "storage" -> {
                final vn.ledat.itemupgrader.storage.management.StorageArguments request;
                try { request=vn.ledat.itemupgrader.storage.management.StorageArguments.parse(Arrays.asList(args).subList(1,args.length)); }
                catch(IllegalArgumentException invalid){messages.send(sender,"storage-invalid-arguments");return true;}
                if(!(sender instanceof Player)&&!(sender instanceof org.bukkit.command.ConsoleCommandSender)) {
                    messages.send(sender,"storage-sender-only");return true;
                }
                switch(request.action()) {
                    case STATUS -> {
                        var state=storage.status();
                        messages.send(sender,"storage-status",Map.of("revision",Long.toString(state.revision()),"state",state.state().name(),
                            "reason",state.reason().name(),"busy",Boolean.toString(state.busy()),"dialect",state.schema().map(s->s.dialect().name()).orElse("-"),
                            "tables",state.schema().map(s->Integer.toString(s.tables())).orElse("0"),"pruned",Integer.toString(state.lastPruned())));
                        messages.send(sender,"storage-readonly-boundary");
                    }
                    case RECHECK -> messages.send(sender,storage.recheck()?"storage-check-started":"storage-busy");
                    case RECOVERY -> storage.recovery(sender instanceof Player p?p.getUniqueId():null,request.after());
                }
            }
            case "history" -> {
                if(!(sender instanceof Player player)){messages.send(sender,"player-only");return true;}
                java.util.UUID subject=player.getUniqueId();var filter=vn.ledat.itemupgrader.history.HistoryQuery.Filter.ALL;
                try {
                    if(args.length>3)throw new IllegalArgumentException("arguments");
                    if(args.length>=2) {
                        if(List.of("all","win","loss","unfinished").contains(args[1].toLowerCase(Locale.ROOT))) {
                            if(args.length!=2)throw new IllegalArgumentException("arguments");
                            filter=vn.ledat.itemupgrader.history.HistoryQuery.Filter.valueOf(args[1].toUpperCase(Locale.ROOT));
                        } else {
                            if(!sender.hasPermission("ledatitemupgrader.admin.history")){messages.send(sender,"no-permission");return true;}
                            subject=strictUuid(args[1]);
                            if(args.length==3)filter=vn.ledat.itemupgrader.history.HistoryQuery.Filter.valueOf(args[2].toUpperCase(Locale.ROOT));
                        }
                    }
                }catch(IllegalArgumentException invalid){messages.send(sender,"history-invalid-arguments");return true;}
                history.open(player,subject,filter);
            }
            case "statistics" -> {if(sender instanceof Player player)history.statistics(player);else messages.send(sender,"player-only");}
            case "diagnose" -> {
                if(!(sender instanceof Player player)){messages.send(sender,"player-only");return true;}
                try{if(args.length!=2)throw new IllegalArgumentException("arguments");history.diagnostic(player,strictUuid(args[1]));}
                catch(IllegalArgumentException invalid){messages.send(sender,"history-invalid-arguments");}
            }
            case "pity" -> {
                if(!(sender instanceof Player player)){messages.send(sender,"player-only");return true;}
                if(args.length>2||args.length==2&&!args[1].matches("[a-f0-9]{64}")){messages.send(sender,"history-invalid-arguments");return true;}
                history.pity(player,args.length==2?args[1]:"");
            }
            case "animation" -> {
                if (!(sender instanceof Player player)) { messages.send(sender, "player-only"); return true; }
                if (args.length < 2 || args.length > 3 || !List.of("win", "loss").contains(args[1].toLowerCase(Locale.ROOT))
                        || args.length == 3 && !args[2].matches("[a-z0-9][a-z0-9_-]{0,31}")) {
                    messages.send(sender, "animation-invalid-arguments"); return true;
                }
                animations.preview(player, vn.ledat.itemupgrader.animation.AnimationRequest.Outcome.valueOf(args[1].toUpperCase(Locale.ROOT)),
                        args.length == 3 ? args[2] : "");
            }
            case "menu" -> { if(sender instanceof Player player) guis.open(player); else messages.send(sender,"player-only"); }
            case "help" -> {
                messages.send(sender, "help-header");
                PERMISSIONS.keySet().stream().sorted().filter(key -> !key.equals("help") && sender.hasPermission(PERMISSIONS.get(key)))
                        .forEach(key -> messages.send(sender, "help-" + key));
            }
            case "status" -> messages.send(sender, "status", Map.of("state", runtime.state().name(),
                    "revision", runtime.snapshot().map(snapshot -> String.valueOf(snapshot.revision())).orElse("0"), "api", platform.apiVersion()));
            case "reload" -> {
                if (sender instanceof Player player && !platform.acquire(player.getUniqueId(), "reload", Duration.ofSeconds(2))) {
                    messages.send(sender, "cooldown"); return true;
                }
                configs.reload(sender instanceof Player player ? player.getUniqueId() : null);
            }
            case "value", "inspect" -> {
                if (!(sender instanceof Player player)) { messages.send(sender, "player-only"); return true; }
                inspections.inspect(player, sub.equals("inspect"));
            }
            case "catalog", "recommend", "paths" -> {
                if (!(sender instanceof Player player)) { messages.send(sender, "player-only"); return true; }
                var current = runtime.snapshot();
                if (current.isEmpty()) { messages.send(sender, "not-ready"); return true; }
                final CatalogQuery query;
                try { query = CatalogArguments.parse(Arrays.asList(args).subList(1, args.length), current.get().value().catalog().settings().pageSize()); }
                catch (IllegalArgumentException error) { messages.send(sender, "catalog-invalid-arguments"); return true; }
                catalogs.preview(player, CatalogPreviewService.Mode.valueOf(sub.toUpperCase(Locale.ROOT)), query);
            }
            case "quote" -> {
                if (!(sender instanceof Player player)) { messages.send(sender, "player-only"); return true; }
                final QuoteRequest request;
                try { request = QuoteArguments.parse(Arrays.asList(args).subList(1, args.length), java.util.UUID.randomUUID(), player.getInventory().getHeldItemSlot()); }
                catch (IllegalArgumentException invalid) { messages.send(sender, "quote-invalid-arguments"); return true; }
                quotes.preview(player, request);
            }
            case "profiles", "boosts" -> {
                if (!(sender instanceof Player player)) { messages.send(sender, "player-only"); return true; }
                int page = 1;
                if (args.length > 2 || (args.length == 2 && !args[1].matches("[1-9][0-9]{0,3}"))) { messages.send(sender, "quote-invalid-arguments"); return true; }
                if (args.length == 2) page = Integer.parseInt(args[1]);
                quotes.list(player, sub.equals("profiles"), page);
            }
            default -> messages.send(sender, "invalid-usage");
        }
        return true;
    }
    private static java.util.UUID strictUuid(String input) {
        if(!input.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"))throw new IllegalArgumentException("uuid");
        return java.util.UUID.fromString(input);
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("ledatitemupgrader.use") || args.length < 1 || args.length > 4
                || Arrays.stream(args).anyMatch(token -> token.length() > 600 || token.codePoints().anyMatch(Character::isISOControl))) return List.of();
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        Stream<String> choices;
        if (args.length == 1) choices = PERMISSIONS.entrySet().stream().filter(entry -> sender.hasPermission(entry.getValue())).map(Map.Entry::getKey);
        else if(args[0].equalsIgnoreCase("storage")&&sender.hasPermission("ledatitemupgrader.admin.storage")&&args.length==2) choices=Stream.of("status","recheck","recovery");
        else if(args[0].equalsIgnoreCase("history")&&sender.hasPermission("ledatitemupgrader.history")&&args.length<=3) {
            choices=args.length==2||sender.hasPermission("ledatitemupgrader.admin.history")?Stream.of("all","win","loss","unfinished"):Stream.empty();
        }
        else if (args[0].equalsIgnoreCase("animation") && sender.hasPermission("ledatitemupgrader.admin.animation")) {
            choices = switch (args.length) {
                case 2 -> Stream.of("win", "loss");
                case 3 -> runtime.snapshot().flatMap(snapshot -> snapshot.value().animation())
                        .map(config -> config.presets().keySet().stream()).orElse(Stream.empty());
                default -> Stream.empty();
            };
        }
        else if (args[0].equalsIgnoreCase("catalog") && sender.hasPermission("ledatitemupgrader.admin.catalog")) {
            choices = switch (args.length) {
                case 2 -> Stream.of("1", "2", "3");
                case 3 -> Stream.concat(Stream.of("all"), runtime.snapshot().map(snapshot -> snapshot.value().catalog().categories().stream()).orElse(Stream.empty()));
                case 4 -> Arrays.stream(CatalogQuery.Sort.values()).map(value -> value.name().toLowerCase(Locale.ROOT));
                default -> Stream.empty();
            };
        } else if (args[0].equalsIgnoreCase("quote") && sender.hasPermission("ledatitemupgrader.admin.quote")) {
            var current = runtime.snapshot();
            if (current.isEmpty()) return List.of();
            var definitions = current.orElseThrow().value();
            choices = switch (args.length) {
                case 2 -> definitions.catalog().targets().values().stream().filter(value -> value.enabled() && (value.permission().isEmpty() || sender.hasPermission(value.permission())))
                        .map(value -> value.id());
                case 3 -> Stream.concat(Stream.of("default"), definitions.upgradeRules().profiles().values().stream()
                        .filter(value -> value.enabled() && (value.permission().isEmpty() || sender.hasPermission(value.permission()))).map(value -> value.id()));
                case 4 -> Stream.concat(Stream.of("none"), definitions.upgradeRules().boosts().values().stream()
                        .filter(value -> value.enabled() && (value.permission().isEmpty() || sender.hasPermission(value.permission()))).map(value -> value.id()));
                default -> Stream.empty();
            };
        } else if ((args[0].equalsIgnoreCase("profiles") || args[0].equalsIgnoreCase("boosts")) && args.length == 2
                && sender.hasPermission("ledatitemupgrader.admin.quote")) choices = Stream.of("1", "2", "3");
        else return List.of();
        return choices.filter(value -> value.startsWith(prefix)).distinct().sorted().limit(50).toList();
    }
}
