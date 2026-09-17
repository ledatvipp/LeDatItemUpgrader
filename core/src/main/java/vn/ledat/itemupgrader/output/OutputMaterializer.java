package vn.ledat.itemupgrader.output;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import vn.ledat.itemupgrader.failure.*;
import vn.ledat.itemupgrader.item.*;
import vn.ledat.itemupgrader.transaction.model.AttemptPlan;
import vn.ledat.itemupgrader.transaction.storage.JournalCodec;
import vn.ledat.itemupgrader.transfer.*;
import vn.ledat.itemupgrader.value.*;
import static vn.ledat.itemupgrader.output.OutputRejectedException.Code.*;

/** Async composition over detached facts. All native operations are delegated; no common pool or blocking join.
 * Called once by the winner of a durable output claim. A pending claim must never invoke it again after a crash.
 */
public final class OutputMaterializer {
    private final ItemMutationPort items;
    private final ValueDefinitions values;
    private final long revision;
    private final Executor worker;
    private final Clock clock;
    public OutputMaterializer(ItemMutationPort items, ValueDefinitions values, long revision, Executor worker, Clock clock) {
        this.items=Objects.requireNonNull(items); this.values=Objects.requireNonNull(values); this.worker=Objects.requireNonNull(worker); this.clock=Objects.requireNonNull(clock);
        if(revision<1)throw new IllegalArgumentException("invalid output materializer revision");this.revision=revision;
    }
    public CompletionStage<PreparedOutput> materialize(AttemptPlan plan) {
        if(plan.terms().output().isEmpty())throw reject(LEGACY_PLAN,"legacy plan cannot materialize automatically");
        if(plan.configRevision()!=revision)throw reject(RECONFIRM_REQUIRED,"output config revision changed");
        if(!clock.instant().isBefore(plan.expiresAt()))throw reject(EXPIRED,"quote expired before output creation");
        var context=new ItemMutationPort.Context(plan.playerId(),plan.attemptId(),JournalCodec.planDigest(plan));
        var spec=plan.terms().output().orElseThrow();
        return items.inspect(context,plan.source()).thenComposeAsync(source->{
            exact(plan.source(),source);
            return items.inspect(context,plan.target()).thenComposeAsync(template->{
                exact(plan.target(),template); requirePure(template);
                return items.createFresh(context,plan.target().facts().key(),plan.target().facts().amount()).thenComposeAsync(fresh->{
                    fresh(plan.target().facts().key(),plan.target().facts().amount(),fresh);
                    if(!Collections.disjoint(fresh.metadata().uniqueTokens(),source.metadata().uniqueTokens())
                            ||!Collections.disjoint(fresh.metadata().uniqueTokens(),template.metadata().uniqueTokens()))
                        throw reject(REUSED_IDENTITY,"provider creation reused source/catalog unique identity");
                    // A custom adapter must attest that fresh construction matches the intended template aside from unique IDs.
                    if(!fresh.metadata().templateDigest().equals(template.metadata().templateDigest()))
                        throw reject(RECONFIRM_REQUIRED,"fresh provider template differs from the quoted template");
                    return transfer(context,source,fresh,spec.transfer()).thenComposeAsync(success->{
                        if(price(success.snapshot()).compareTo(plan.targetTotal())!=0)
                            throw reject(RECONFIRM_REQUIRED,"materialized target value differs; do not silently change odds");
                        return failure(context,source,spec.failure()).thenApplyAsync(loss->{
                            if(spec.failure() instanceof FailurePolicy.Downgrade && price(loss.orElseThrow().snapshot()).compareTo(plan.sourceTotal())>=0)
                                throw reject(INVALID_FAILURE,"downgrade must have strictly lower total value after transfer");
                            Set<String> ids=spec.failure() instanceof FailurePolicy.Downgrade ? loss.orElseThrow().metadata().uniqueTokens():Set.of();
                            if(!Collections.disjoint(success.metadata().uniqueTokens(),ids))throw reject(REUSED_IDENTITY,"outcome candidates share a unique identity");
                            var prepared=new PreparedOutput(plan.attemptId(),plan.playerId(),context.planDigest(),success.snapshot(),loss.map(ItemDocument::snapshot),
                                    success.metadata().uniqueTokens(),ids,clock.instant());
                            prepared.checkPlan(plan); return prepared;
                        },worker);
                    },worker);
                },worker);
            },worker);
        },worker);
    }
    private CompletionStage<Optional<ItemDocument>> failure(ItemMutationPort.Context context,ItemDocument source,FailurePolicy policy) {
        return switch(policy) {
            case FailurePolicy.Destroy ignored -> CompletableFuture.completedFuture(Optional.empty());
            case FailurePolicy.Keep ignored -> CompletableFuture.completedFuture(Optional.of(source));
            case FailurePolicy.Damage p -> {
                final FailurePlanner.DamageResult result;
                try{result=new FailurePlanner().damage(source,p);}catch(IllegalArgumentException error){throw reject(INVALID_FAILURE,error.getMessage());}
                if(result.destroyed())yield CompletableFuture.completedFuture(Optional.empty());
                var patch=new MetadataPatch(Optional.empty(),OptionalInt.empty(),result.damage(),Optional.empty(),Map.of());
                yield mutate(context,source,patch).thenApplyAsync(Optional::of,worker);
            }
            case FailurePolicy.Downgrade p -> {
                if(p.key().equals(source.snapshot().facts().key()))throw reject(INVALID_FAILURE,"downgrade cannot use the source identity key");
                yield items.createFresh(context,p.key(),p.amount()).thenComposeAsync(fresh->{
                    fresh(p.key(),p.amount(),fresh);
                    if(!Collections.disjoint(source.metadata().uniqueTokens(),fresh.metadata().uniqueTokens()))throw reject(REUSED_IDENTITY,"downgrade reused source unique identity");
                    return transfer(context,source,fresh,p.transfer()).thenApplyAsync(Optional::of,worker);
                },worker);
            }
        };
    }
    private CompletionStage<ItemDocument> transfer(ItemMutationPort.Context context,ItemDocument source,ItemDocument target,TransferPolicy policy) {
        final MetadataPatch patch;
        try{patch=new TransferPlanner().plan(source,target,policy);}catch(IllegalArgumentException error){throw reject(UNSAFE_TRANSFER,error.getMessage());}
        return mutate(context,target,patch);
    }
    private CompletionStage<ItemDocument> mutate(ItemMutationPort.Context context,ItemDocument target,MetadataPatch patch) {
        if(patch.empty())return CompletableFuture.completedFuture(target);
        return items.apply(context,target,patch).thenApplyAsync(after->{
            try{TransferPlanner.verify(target,after,patch);}catch(IllegalArgumentException error){throw reject(UNSAFE_TRANSFER,error.getMessage());}
            return after;
        },worker);
    }
    private java.math.BigDecimal price(ItemSnapshot item) {
        var quote=new ItemValueService().evaluate(item,values,revision).quote();
        return quote.orElseThrow(()->reject(UNKNOWN_VALUE,"prepared item has no safe configured value")).totalValue();
    }
    private static void exact(ItemSnapshot snapshot,ItemDocument document) {
        if(!snapshot.facts().equals(document.snapshot().facts())||!snapshot.fingerprint().equals(document.snapshot().fingerprint()))
            throw reject(INVALID_SNAPSHOT,"native round-trip differs from pinned source/template");
    }
    private static void requirePure(ItemDocument document) {
        if(!document.capabilities().pureFreshCreation())throw reject(CAPABILITY_UNAVAILABLE,"provider creation is not attested side-effect-free");
    }
    private static void fresh(ItemKey key,int amount,ItemDocument document) {
        requirePure(document);
        if(!key.equals(document.snapshot().facts().key())||amount!=document.snapshot().facts().amount())throw reject(INVALID_SNAPSHOT,"provider created wrong item/quantity");
    }
    private static OutputRejectedException reject(OutputRejectedException.Code code,String message){return new OutputRejectedException(code,message);}
}
