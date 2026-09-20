package vn.ledat.itemupgrader.paper.gui;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import vn.ledat.itemupgrader.catalog.CatalogQuery;
import vn.ledat.itemupgrader.gui.*;
import vn.ledat.itemupgrader.paper.message.Messages;
import vn.ledat.itemupgrader.paper.item.PlatformIdentityIndex;
import vn.ledat.itemupgrader.paper.transaction.NativeTestUpgradeService;
import vn.ledat.itemupgrader.paper.platform.PlatformAccess;
import vn.ledat.itemupgrader.paper.service.AccessSnapshotService;
import vn.ledat.itemupgrader.runtime.*;

/** Owner-thread UI coordinator. Read-only slot references; live transaction execution is deliberately absent. */
public final class InventoryGuiService {
    private static final class View {
        final UpgraderGuiHolder holder;
        final GuiTitleToken titleToken;
        Map<Integer,ItemStack> last=Map.of(); Map<Integer,GuiRenderer.Binding> actions=Map.of();
        GuiPreviewLoader.Loaded loaded;
        GuiSessionStore.State titleState; Optional<GuiPreviewService.Preview> titlePreview=Optional.empty();
        UpgraderTitleRenderer.Data rollData; NativeTestUpgradeService.Prepared pendingUpgrade;
        int landingBar;
        Component lastTitle; String titleSignature=""; long titleStarted, titleRollSeed;
        boolean titleComplete=true, titleRolling;
        boolean titleActive;
        View(UpgraderGuiHolder holder, GuiTitleToken titleToken, Component title) {
            this.holder=holder; this.titleToken=titleToken; this.lastTitle=title;
        }
    }
    private final JavaPlugin owner; private final PlatformAccess platform; private final RuntimeStore<UpgraderRuntime> runtime;
    private final Messages messages; private final GuiPreviewLoader loader; private final AccessSnapshotService access;
    private final GuiSessionStore sessions=new GuiSessionStore(System::nanoTime);
    private final Map<UUID,View> views=new HashMap<>();
    // Replacing a GUI is deferred. The sweep must not close the old holder between command and next tick.
    private final Map<UUID,GuiSessionStore.Handle> pendingOpens=new HashMap<>();
    private final UUID ownerToken=UUID.randomUUID();
    private final PaperGuiScheduler scheduler; private final GuiRenderer renderer;
    private final UpgraderTitleRenderer titleRenderer; private final GuiTitlePackets titlePackets;
    private final NativeTestUpgradeService upgrades;
    private long titleTick; private int titleCursor;
    private boolean stopped, titleWarning;
    public InventoryGuiService(JavaPlugin owner, PlatformAccess platform, RuntimeStore<UpgraderRuntime> runtime,
                               Messages messages, GuiPreviewLoader loader, AccessSnapshotService access,PlatformIdentityIndex identities) {
        this.owner=owner; this.platform=platform; this.runtime=runtime; this.messages=messages; this.loader=loader; this.access=access;
        this.scheduler=new PaperGuiScheduler(owner,platform); this.renderer=new GuiRenderer(messages);
        this.titleRenderer=new UpgraderTitleRenderer(owner); this.titlePackets=new GuiTitlePackets(owner);
        this.upgrades=new NativeTestUpgradeService(platform,identities);
    }
    public void start() { scheduler.sweep(this::sweep); scheduler.repeat(this::tickTitles,1); }
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
        var live=views.get(player.getUniqueId());
        if(live!=null&&live.titleRolling&&button.action()!=MenuDefinition.Action.CLOSE) return;
        if(right&&button.action()!=MenuDefinition.Action.SOURCE_INPUT&&button.action()!=MenuDefinition.Action.ADJUST_AMOUNT) return;
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
                var finished=sessions.complete(queued.handle(),queued.context());
                if(finished.isEmpty()) return;
                var stable=finished.orElseThrow();
                if(!definition.value().upgradesEnabled()) {
                    messages.send(player,"gui-transactions-locked");cue(player,definition,"error");return;
                }
                if(v.loaded==null)throw new IllegalArgumentException("upgrade preview unavailable");
                var layout=definition.value().gui().orElseThrow().titleLayout().filter(UpgraderTitleLayout::enabled)
                        .orElseThrow(()->new IllegalArgumentException("upgrade roll title disabled"));
                var data=titleRenderer.data(player,stable,Optional.of(v.loaded.preview()),layout)
                        .orElseThrow(()->new IllegalArgumentException("upgrade quote unavailable"));
                var plan=loader.prepareAttempt(player,stable,definition,v.loaded);
                var prepared=upgrades.prepare(player,definition,plan);
                if(!startTitleRoll(player,v,stable,layout,data,layout.sampleBar(prepared.sample()),prepared)) return;
                messages.send(player,"gui-roll-started");cue(player,definition,"click");
                return;
            }
            GuiContext next=queued.context();
            if(button.action()!=MenuDefinition.Action.SOURCE_INPUT&&v.loaded!=null
                    &&!GuiPreviewLoader.sameSource(v.loaded.preview().source(),loader.source(player,definition,next.sourceSlot(),next.selectedAmount()))) {
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
            case ADJUST_AMOUNT -> {
                if(c.sourceSlot()<0) throw new IllegalArgumentException("amount requires source");
                ItemStack source=player.getInventory().getItem(c.sourceSlot());
                if(source==null||source.getType().isAir()) throw new IllegalArgumentException("amount source missing");
                int step=Integer.parseInt(b.argument());
                int selected=right?Math.max(1,c.selectedAmount()-step):Math.min(source.getAmount(),c.selectedAmount()+step);
                if(selected==c.selectedAmount()) throw new IllegalArgumentException("amount bound reached");
                yield c.amount(selected);
            }
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
                var source=loader.source(player,definition,c.sourceSlot(),c.selectedAmount());
                var path=source.flatMap(s->definition.value().catalog().pathFor(s.facts().key()));
                if(selected==null||!selected.enabled()||!rules.profileAllowed(path,selected.id())||!evidence.allows(selected.permission(),selected.requiredConditions()))
                    throw new IllegalArgumentException("profile denied");
                yield c.profile(selected.id()).screen(GuiContext.Screen.MAIN);
            }
            case TOGGLE_BOOST -> {
                var chosen=new ArrayList<>(c.boosts());
                if(!chosen.remove(b.argument())) {
                    var selected=rules.boosts().get(b.argument()); var evidence=access.capture(player,definition.value());
                    var path=loader.source(player,definition,c.sourceSlot(),c.selectedAmount()).flatMap(s->definition.value().catalog().pathFor(s.facts().key()));
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
            View retired=v;
            boolean dynamic=state.context().screen()==GuiContext.Screen.MAIN&&gui.titleLayout().filter(UpgraderTitleLayout::enabled).isPresent();
            var title=dynamic?initialTitle(player,state,preview,gui.titleLayout().orElseThrow())
                    :messages.template(menu.title(),renderer.parameters(definition.value(),state,preview,status));
            var token=dynamic?GuiTitleToken.of(state.handle()):null;
            v=new View(new UpgraderGuiHolder(ownerToken,state.handle(),menu.size(),title),token,title);
            if(retired!=null&&retired.titleToken!=null) titlePackets.release(retired.titleToken);
            views.put(player.getUniqueId(),v);
            if(dynamic) {
                v.titleActive=titlePackets.expect(token,v.holder.getInventory());
                if(!v.titleActive) warnTitle("could not prepare the owned title packet session",null);
            }
        }
        var frame=renderer.render(definition.revision(),definition.value(),state,preview,status);
        for(var entry:SlotDiff.changed(v.last,frame.items(),menu.size()).entrySet()) v.holder.getInventory().setItem(entry.getKey(),entry.getValue().clone());
        v.last=frame.items(); v.actions=frame.actions();
        if(opening) {
            if(v.titleActive&&!titlePackets.arm(player,v.titleToken,v.holder.getInventory()))
                disableTitle(v,"could not arm the owned title capture");
            try { player.openInventory(v.holder.getInventory()); }
            finally { if(v.titleToken!=null) titlePackets.disarm(v.titleToken); }
        }
        if(player.getOpenInventory().getTopInventory()!=v.holder.getInventory()) {
            closed(player.getUniqueId(),v.holder);
            // A cancelled replacement may leave our old container open; never leave an untracked owned view.
            if(owns(player.getOpenInventory().getTopInventory())) player.closeInventory();
            return false;
        }
        if(opening&&v.titleActive&&!titlePackets.activate(player,v.titleToken,v.holder.getInventory()))
            disableTitle(v,"the owned OPEN_WINDOW packet was not captured");
        updateTitleState(player,v,state,preview,gui.titleLayout(),opening);
        return true;
    }
    public void closed(UUID viewer, UpgraderGuiHolder holder) {
        if(!holder.owned(ownerToken)) return;
        sessions.close(viewer,holder.identity().session(),holder.identity().view());
        var v=views.get(viewer); if(v!=null&&v.holder==holder) { views.remove(viewer); releaseTitle(v); }
    }
    public void forget(UUID viewer) { sessions.forget(viewer); releaseTitle(views.remove(viewer)); pendingOpens.remove(viewer); }
    /** Teleport/death/kick route here, not just forget(): close the exact old view on next tick. */
    public void exit(UUID viewer,String message) {
        sessions.forget(viewer); pendingOpens.remove(viewer); View v=views.remove(viewer);
        if(v==null) return;
        releaseTitle(v);
        try { scheduler.next(viewer,p->{if(p.getOpenInventory().getTopInventory()==v.holder.getInventory()) {p.closeInventory(); if(message!=null) messages.send(p,message);}}); }
        catch(RuntimeException rejected) { report(rejected); }
    }
    private Component initialTitle(Player player,GuiSessionStore.State state,Optional<GuiPreviewService.Preview> preview,
                                   UpgraderTitleLayout layout) {
        return titleRenderer.data(player,state,preview,layout).map(data->titleRenderer.preview(player,layout,data))
                .orElseGet(()->titleRenderer.empty(player,layout));
    }
    private void updateTitleState(Player player,View view,GuiSessionStore.State state,Optional<GuiPreviewService.Preview> preview,
                                  Optional<UpgraderTitleLayout> configured,boolean opening) {
        view.titleState=state; view.titlePreview=preview;
        if(!view.titleActive||view.titleToken==null||configured.isEmpty()||!configured.orElseThrow().enabled()) return;
        var layout=configured.orElseThrow(); var data=titleRenderer.data(player,state,preview,layout);
        String signature=data.map(UpgraderTitleRenderer.Data::signature).orElse("");
        if(signature.equals(view.titleSignature)) return;
        view.titleSignature=signature; view.titleRolling=false; view.titleComplete=true;
        Component title=data.map(d->titleRenderer.preview(player,layout,d)).orElseGet(()->titleRenderer.empty(player,layout));
        if(opening) { view.lastTitle=title; return; }
        sendTitle(player,view,title);
        // Some inventory/resource-pack listeners finish their own slot/title work later in this
        // tick. Reassert the exact same completed preview once on the next owner tick so the
        // initial CHƯA CÓ title cannot win that race. The identity/signature guards prevent a
        // stale quote from being sent after another click, screen change, or close.
        UUID viewer=player.getUniqueId();
        try { scheduler.next(viewer,online->{
            View live=views.get(viewer);
            if(live==view&&live.titleActive&&signature.equals(live.titleSignature)
                    &&online.getOpenInventory().getTopInventory()==live.holder.getInventory())
                forceTitle(online,live,title);
        }); }
        catch(RuntimeException rejected) { report(rejected); }
    }
    private void tickTitles() {
        if(stopped) return; titleTick++;
        var snapshot=runtime.snapshot();
        var layout=snapshot.flatMap(s->s.value().gui()).flatMap(GuiMenus::titleLayout).filter(UpgraderTitleLayout::enabled);
        if(layout.isEmpty()||views.isEmpty()) return;
        var candidates=views.values().stream().filter(v->v.titleActive&&v.titleToken!=null&&v.titleRolling&&!v.titleComplete).toList();
        if(candidates.isEmpty()) return;
        int visits=Math.min(layout.orElseThrow().visitsPerTick(),candidates.size());
        int start=Math.floorMod(titleCursor,candidates.size()); titleCursor=(start+visits)%candidates.size();
        for(int i=0;i<visits;i++) {
            View expected=candidates.get((start+i)%candidates.size());
            try { platform.player(expected.titleToken.viewer(),player->{
                try { animateTitle(player,expected,layout.orElseThrow()); }
                catch(RuntimeException error) {
                    warnTitle("dynamic title rendering failed",error);
                    if(disableTitle(expected,null)) messages.send(player,"gui-roll-display-interrupted");
                }
            }); }
            catch(RuntimeException error) { warnTitle("dynamic title callback failed",error); disableTitle(expected,null); }
        }
    }
    private void animateTitle(Player player,View expected,UpgraderTitleLayout layout) {
        View live=views.get(expected.titleToken.viewer()); var state=sessions.get(expected.titleToken.viewer());
        if(live!=expected||!expected.titleActive||state.isEmpty()||!expected.titleToken.matches(state.orElseThrow().handle())
                ||player.getOpenInventory().getTopInventory()!=expected.holder.getInventory()) return;
        var data=Optional.ofNullable(expected.rollData);
        String signature=data.map(UpgraderTitleRenderer.Data::signature).orElse("");
        if(!signature.equals(expected.titleSignature)) {
            expected.titleState=state.orElseThrow(); expected.titleSignature=signature; expected.titleRolling=false; expected.titleComplete=true;
            sendTitle(player,expected,data.map(d->titleRenderer.preview(player,layout,d)).orElseGet(()->titleRenderer.empty(player,layout)));
            return;
        }
        if(data.isEmpty()) { expected.titleRolling=false; expected.titleComplete=true; return; }
        long elapsed=Math.max(0,titleTick-expected.titleStarted);
        if(elapsed<layout.selectedHoldTicks()) {
            sendTitle(player,expected,titleRenderer.preview(player,layout,data.orElseThrow())); return;
        }
        // The chance bar is already visible before click; skip its old fill phase and animate only the pointer.
        var frame=layout.rollFrame(data.orElseThrow().targetBar(),expected.landingBar,
                elapsed-layout.selectedHoldTicks()+layout.barFillTicks(),expected.titleRollSeed);
        Component title=titleRenderer.animated(player,layout,data.orElseThrow(),frame);
        boolean changed=!title.equals(expected.lastTitle); sendTitle(player,expected,title);
        if(changed&&frame.pulse()) cue(player,runtime.snapshot().orElseThrow(),"title-pulse");
        if(frame.complete()&&elapsed>=layout.selectedHoldTicks()+layout.arrowDurationTicks()+layout.selectedHoldTicks()) {
            expected.titleRolling=false;expected.titleComplete=true;
            var prepared=expected.pendingUpgrade;expected.pendingUpgrade=null;expected.rollData=null;
            if(prepared!=null) {
                try {
                    var definition=runtime.snapshot().orElseThrow();upgrades.commit(player,definition,prepared);
                    messages.send(player,prepared.success()?"gui-roll-success":"gui-roll-failure");
                } catch(RuntimeException error) { report(error);messages.send(player,"gui-roll-commit-failed"); }
                exit(player.getUniqueId(),null);
            }
        }
    }
    private boolean startTitleRoll(Player player,View view,GuiSessionStore.State state,UpgraderTitleLayout layout,
                                   UpgraderTitleRenderer.Data data,int landingBar,NativeTestUpgradeService.Prepared prepared) {
        if(!view.titleActive||view.titleToken==null)throw new IllegalArgumentException("upgrade title packet unavailable");
        view.titleState=state;view.titleSignature=data.signature();view.rollData=data;view.landingBar=landingBar;view.pendingUpgrade=prepared;
        view.titleStarted=titleTick; view.titleRollSeed=ThreadLocalRandom.current().nextLong();
        view.titleComplete=false; view.titleRolling=true;
        sendTitle(player,view,titleRenderer.preview(player,layout,data));
        return view.titleActive&&view.titleRolling&&view.pendingUpgrade==prepared;
    }
    private void sendTitle(Player player,View view,Component title) {
        if(!view.titleActive||title.equals(view.lastTitle)) return;
        forceTitle(player,view,title);
    }
    private void forceTitle(Player player,View view,Component title) {
        if(!view.titleActive) return;
        if(!titlePackets.send(player,view.titleToken,view.holder.getInventory(),title)) {
            if(disableTitle(view,"the exact owned container was lost")) messages.send(player,"gui-roll-display-interrupted");
            return;
        }
        view.lastTitle=title;
    }
    private boolean disableTitle(View view,String reason) {
        if(view==null||!view.titleActive) return false;
        boolean interruptedRoll=view.pendingUpgrade!=null;
        view.titleActive=false; view.titleRolling=false; view.titleComplete=true;
        // The title is the acknowledgement boundary for the opt-in native executor. If that
        // presentation channel is lost, discard the prepared outcome rather than committing an
        // invisible result or leaving the player in a stale rolling state.
        view.pendingUpgrade=null; view.rollData=null;
        if(view.titleToken!=null) titlePackets.release(view.titleToken);
        if(reason!=null) warnTitle(reason,null);
        return interruptedRoll;
    }
    private void releaseTitle(View view) {
        if(view!=null&&view.titleToken!=null) titlePackets.release(view.titleToken);
        if(view!=null) view.titleActive=false;
    }
    private void warnTitle(String reason,RuntimeException error) {
        if(titleWarning) return;
        titleWarning=true;
        String message="Dynamic inventory title disabled for this view: "+reason+". The preview remains usable.";
        if(error==null) owner.getLogger().warning(message); else owner.getLogger().log(Level.WARNING,message,error);
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
        releaseTitle(v);
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
            releaseTitle(entry.getValue());
        }
        views.clear(); pendingOpens.clear(); renderer.clear();
        titlePackets.close();
    }
}
