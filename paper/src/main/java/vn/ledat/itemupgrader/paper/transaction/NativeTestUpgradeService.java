package vn.ledat.itemupgrader.paper.transaction;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import vn.ledat.itemupgrader.cost.CostResource;
import vn.ledat.itemupgrader.failure.FailurePolicy;
import vn.ledat.itemupgrader.item.ItemKey;
import vn.ledat.itemupgrader.paper.item.PaperItemSnapshotFactory;
import vn.ledat.itemupgrader.paper.item.PlatformIdentityIndex;
import vn.ledat.itemupgrader.paper.platform.PlatformAccess;
import vn.ledat.itemupgrader.runtime.RuntimeStore;
import vn.ledat.itemupgrader.runtime.UpgraderRuntime;
import vn.ledat.itemupgrader.transaction.model.AttemptPlan;

/**
 * Opt-in single-server test executor. It commits only after the title roll finishes and supports the
 * bundled CLEAN + DESTROY/KEEP policies. It deliberately rejects advanced metadata/failure policies.
 */
public final class NativeTestUpgradeService {
    public record Prepared(AttemptPlan plan, int sample, boolean success, Optional<ItemStack> output) {
        public Prepared { Objects.requireNonNull(plan); Objects.requireNonNull(output); }
    }
    private record Debit(String provider, BigDecimal amount) {}
    private final PlatformAccess platform;
    private final PlatformIdentityIndex identities;
    private final PaperItemSnapshotFactory snapshots=new PaperItemSnapshotFactory();
    private final SecureRandom random=new SecureRandom();

    public NativeTestUpgradeService(PlatformAccess platform,PlatformIdentityIndex identities) {
        this.platform=Objects.requireNonNull(platform);this.identities=Objects.requireNonNull(identities);
    }

    public Prepared prepare(Player player,RuntimeStore.Snapshot<UpgraderRuntime> runtime,AttemptPlan plan) {
        if(!runtime.value().upgradesEnabled())throw new IllegalArgumentException("live test upgrades disabled");
        if(!player.getUniqueId().equals(plan.playerId())||!Instant.now().isBefore(plan.expiresAt()))
            throw new IllegalArgumentException("attempt expired or wrong player");
        var spec=plan.terms().output().orElseThrow(()->new IllegalArgumentException("output policy unavailable"));
        if(!spec.transfer().empty())throw new IllegalArgumentException("test executor supports CLEAN transfer only");
        if(!(spec.failure() instanceof FailurePolicy.Destroy||spec.failure() instanceof FailurePolicy.Keep))
            throw new IllegalArgumentException("test executor supports DESTROY/KEEP only");
        int sample=random.nextInt(1_000_000_000);
        boolean success=sample<plan.terms().probability().winningTickets();
        Optional<ItemStack> output=Optional.empty();
        if(success) {
            ItemKey key=plan.target().facts().key(); int amount=plan.target().facts().amount();
            ItemStack created=platform.create(key.value(),amount).orElseThrow(()->new IllegalArgumentException("target provider could not create output"));
            var identity=identities.identify(created,runtime).key();
            if(identity.isEmpty()||!identity.orElseThrow().equals(key))throw new IllegalArgumentException("created target identity mismatch");
            var captured=snapshots.capture(created,key);
            if(captured.facts().amount()!=amount||!captured.facts().risks().isEmpty())throw new IllegalArgumentException("created target is unsafe");
            output=Optional.of(created.clone());
        }
        return new Prepared(plan,sample,success,output);
    }

    public void commit(Player player,RuntimeStore.Snapshot<UpgraderRuntime> runtime,Prepared prepared) {
        AttemptPlan plan=prepared.plan();
        if(!runtime.value().upgradesEnabled()||runtime.revision()!=plan.configRevision()||!player.getUniqueId().equals(plan.playerId()))
            throw new IllegalArgumentException("attempt runtime/player changed");
        ItemStack[] original=cloneContents(player.getInventory().getStorageContents());
        ItemStack[] next=cloneContents(original);
        verifySource(next,plan);
        verifyFees(next,plan,runtime);
        if(prepared.success()||plan.terms().failure()==vn.ledat.itemupgrader.profile.RiskProfile.FailureMode.DESTROY)
            remove(next,plan.sourceSlot(),plan.source().facts().amount());
        consumeFeeItems(next,plan,prepared.success());
        if(prepared.success()&&!insert(next,prepared.output().orElseThrow().clone()))
            throw new IllegalArgumentException("inventory has no room for target output");

        List<Debit> debits=currencyDebits(plan,prepared.success());
        for(Debit debit:debits) {
            var balance=platform.balance(player,debit.provider());
            if(balance.isEmpty()||balance.orElseThrow().compareTo(debit.amount())<0)
                throw new IllegalArgumentException("currency balance changed");
        }
        player.getInventory().setStorageContents(next);
        List<Debit> applied=new ArrayList<>();
        try {
            for(Debit debit:debits) {
                if(!platform.withdraw(player,debit.provider(),debit.amount()))throw new IllegalStateException("economy withdraw rejected");
                applied.add(debit);
            }
        } catch(RuntimeException failure) {
            player.getInventory().setStorageContents(original);
            boolean restored=true;
            for(int i=applied.size()-1;i>=0;i--) {
                Debit debit=applied.get(i);
                try { restored&=platform.deposit(player,debit.provider(),debit.amount()); }
                catch(RuntimeException ignored) { restored=false; }
            }
            if(!restored)throw new IllegalStateException("economy rollback failed; administrator intervention required",failure);
            throw failure;
        }
    }

    private void verifySource(ItemStack[] contents,AttemptPlan plan) {
        ItemStack item=contents[plan.sourceSlot()];
        if(item==null||item.getType().isAir()||item.getAmount()<plan.source().facts().amount())throw new IllegalArgumentException("source disappeared");
        // prepareAttempt already verified identity; the exact serialized selected snapshot below prevents slot substitution.
        ItemStack selected=item.clone();selected.setAmount(plan.source().facts().amount());
        var snapshot=snapshots.capture(selected,plan.source().facts().key());
        if(!snapshot.fingerprint().equals(plan.source().fingerprint())||!snapshot.facts().equals(plan.source().facts()))
            throw new IllegalArgumentException("source changed before commit");
    }
    private void verifyFees(ItemStack[] contents,AttemptPlan plan,RuntimeStore.Snapshot<UpgraderRuntime> runtime) {
        for(var hold:plan.feeItems()) {
            ItemStack item=contents[hold.slot()];
            if(item==null||item.getType().isAir()||item.getAmount()<hold.amount())throw new IllegalArgumentException("fee item disappeared");
            var identity=identities.identify(item,runtime).key();
            if(identity.isEmpty()||!identity.orElseThrow().equals(hold.snapshot().facts().key()))throw new IllegalArgumentException("fee identity changed");
            var snapshot=snapshots.capture(item,hold.snapshot().facts().key());
            if(!snapshot.fingerprint().equals(hold.snapshot().fingerprint()))throw new IllegalArgumentException("fee item changed");
        }
    }
    private static void consumeFeeItems(ItemStack[] contents,AttemptPlan plan,boolean success) {
        Map<CostResource,Integer> remaining=new TreeMap<>();
        for(var line:plan.terms().costs().lines())if(line.resource().kind()==CostResource.Kind.ITEM)
            remaining.put(line.resource(),line.consumed(success).intValueExact());
        for(var hold:plan.feeItems()) {
            CostResource resource=CostResource.item(hold.snapshot().facts().key());
            int consume=Math.min(hold.amount(),remaining.getOrDefault(resource,0));
            if(consume>0){remove(contents,hold.slot(),consume);remaining.put(resource,remaining.get(resource)-consume);}
        }
        if(remaining.values().stream().anyMatch(amount->amount!=0))throw new IllegalArgumentException("fee allocation incomplete");
    }
    private static List<Debit> currencyDebits(AttemptPlan plan,boolean success) {
        List<Debit> result=new ArrayList<>();
        for(var line:plan.terms().costs().lines())if(line.resource().kind()==CostResource.Kind.CURRENCY) {
            BigDecimal amount=line.consumed(success);
            if(amount.signum()>0)result.add(new Debit(line.resource().key(),amount));
        }
        return List.copyOf(result);
    }
    private static void remove(ItemStack[] contents,int slot,int amount) {
        ItemStack item=contents[slot];
        if(item==null||item.getAmount()<amount)throw new IllegalArgumentException("inventory amount changed");
        if(item.getAmount()==amount)contents[slot]=null;
        else {item=item.clone();item.setAmount(item.getAmount()-amount);contents[slot]=item;}
    }
    private static boolean insert(ItemStack[] contents,ItemStack adding) {
        int remaining=adding.getAmount();
        for(int i=0;i<contents.length&&remaining>0;i++) {
            ItemStack current=contents[i];
            if(current==null||!current.isSimilar(adding))continue;
            int room=Math.max(0,current.getMaxStackSize()-current.getAmount());int moved=Math.min(room,remaining);
            if(moved>0){current=current.clone();current.setAmount(current.getAmount()+moved);contents[i]=current;remaining-=moved;}
        }
        for(int i=0;i<contents.length&&remaining>0;i++)if(contents[i]==null||contents[i].getType().isAir()) {
            int moved=Math.min(adding.getMaxStackSize(),remaining);ItemStack placed=adding.clone();placed.setAmount(moved);contents[i]=placed;remaining-=moved;
        }
        return remaining==0;
    }
    private static ItemStack[] cloneContents(ItemStack[] source) {
        ItemStack[] copy=new ItemStack[source.length];for(int i=0;i<source.length;i++)copy[i]=source[i]==null?null:source[i].clone();return copy;
    }
}
