package vn.ledat.itemupgrader.paper.history;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.inventory.Inventory;
import vn.ledat.itemupgrader.history.*;
import vn.ledat.itemupgrader.pity.PitySnapshot;
import vn.ledat.itemupgrader.gui.MenuDefinition;
import vn.ledat.itemupgrader.paper.gui.PaperGuiScheduler;
import vn.ledat.itemupgrader.paper.message.Messages;
import vn.ledat.itemupgrader.paper.platform.PlatformAccess;
import vn.ledat.itemupgrader.paper.storage.PlatformHistoryStore;
import vn.ledat.itemupgrader.runtime.*;
/** Native source, Paper-owner-thread lifecycle. No transaction, refund, claim, draw or retention mutation endpoint. */
public final class HistoryUiService {
    private final JavaPlugin owner;private final PlatformAccess platform;private final RuntimeStore<UpgraderRuntime> runtime;
    private final Messages messages;private final PlatformHistoryStore store;private final PaperGuiScheduler scheduler;private final HistoryRenderer renderer;
    private final Map<UUID,HistoryHolder> holders=new HashMap<>();private final Set<UUID> waiting=new HashSet<>();
    private HistorySessionStore sessions;private HistorySettings settings;private long revision=-1;private volatile boolean stopped;
    private volatile StatisticsCache statistics;private volatile boolean placeholderEnabled;private boolean storageReadable;private Instant lastWarning=Instant.EPOCH;
    public HistoryUiService(JavaPlugin owner,PlatformAccess platform,RuntimeStore<UpgraderRuntime> runtime,Messages messages,PlatformHistoryStore store) {
        this.owner=owner;this.platform=platform;this.runtime=runtime;this.messages=messages;this.store=Objects.requireNonNull(store);
        this.scheduler=new PaperGuiScheduler(owner,platform);this.renderer=new HistoryRenderer(messages);
    }
    public void start() {
        platform.registerHistoryPlaceholders((player,key)->{
            var cache=statistics;return !stopped&&placeholderEnabled&&store.available()&&cache!=null?cache.placeholder(player,key,Instant.now()):"";
        });
        scheduler.sweep(this::sweep);
    }
    private Optional<HistorySettings> current() {
        if(stopped)return Optional.empty();var snapshot=runtime.snapshot();
        if(snapshot.isEmpty()||snapshot.orElseThrow().value().history().isEmpty())return Optional.empty();
        var value=snapshot.orElseThrow();
        if(value.revision()!=revision) {
            clearViews();revision=value.revision();settings=value.value().history().orElseThrow();
            if(statistics!=null)statistics.close();statistics=new StatisticsCache(settings.cacheSize(),settings.cacheTtl());
            sessions=new HistorySessionStore(settings.maximumSessions(),settings.maximumPages(),settings.requestTimeout(),settings.sessionTimeout());
            placeholderEnabled=settings.enabled()&&settings.placeholdersEnabled();
        }
        boolean available=store.available();
        if(storageReadable!=available){clearViews();if(statistics!=null)statistics.close();statistics=new StatisticsCache(settings.cacheSize(),settings.cacheTtl());sessions=new HistorySessionStore(settings.maximumSessions(),settings.maximumPages(),settings.requestTimeout(),settings.sessionTimeout());}
        storageReadable=available;placeholderEnabled=settings.enabled()&&settings.placeholdersEnabled()&&available;
        return settings.enabled()&&available?Optional.of(settings):Optional.empty();
    }
    private String unavailableKey() {
        return runtime.snapshot().flatMap(v->v.value().history()).map(c->c.enabled()?"history-storage-unavailable":"history-disabled").orElse("history-disabled");
    }
    private boolean allowed(Player p,UUID subject) {
        return p.hasPermission("ledatitemupgrader.use")&&p.hasPermission("ledatitemupgrader.history")
                &&(p.getUniqueId().equals(subject)||p.hasPermission("ledatitemupgrader.admin.history"));
    }
    public void open(Player p,UUID subject,HistoryQuery.Filter filter) {
        if(!allowed(p,subject)){messages.send(p,"no-permission");return;}
        var config=current();if(config.isEmpty()){messages.send(p,unavailableKey());return;}
        if(!p.getItemOnCursor().getType().isAir()){messages.send(p,"history-cursor-busy");return;}
        if(!platform.acquire(p.getUniqueId(),"history-open",Duration.ofSeconds(1))){messages.send(p,"cooldown");return;}
        var view=sessions.open(p.getUniqueId(),subject,revision,filter,settings.entrySlots().size(),Instant.now());
        if(view.isEmpty()){messages.send(p,"history-capacity");return;}
        var state=view.orElseThrow();var holder=new HistoryHolder(state.viewer(),state.session(),revision,settings.menu().size(),
                messages.template(settings.menu().title(),Map.of("player",subject.toString())));
        // Old close events are fenced by session identity, so they cannot remove this replacement.
        holders.put(state.viewer(),holder);renderer.render(holder,settings,state,"history-loading");p.openInventory(holder.getInventory());sound(p,"open");
        fetch(p,holder,HistorySessionStore.Navigation.FIRST);
    }
    private void fetch(Player p,HistoryHolder holder,HistorySessionStore.Navigation navigation) {
        if(current().isEmpty()||!matches(p,holder))return;
        var view=sessions.view(p.getUniqueId(),holder.session,revision,Instant.now());if(view.isEmpty())return;
        if(!allowed(p,view.orElseThrow().subject())){closeLater(p.getUniqueId(),holder);return;}
        if(!platform.acquire(p.getUniqueId(),"history-page",Duration.ofMillis(350))){messages.send(p,"cooldown");return;}
        var token=sessions.begin(p.getUniqueId(),holder.session,revision,navigation,Instant.now());
        if(token.isEmpty()){messages.send(p,"history-page-unavailable");return;}
        var request=token.orElseThrow();waiting.add(holder.session);
        renderer.render(holder,settings,sessions.view(request.viewer(),holder.session,revision,Instant.now()).orElseThrow(),"history-loading");
        store.page(request.query(),settings.maximumQueries()).whenComplete((page,error)-> {
            if(stopped)return;
            platform.player(request.viewer(),player->{
                if(current().isEmpty()||!matches(player,holder)||!allowed(player,request.query().playerId()))return;
                if(error!=null) {
                    if(sessions.failed(request,revision,Instant.now())) {
                        waiting.remove(holder.session);warn(error);sound(player,"error");messages.send(player,"history-unavailable");
                        sessions.view(request.viewer(),holder.session,revision,Instant.now()).ifPresent(v->renderer.render(holder,settings,v,"history-unavailable"));
                    }
                    return;
                }
                var accepted=sessions.complete(request,page,revision,Instant.now());
                if(accepted.isPresent()) {waiting.remove(holder.session);renderer.render(holder,settings,accepted.orElseThrow(),page.rows().isEmpty()?"history-empty":"history-ready");}
            });
        });
    }
    /** Listener has already cancelled inventory interaction; only declared top navigation is dispatched next tick. */
    public void click(Player p,HistoryHolder holder,int rawSlot) {
        if(current().isEmpty()||!matches(p,holder)||rawSlot<0||rawSlot>=settings.menu().size())return;
        var element=settings.menu().slots().get(rawSlot);var action=element.action();
        if(action==MenuDefinition.Action.NONE)return;
        sound(p,"click");UUID id=p.getUniqueId();scheduler.next(id,player->{
            if(stopped||!matches(player,holder))return;
            if(action==MenuDefinition.Action.CLOSE){player.closeInventory();return;}
            var navigation=switch(action){case NEXT_PAGE->HistorySessionStore.Navigation.NEXT;case PREVIOUS_PAGE->HistorySessionStore.Navigation.PREVIOUS;
                case REFRESH->HistorySessionStore.Navigation.REFRESH;default->null;};
            if(navigation!=null)fetch(player,holder,navigation);
        });
    }
    public void closed(Player p,HistoryHolder holder) {
        if(!stopped)sound(p,"close");holders.remove(p.getUniqueId(),holder);waiting.remove(holder.session);
        if(sessions!=null)sessions.close(p.getUniqueId(),holder.session);
    }
    public void quit(UUID id) {
        var h=holders.remove(id);if(h!=null)waiting.remove(h.session);if(sessions!=null)sessions.quit(id);
        var cache=statistics;if(cache!=null)cache.invalidate(id);
    }
    public void statistics(Player p) {
        if(!allowed(p,p.getUniqueId())){messages.send(p,"no-permission");return;}
        if(current().isEmpty()){messages.send(p,unavailableKey());return;}
        if(!platform.acquire(p.getUniqueId(),"history-stats",Duration.ofSeconds(2))){messages.send(p,"cooldown");return;}
        var cache=statistics;var id=p.getUniqueId();var ticket=cache.begin(id);
        if(ticket.isEmpty()){messages.send(p,"history-capacity");return;}
        var request=ticket.orElseThrow();long configRevision=revision;Instant deadline=Instant.now().plus(settings.requestTimeout());
        store.statistics(id,settings.maximumQueries()).whenComplete((value,error)-> {
            if(error!=null){cache.failed(request);if(!stopped)platform.player(id,player->{if(current().isPresent()&&revision==configRevision&&allowed(player,id)){warn(error);sound(player,"error");messages.send(player,"history-unavailable");}});return;}
            if(stopped){cache.failed(request);return;}
            platform.player(id,player->{
                if(current().isEmpty()||revision!=configRevision||!allowed(player,id)||!Instant.now().isBefore(deadline)){cache.failed(request);return;}
                if(cache.complete(request,value,Instant.now()))messages.send(player,"history-statistics",Map.of("completed",Long.toString(value.completed()),
                        "wins",Long.toString(value.wins()),"losses",Long.toString(value.losses()),"rate",value.winRate().toPlainString()));
            });
        });
    }
    public void diagnostic(Player p,UUID transaction) {
        if(!p.hasPermission("ledatitemupgrader.admin.diagnostics")){messages.send(p,"no-permission");return;}
        if(current().isEmpty()){messages.send(p,unavailableKey());return;}
        if(!platform.acquire(p.getUniqueId(),"history-diagnostic",Duration.ofSeconds(2))){messages.send(p,"cooldown");return;}
        UUID id=p.getUniqueId();long configRevision=revision;Instant deadline=Instant.now().plus(settings.requestTimeout());
        store.diagnostic(transaction,settings.maximumQueries()).whenComplete((record,error)->{
            if(stopped)return;platform.player(id,player->{
                if(current().isEmpty()||revision!=configRevision||!player.hasPermission("ledatitemupgrader.admin.diagnostics")||!Instant.now().isBefore(deadline))return;
                if(error!=null){warn(error);sound(player,"error");messages.send(player,"history-unavailable");return;}
                if(record.isEmpty()){messages.send(player,"history-attempt-not-found");return;}var d=record.orElseThrow();
                messages.send(player,"history-diagnostic",Map.of("transaction",d.transactionId().toString(),"player",d.playerId().toString(),
                    "state",d.state().name(),"version",Long.toString(d.version()),"outcome",d.outcome().name(),"reason",d.reason(),
                    "steps",Integer.toString(d.totalSteps()),"applied",Integer.toString(d.appliedSteps()),"pending",d.pendingEffect().map(Enum::name).orElse("-")));
                messages.send(player,"history-diagnostic-readonly");
            });
        });
    }
    public void pity(Player p,String scope) {
        if(!p.hasPermission("ledatitemupgrader.admin.diagnostics")){messages.send(p,"no-permission");return;}
        if(scope.isEmpty()){messages.send(p,"history-pity-gated");return;}
        PitySnapshot.validateScope(scope);
        if(current().isEmpty()){messages.send(p,unavailableKey());return;}
        if(!platform.acquire(p.getUniqueId(),"history-pity",Duration.ofSeconds(2))){messages.send(p,"cooldown");return;}
        UUID id=p.getUniqueId();long configRevision=revision;Instant deadline=Instant.now().plus(settings.requestTimeout());
        store.pity(id,scope,settings.maximumQueries()).whenComplete((snapshot,error)->{
            if(stopped)return;platform.player(id,player->{
                if(current().isEmpty()||revision!=configRevision||!player.hasPermission("ledatitemupgrader.admin.diagnostics")||!Instant.now().isBefore(deadline))return;
                if(error!=null){warn(error);sound(player,"error");messages.send(player,"history-unavailable");return;}
                messages.send(player,"history-pity-snapshot",Map.of("scope",scope,"version",Long.toString(snapshot.version()),"failures",Long.toString(snapshot.failures())));
            });
        });
    }
    private void sweep() {
        if(current().isEmpty())return;sessions.sweep(Instant.now());
        for(var holder:List.copyOf(holders.values()))platform.player(holder.viewer,p->{
            if(stopped||!matches(p,holder))return;var view=sessions.view(holder.viewer,holder.session,revision,Instant.now());
            if(view.isEmpty()||!allowed(p,view.orElseThrow().subject())){closeLater(holder.viewer,holder);return;}
            if(waiting.contains(holder.session)&&!view.orElseThrow().busy()) {
                waiting.remove(holder.session);renderer.render(holder,settings,view.orElseThrow(),"history-request-expired");
            }
        });
    }
    private boolean matches(Player p,HistoryHolder h) {
        Inventory top=p.getOpenInventory().getTopInventory();return h.viewer.equals(p.getUniqueId())&&top==h.getInventory()&&top.getHolder()==h&&holders.get(h.viewer)==h;
    }
    private void closeLater(UUID id,HistoryHolder h){scheduler.next(id,p->{if(matches(p,h))p.closeInventory();});}
    private void clearViews() {
        if(sessions!=null)sessions.shutdown();
        for(var h:List.copyOf(holders.values()))scheduler.next(h.viewer,p->{if(p.getOpenInventory().getTopInventory()==h.getInventory())p.closeInventory();});
        holders.clear();waiting.clear();
    }
    private void sound(Player player,String id) {
        if(settings==null)return;var cue=settings.sounds().get(id);
        if(cue!=null&&!cue.key().isEmpty())player.playSound(player.getLocation(),cue.key(),org.bukkit.SoundCategory.MASTER,cue.volume(),cue.pitch());
    }
    private void warn(Throwable error) {
        Instant now=Instant.now();if(now.isBefore(lastWarning.plusSeconds(30)))return;lastWarning=now;
        Throwable root=error;
        for(int i=0;i<8&&(root instanceof java.util.concurrent.CompletionException||root instanceof java.util.concurrent.ExecutionException)&&root.getCause()!=null;i++)root=root.getCause();
        String details=root.getClass().getName();
        if(root instanceof java.sql.SQLException sql) {
            String state=sql.getSQLState();
            details+=" sqlState="+(state!=null&&state.matches("[A-Za-z0-9]{1,8}")?state:"unknown")+" vendorCode="+sql.getErrorCode();
        }
        // Driver messages can contain connection strings or credentials. Keep types/codes and source frames, not raw messages.
        String frames=Arrays.stream(root.getStackTrace()).limit(8).map(StackTraceElement::toString).collect(Collectors.joining("\n  at "));
        owner.getLogger().warning("History read unavailable; no transaction modified; "+details+"\n  at "+frames+"\nFurther warnings throttled for 30 seconds.");
    }
    public void close() {
        if(stopped)return;stopped=true;placeholderEnabled=false;if(statistics!=null)statistics.close();if(sessions!=null)sessions.shutdown();
        // Disable runs on the Paper owner thread. No delayed task is needed for already-open read-only menus.
        for(var h:List.copyOf(holders.values())){var p=owner.getServer().getPlayer(h.viewer);if(p!=null&&p.getOpenInventory().getTopInventory()==h.getInventory())p.closeInventory();}
        holders.clear();waiting.clear();scheduler.close();platform.unregisterPlaceholders();
    }
}
