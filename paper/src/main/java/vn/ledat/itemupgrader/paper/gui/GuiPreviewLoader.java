package vn.ledat.itemupgrader.paper.gui;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import vn.ledat.itemupgrader.catalog.*;
import vn.ledat.itemupgrader.cost.ResourceSnapshot;
import vn.ledat.itemupgrader.gui.*;
import vn.ledat.itemupgrader.item.ItemSnapshot;
import vn.ledat.itemupgrader.item.ItemKey;
import vn.ledat.itemupgrader.paper.item.*;
import vn.ledat.itemupgrader.paper.platform.PlatformAccess;
import vn.ledat.itemupgrader.paper.service.*;
import vn.ledat.itemupgrader.quote.QuoteRequest;
import vn.ledat.itemupgrader.quote.UpgradeQuoteService;
import vn.ledat.itemupgrader.transaction.AttemptPlanner;
import vn.ledat.itemupgrader.transaction.model.AttemptPlan;
import vn.ledat.itemupgrader.runtime.*;

/** Owner snapshot -> bounded worker query -> owner recheck. Callbacks never retain Player/event/cursor. */
public final class GuiPreviewLoader {
    private record Observed(Optional<ItemSnapshot> source, CatalogAccess access, ResourceSnapshot resources) {}
    public record Loaded(GuiPreviewService.Preview preview, CatalogIndex index) {}
    private final JavaPlugin owner; private final PlatformAccess platform; private final RuntimeStore<UpgraderRuntime> runtime;
    private final CatalogCacheService cache; private final PlatformIdentityIndex identities; private final AccessSnapshotService access;
    private final CostResourceCapture costs; private final PaperItemSnapshotFactory snapshots=new PaperItemSnapshotFactory();
    private final GuiPreviewService previews=new GuiPreviewService(); private final AtomicInteger workers=new AtomicInteger();
    public GuiPreviewLoader(JavaPlugin owner, PlatformAccess platform, RuntimeStore<UpgraderRuntime> runtime, CatalogCacheService cache,
                            PlatformIdentityIndex identities, AccessSnapshotService access) {
        this.owner=owner; this.platform=platform; this.runtime=runtime; this.cache=cache; this.identities=identities; this.access=access;
        this.costs=new CostResourceCapture(owner,platform,identities);
    }
    public void load(Player player, GuiSessionStore.State state, RuntimeStore.Snapshot<UpgraderRuntime> definition,
                     BooleanSupplier current, Consumer<Loaded> success, Consumer<String> failure) {
        UUID id=state.handle().viewer();
        cache.prepare(player,definition,index->{
            if(!current.getAsBoolean()) return;
            try {
                // prepare callback is owner-thread; resolve UUID rather than retain the initiating Player.
                platform.player(id, online -> captureAndQuery(online,state,definition,index,current,success,failure));
            } catch(RuntimeException error) { report(error); failure.accept("gui-load-failed"); }
        },reason->{ if(current.getAsBoolean()) failure.accept("gui-catalog-"+reason.name().toLowerCase(java.util.Locale.ROOT)); });
    }
    private void captureAndQuery(Player online, GuiSessionStore.State state, RuntimeStore.Snapshot<UpgraderRuntime> definition,
                                 CatalogIndex index, BooleanSupplier current, Consumer<Loaded> success, Consumer<String> failure) {
        if(!current.getAsBoolean()) return;
        if(!runtime.isCurrent(definition.revision())||!cache.current(index)||!online.hasPermission("ledatitemupgrader.use")) { failure.accept("gui-stale"); return; }
        try {
            Observed observed=observe(online,definition,state.context());
            if(workers.incrementAndGet()>16) { workers.decrementAndGet(); failure.accept("backend-busy"); return; }
            try {
                platform.async("itemupgrader-gui-query",()->{
                    try {
                        if(!current.getAsBoolean()||!runtime.isCurrent(definition.revision())) return;
                        var result=previews.calculate(state.handle().session(),state.context(),observed.source(),index,observed.access(),
                                definition.value().upgradeRules(),observed.resources(),definition.value().gui().orElseThrow().pageSize(state.context().screen()),Instant.now());
                        platform.player(state.handle().viewer(),player->{
                            if(!current.getAsBoolean()) return;
                            try {
                                if(!runtime.isCurrent(definition.revision())||!cache.current(index)||!player.hasPermission("ledatitemupgrader.use")
                                        ||!same(observed,observe(player,definition,state.context()))
                                        ||result.quote().flatMap(q->q.quote()).filter(q->!Instant.now().isBefore(q.selection().expiresAt())).isPresent()) {
                                    failure.accept("gui-stale"); return;
                                }
                                success.accept(new Loaded(result,index));
                            } catch(RuntimeException error) { report(error); failure.accept("gui-load-failed"); }
                        });
                    } catch(RuntimeException error) { report(error); platform.player(state.handle().viewer(),p->{if(current.getAsBoolean()) failure.accept("gui-load-failed");}); }
                    finally { workers.decrementAndGet(); }
                });
            } catch(RuntimeException rejected) { workers.decrementAndGet(); throw rejected; }
        } catch(RuntimeException error) { report(error); failure.accept("gui-source-invalid"); }
    }
    private Observed observe(Player player, RuntimeStore.Snapshot<UpgraderRuntime> definition, GuiContext context) {
        Optional<ItemSnapshot> source=source(player,definition,context.sourceSlot(),context.selectedAmount());
        var snapshot=access.capture(player,definition.value());
        var resources=source.isEmpty()?ResourceSnapshot.empty(player.getUniqueId(),Math.max(0,context.sourceSlot())):
                costs.capture(player,definition,definition.value().upgradeRules().resourcesFor(definition.value().catalog().pathFor(source.orElseThrow().facts().key()),
                        new QuoteRequest(new UUID(0,1),context.targetId().isEmpty()?"preview":context.targetId(),context.profileId(),context.boosts(),context.sourceSlot())),context.sourceSlot());
        return new Observed(source,snapshot,resources);
    }
    public Optional<ItemSnapshot> source(Player player, RuntimeStore.Snapshot<UpgraderRuntime> definition, int slot, int selectedAmount) {
        if(slot<0) return Optional.empty();
        if(slot>35) throw new IllegalArgumentException("GUI source outside storage inventory");
        var item=player.getInventory().getItem(slot);
        if(item==null||item.getType().isAir()||item.getAmount()<1) return Optional.empty();
        if(selectedAmount<1||selectedAmount>item.getAmount()) throw new IllegalArgumentException("selected source amount unavailable");
        var key=identities.identify(item,definition).key().orElseThrow(()->new IllegalArgumentException("source identity unverified"));
        var selected=item.clone(); selected.setAmount(selectedAmount);
        var snapshot=snapshots.capture(selected,key);
        if(snapshot.byteSize()>definition.value().values().limits().maxSnapshotBytes()) throw new IllegalArgumentException("source snapshot over limit");
        return Optional.of(snapshot);
    }
    public static boolean sameSource(Optional<ItemSnapshot> a, Optional<ItemSnapshot> b) {
        return a.isEmpty()?b.isEmpty():b.isPresent()&&a.orElseThrow().fingerprint().equals(b.orElseThrow().fingerprint())&&a.orElseThrow().facts().equals(b.orElseThrow().facts());
    }
    /** Owner-thread final capture. Nothing is consumed; the executor rechecks these exact snapshots again at commit. */
    public AttemptPlan prepareAttempt(Player player, GuiSessionStore.State state, RuntimeStore.Snapshot<UpgraderRuntime> definition,
                                      Loaded loaded) {
        if(!runtime.isCurrent(definition.revision())||!cache.current(loaded.index())||state.busy())
            throw new IllegalArgumentException("stale upgrade view");
        var previous=loaded.preview().quote().flatMap(q->q.quote()).orElseThrow(()->new IllegalArgumentException("missing quote"));
        Observed observed=observe(player,definition,state.context());
        ItemSnapshot source=observed.source().orElseThrow(()->new IllegalArgumentException("missing source"));
        var validation=new UpgradeQuoteService().revalidate(previous,state.handle().session(),source,loaded.index(),observed.access(),
                definition.value().upgradeRules(),observed.resources(),Instant.now());
        if(validation.status()!=UpgradeQuoteService.ValidationStatus.VALID_PREVIEW)
            throw new IllegalArgumentException("quote revalidation: "+validation.status());
        Map<Integer,ItemSnapshot> fees=new HashMap<>();
        for(var allocation:validation.replacement().orElseThrow().resources().allocations()) {
            ItemStack stack=player.getInventory().getItem(allocation.slot());
            if(stack==null||stack.getType().isAir())throw new IllegalArgumentException("fee item disappeared");
            var key=ItemKey.of(allocation.resource().key());
            var identified=identities.identify(stack,definition).key();
            if(identified.isEmpty()||!identified.orElseThrow().equals(key))throw new IllegalArgumentException("fee identity changed");
            fees.put(allocation.slot(),snapshots.capture(stack,key));
        }
        var target=loaded.index().ready().get(previous.request().targetId());
        if(target==null)throw new IllegalArgumentException("target no longer ready");
        return new AttemptPlanner().prepare(validation,source,target.snapshot(),fees,Instant.now());
    }
    private static boolean same(Observed a, Observed b) { return sameSource(a.source(),b.source())&&Objects.equals(a.access(),b.access())&&Objects.equals(a.resources(),b.resources()); }
    private void report(RuntimeException error) { owner.getLogger().fine("GUI preview rejected: "+error.getClass().getSimpleName()); }
}
