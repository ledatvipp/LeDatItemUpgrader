package vn.ledat.itemupgrader.paper.gui;

import java.time.Instant;
import java.util.*;
import java.util.logging.Level;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import vn.ledat.itemupgrader.catalog.CatalogQuery;
import vn.ledat.itemupgrader.gui.*;
import vn.ledat.itemupgrader.paper.message.Messages;
import vn.ledat.itemupgrader.paper.platform.PlatformAccess;
import vn.ledat.itemupgrader.paper.service.AccessSnapshotService;
import vn.ledat.itemupgrader.runtime.*;

/** Owner-thread UI coordinator. Read-only slot references; live transaction execution is deliberately absent. */
public final class InventoryGuiService {
    private static final class View {
        final UpgraderGuiHolder holder;
        Map<Integer,ItemStack> last=Map.of(); Map<Integer,GuiRenderer.Binding> actions=Map.of();
        GuiPreviewLoader.Loaded loaded;
        View(UpgraderGuiHolder holder) { this.holder=holder; }
    }
    private final JavaPlugin owner; private final PlatformAccess platform; private final RuntimeStore<UpgraderRuntime> runtime;
    private final Messages messages; private final GuiPreviewLoader loader; private final AccessSnapshotService access;
    private final GuiSessionStore sessions=new GuiSessionStore(System::nanoTime);
    private final Map<UUID,View> views=new HashMap<>();
    // Replacing a GUI is deferred. The sweep must not close the old holder between command and next tick.
    private final Map<UUID,GuiSessionStore.Handle> pendingOpens=new HashMap<>();
    private final UUID ownerToken=UUID.randomUUID();
    private final PaperGuiScheduler scheduler; private final GuiRenderer renderer;
    private boolean stopped;
    public InventoryGuiService(JavaPlugin owner, PlatformAccess platform, RuntimeStore<UpgraderRuntime> runtime,
                               Messages messages, GuiPreviewLoader loader, AccessSnapshotService access) {
        this.owner=owner; this.platform=platform; this.runtime=runtime; this.messages=messages; this.loader=loader; this.access=access;
        this.scheduler=new PaperGuiScheduler(owner,platform); this.renderer=new GuiRenderer(messages);
    }
    public void start() { scheduler.sweep(this::sweep); }
    public boolean owns(Inventory top) { return top.getHolder(false) instanceof UpgraderGuiHolder h&&h.owned(ownerToken)&&h.getInventory()==top; }
    public Optional<GuiSessionStore.State> state(Player player, Inventory top) {
        if(!owns(top)||stopped) return Optional.empty();
        UpgraderGuiHolder h=(UpgraderGuiHolder)top.getHolder(false);
        var view=views.get(player.getUniqueId());
        return sessions.get(player.getUniqueId()).filter(s->runtime.isCurrent(s.handle().revision())&&view!=null&&view.holder==h
                &&sameView(s.handle(),h.identity())&&s.handle().viewer().equals(player.getUniqueId()));
    }
    /** Always deferred, including command calls dispatched by another plugin's inventory-click listener. */
    public void open(Player player) {
        if(stopped) return;
        var current=runtime.snapshot();
        if(current.isEmpty()||current.orElseThrow().value().gui().isEmpty()) { messages.send(player,"not-ready"); return; }
        var definition=current.orElseThrow(); var settings=definition.value().gui().orElseThrow().settings();
        if(!settings.enabled()) { messages.send(player,"gui-disabled"); return; }
        if(!permitted(player)) { messages.send(player,"no-permission"); return; }
        if(!modeAllowed(player)) { messages.send(player,"gui-mode-denied"); return; }
        if(!cursorEmpty(player)) { messages.send(player,"gui-cursor-not-empty"); return; }
        if(!platform.acquire(player.getUniqueId(),"gui-open",settings.openCooldown())) { messages.send(player,"cooldown"); return; }
        Inventory expectedTop=player.getOpenInventory().getTopInventory();
        int slot=player.getInventory().getItemInMainHand().getType().isAir()?-1:player.getInventory().getHeldItemSlot();
        var opened=sessions.open(player.getUniqueId(),definition.revision(),GuiContext.initial(slot),settings);
        if(opened.isEmpty()) { messages.send(player,"backend-busy"); return; }
        var initial=opened.orElseThrow();
        pendingOpens.put(initial.handle().viewer(),initial.handle());
        try {
            scheduler.next(initial.handle().viewer(),online->{
                if(!pendingOpens.remove(initial.handle().viewer(),initial.handle())) return;
                if(!sessions.current(initial.handle())||!runtime.isCurrent(definition.revision())) return;
                if(online.getOpenInventory().getTopInventory()!=expectedTop||!permitted(online)||!cursorEmpty(online)||!modeAllowed(online)) {
                    abandonPending(initial); return;
                }
                try {
                    if(!show(online,initial,definition,Optional.empty(),"gui-state-loading")) return;
                    cue(online,definition,"open"); messages.send(online,"gui-opened-preview");
                    var reserved=sessions.reserve(initial.handle());
                    reserved.ifPresent(s->refresh(online,s,definition));
                } catch(RuntimeException error) { broken(online,initial,error); }
            });
        } catch(RuntimeException error) { abandonPending(initial); report(error); messages.send(player,"gui-load-failed"); }
    }
    private void abandonPending(GuiSessionStore.State state) {
        pendingOpens.remove(state.handle().viewer(),state.handle());
        sessions.close(state.handle().viewer(),state.handle().session(),state.handle().view());
        // Keep a still-open older owned holder guarded; the sweep will close it by its own identity.
    }
    public Optional<GuiRenderer.Binding> binding(Player player, int slot) {
        var v=views.get(player.getUniqueId()); return v==null?Optional.empty():Optional.ofNullable(v.actions.get(slot));
    }
    /** Called only after the listener cancels the event. Captures IDs/context, never InventoryClickEvent/Player. */
    public void click(Player player, GuiSessionStore.State current, GuiRenderer.Binding button, boolean right, int sourceSlot) {
        if(!permitted(player)) return;
        if(right&&button.action()!=MenuDefinition.Action.SOURCE_INPUT) return;
        var reserved=sessions.reserve(current.handle());
        if(reserved.isEmpty()) return;
        var ticket=reserved.orElseThrow();
        try { scheduler.next(ticket.handle().viewer(),p->execute(p,ticket,button,right,sourceSlot)); }
        catch(RuntimeException error) { broken(player,ticket,error); }
    }
    private void execute(Player player, GuiSessionStore.State queued, GuiRenderer.Binding button, boolean right, int sourceSlot) {
        if(!sessions.current(queued.handle())) return;
        var current=runtime.snapshot(); var v=views.get(player.getUniqueId());
        if(current.isEmpty()||current.orElseThrow().revision()!=queued.handle().revision()||v==null
                ||!sameView(queued.handle(),v.holder.identity())||player.getOpenInventory().getTopInventory()!=v.holder.getInventory()) { exit(player.getUniqueId(),"gui-stale"); return; }
        var definition=current.orElseThrow();
        if(!permitted(player)||!modeAllowed(player)||!cursorEmpty(player)) { exit(player.getUniqueId(),"gui-stale"); return; }
        try {
            if(button.action()==MenuDefinition.Action.CLOSE) { cue(player,definition,"close"); exit(player.getUniqueId(),null); return; }
            if(button.action()==MenuDefinition.Action.UPGRADE) {
                // No AttemptPlanner/Engine/EffectPort reachable from this UI until native acceptance gates pass.
                messages.send(player,"gui-transactions-locked"); cue(player,definition,"error");
                refresh(player,queued,definition); return;
            }
            GuiContext next=queued.context();
            if(button.action()!=MenuDefinition.Action.SOURCE_INPUT&&v.loaded!=null
                    &&!GuiPreviewLoader.sameSource(v.loaded.preview().source(),loader.source(player,definition,next.sourceSlot()))) {
                next=GuiContext.initial(next.sourceSlot()); messages.send(player,"gui-source-changed");
            } else next=navigate(player,queued.context(),button,right,sourceSlot,v,definition);
            var moved=sessions.transition(queued.handle(),next);
            if(moved.isEmpty()) return;
            var state=moved.orElseThrow();
            if(!show(player,state,definition,Optional.empty(),"gui-state-loading")) return;
            cue(player,definition,"click"); refresh(player,state,definition);
        } catch(IllegalArgumentException invalid) {
            messages.send(player,"gui-option-denied"); cue(player,definition,"error");
            if(sessions.current(queued.handle())) refresh(player,queued,definition);
            else broken(player,queued,invalid);
        } catch(RuntimeException error) { broken(player,queued,error); }
    }
    private GuiContext navigate(Player player, GuiContext c, GuiRenderer.Binding b, boolean right, int sourceSlot, View v,
                                RuntimeStore.Snapshot<UpgraderRuntime> definition) {
        var rules=definition.value().upgradeRules();
        return switch(b.action()) {
            case SOURCE_INPUT -> GuiContext.initial(right?-1:sourceSlot>=0?sourceSlot:player.getInventory().getHeldItemSlot());
            case OPEN_CATALOG -> c.screen(GuiContext.Screen.CATALOG);
            case OPEN_PROFILES -> c.screen(GuiContext.Screen.PROFILES);
            case OPEN_BOOSTS -> c.screen(GuiContext.Screen.BOOSTS);
            case BACK_MAIN -> c.screen(GuiContext.Screen.MAIN);
            case REFRESH -> c;
            case CLEAR_BOOSTS -> c.boosts(List.of());
            case SELECT_TARGET -> {
                if(c.screen()!=GuiContext.Screen.CATALOG||v.loaded==null||v.loaded.preview().entries().stream().noneMatch(e->e.id().equals(b.argument())))
                    throw new IllegalArgumentException("catalog binding no longer valid");
                yield c.target(b.argument()).screen(GuiContext.Screen.MAIN);
            }
            case SELECT_PROFILE -> {
                var selected=rules.profiles().get(b.argument()); var evidence=access.capture(player,definition.value());
                var source=loader.source(player,definition,c.sourceSlot());
                var path=source.flatMap(s->definition.value().catalog().pathFor(s.facts().key()));
                if(selected==null||!selected.enabled()||!rules.profileAllowed(path,selected.id())||!evidence.allows(selected.permission(),selected.requiredConditions()))
                    throw new IllegalArgumentException("profile denied");
                yield c.profile(selected.id()).screen(GuiContext.Screen.MAIN);
            }
            case TOGGLE_BOOST -> {
                var chosen=new ArrayList<>(c.boosts());
                if(!chosen.remove(b.argument())) {
                    var selected=rules.boosts().get(b.argument()); var evidence=access.capture(player,definition.value());
                    var path=loader.source(player,definition,c.sourceSlot()).flatMap(s->definition.value().catalog().pathFor(s.facts().key()));
                    String profile=rules.profileFor(path,c.profileId());
                    if(selected==null||!selected.enabled()||!evidence.allows(selected.permission(),selected.requiredConditions())
                            ||(!selected.allowedProfiles().isEmpty()&&!selected.allowedProfiles().contains(profile))||chosen.size()>=rules.settings().maximumSelectedBoosts())
                        throw new IllegalArgumentException("boost denied");
                    chosen.add(selected.id());
                }
                yield c.boosts(chosen);
            }
            case NEXT_PAGE -> {
                if(v.loaded==null||c.page()>=v.loaded.preview().pages()) throw new IllegalArgumentException("last page"); yield c.page(c.page()+1);
            }
            case PREVIOUS_PAGE -> { if(c.page()<=1) throw new IllegalArgumentException("first page"); yield c.page(c.page()-1); }
            case CYCLE_SORT -> c.sort(CatalogQuery.Sort.values()[(c.sort().ordinal()+1)%CatalogQuery.Sort.values().length]);
            case CYCLE_CATEGORY -> {
                var categories=new ArrayList<String>(); categories.add(""); categories.addAll(definition.value().catalog().categories().stream().sorted().toList());
                yield c.category(categories.get((categories.indexOf(c.category())+1)%categories.size()));
            }
            default -> c;
        };
    }
    private void refresh(Player player, GuiSessionStore.State state, RuntimeStore.Snapshot<UpgraderRuntime> definition) {
        if(!sessions.current(state.handle())) return;
        View v=views.get(player.getUniqueId());
        if(v==null||!sameView(v.holder.identity(),state.handle())) return;
        v.loaded=null;
        show(player,state,definition,Optional.empty(),"gui-state-loading");
        loader.load(player,state,definition,()->sessions.current(state.handle())&&runtime.isCurrent(definition.revision()),loaded->{
            var finished=sessions.complete(state.handle(),loaded.preview().context());
            if(finished.isEmpty()) return;
            // Loader invokes on owner; resolve no new session and never reopen a menu closed in the meantime.
            var online=owner.getServer().getPlayer(state.handle().viewer());
            View live=views.get(state.handle().viewer());
            if(online==null||live==null||!sameView(live.holder.identity(),state.handle())||online.getOpenInventory().getTopInventory()!=live.holder.getInventory()) return;
            live.loaded=loaded;
            show(online,finished.orElseThrow(),definition,Optional.of(loaded.preview()),"gui-state-"+loaded.preview().status());
        },key->{
            var finished=sessions.complete(state.handle(),state.context());
            if(finished.isEmpty()) return;
            var online=owner.getServer().getPlayer(state.handle().viewer()); View live=views.get(state.handle().viewer());
            if(online==null||live==null||!sameView(live.holder.identity(),state.handle())||online.getOpenInventory().getTopInventory()!=live.holder.getInventory()) return;
            live.loaded=null; show(online,finished.orElseThrow(),definition,Optional.empty(),"gui-state-error"); messages.send(online,key);
        });
    }
    private boolean show(Player player, GuiSessionStore.State state, RuntimeStore.Snapshot<UpgraderRuntime> definition,
                         Optional<GuiPreviewService.Preview> preview, String status) {
        if(!sessions.current(state.handle())||!runtime.isCurrent(definition.revision())) return false;
        var gui=definition.value().gui().orElseThrow(); var menu=gui.menu(state.context().screen());
        View v=views.get(player.getUniqueId()); boolean opening=v==null||!sameView(v.holder.identity(),state.handle());
        if(opening) {
            var title=messages.template(menu.title(),renderer.parameters(definition.value(),state,preview,status));
            v=new View(new UpgraderGuiHolder(ownerToken,state.handle(),menu.size(),title)); views.put(player.getUniqueId(),v);
        }
        var frame=renderer.render(definition.revision(),definition.value(),state,preview,status);
        for(var entry:SlotDiff.changed(v.last,frame.items(),menu.size()).entrySet()) v.holder.getInventory().setItem(entry.getKey(),entry.getValue().clone());
        v.last=frame.items(); v.actions=frame.actions();
        if(opening) player.openInventory(v.holder.getInventory());
        if(player.getOpenInventory().getTopInventory()!=v.holder.getInventory()) {
            closed(player.getUniqueId(),v.holder);
            // A cancelled replacement may leave our old container open; never leave an untracked owned view.
            if(owns(player.getOpenInventory().getTopInventory())) player.closeInventory();
            return false;
        }
        return true;
    }
    public void closed(UUID viewer, UpgraderGuiHolder holder) {
        if(!holder.owned(ownerToken)) return;
        sessions.close(viewer,holder.identity().session(),holder.identity().view());
        var v=views.get(viewer); if(v!=null&&v.holder==holder) views.remove(viewer);
    }
    public void forget(UUID viewer) { sessions.forget(viewer); views.remove(viewer); pendingOpens.remove(viewer); }
    /** Teleport/death/kick route here, not just forget(): close the exact old view on next tick. */
    public void exit(UUID viewer,String message) {
        sessions.forget(viewer); pendingOpens.remove(viewer); View v=views.remove(viewer);
        if(v==null) return;
        try { scheduler.next(viewer,p->{if(p.getOpenInventory().getTopInventory()==v.holder.getInventory()) {p.closeInventory(); if(message!=null) messages.send(p,message);}}); }
        catch(RuntimeException rejected) { report(rejected); }
    }
    private void sweep() {
        if(stopped) return;
        long revision=runtime.snapshot().map(s->s.revision()).orElse(-1L);
        for(var state:sessions.sweep(revision)) {
            pendingOpens.remove(state.handle().viewer());
            View v=views.get(state.handle().viewer());
            if(v!=null&&sameView(v.holder.identity(),state.handle())) { views.remove(state.handle().viewer()); closeOld(state.handle().viewer(),v,"gui-session-ended"); }
        }
        for(var entry:List.copyOf(views.entrySet())) {
            var state=sessions.get(entry.getKey()); View v=entry.getValue();
            var pending=pendingOpens.get(entry.getKey());
            if(pending!=null&&state.filter(s->s.handle().equals(pending)).isPresent()) continue;
            if(state.isEmpty()||!sameView(state.orElseThrow().handle(),v.holder.identity())) { views.remove(entry.getKey(),v); closeOld(entry.getKey(),v,"gui-session-ended"); continue; }
            if(!state.orElseThrow().busy()&&v.loaded!=null&&v.loaded.preview().quote().flatMap(q->q.quote())
                    .filter(q->!Instant.now().isBefore(q.selection().expiresAt())).isPresent()) {
                v.loaded=null; // no auto-renewal: player chooses REFRESH explicitly
                var definition=runtime.snapshot().orElseThrow(); var expected=state.orElseThrow();
                platform.player(entry.getKey(),p->{if(sessions.current(expected.handle())&&p.getOpenInventory().getTopInventory()==v.holder.getInventory())
                    show(p,expected,definition,Optional.empty(),"gui-state-expired");});
            }
        }
    }
    private void closeOld(UUID viewer,View v,String message) {
        platform.player(viewer,p->{if(p.getOpenInventory().getTopInventory()==v.holder.getInventory()){p.closeInventory(); messages.send(p,message);}});
    }
    private void broken(Player player,GuiSessionStore.State state,RuntimeException error) {
        report(error); if(sessions.get(player.getUniqueId()).filter(s->s.handle().session().equals(state.handle().session())).isPresent()) exit(player.getUniqueId(),"gui-load-failed");
    }
    private void report(RuntimeException error) { owner.getLogger().log(Level.WARNING,"GUI operation failed; preview contains no player custody",error); }
    private static boolean sameView(GuiSessionStore.Handle a,GuiSessionStore.Handle b) { return a.viewer().equals(b.viewer())&&a.session().equals(b.session())&&a.view().equals(b.view()); }
    private static boolean permitted(Player p) { return p.isOnline()&&!p.isDead()&&p.hasPermission("ledatitemupgrader.use"); }
    private static boolean modeAllowed(Player p) { return p.getGameMode()==GameMode.SURVIVAL||p.getGameMode()==GameMode.ADVENTURE; }
    private static boolean cursorEmpty(Player p) { return p.getItemOnCursor().getType().isAir(); }
    private void cue(Player p,RuntimeStore.Snapshot<UpgraderRuntime> definition,String id) {
        var cue=definition.value().gui().orElseThrow().settings().sounds().get(id);
        if(cue!=null&&!cue.key().isEmpty()) p.playSound(Sound.sound(Key.key(cue.key()),Sound.Source.MASTER,cue.volume(),cue.pitch()));
    }
    public void close() {
        if(stopped) return; stopped=true; sessions.stop(); scheduler.close();
        // onDisable is the Paper main thread. Do not enqueue retired callbacks to close current menus.
        for(var entry:List.copyOf(views.entrySet())) {
            Player p=owner.getServer().getPlayer(entry.getKey());
            if(p!=null&&p.getOpenInventory().getTopInventory()==entry.getValue().holder.getInventory()) p.closeInventory();
        }
        views.clear(); pendingOpens.clear(); renderer.clear();
    }
}
