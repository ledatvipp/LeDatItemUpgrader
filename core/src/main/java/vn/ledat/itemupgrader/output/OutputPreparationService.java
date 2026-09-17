package vn.ledat.itemupgrader.output;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.function.BiConsumer;
import vn.ledat.itemupgrader.transaction.model.AttemptPlan;
import vn.ledat.itemupgrader.transaction.storage.JournalCodec;
import vn.ledat.itemupgrader.output.storage.OutputStore;

/** Must only be called for the engine's durable PREPARE_OUTPUT intent after its player/ledger claim.
 * A stopped worker, cancelled UI, timeout or crash never grants permission to rerun a pending creation.
 */
public final class OutputPreparationService {
    public enum Status { READY, REJECTED, AMBIGUOUS, CONFLICT }
    public record Result(Status status,Optional<PreparedOutput> output,String reason) {
        public Result { Objects.requireNonNull(status);Objects.requireNonNull(output);Objects.requireNonNull(reason);if((status==Status.READY)!=output.isPresent())throw new IllegalArgumentException("invalid preparation result"); }
    }
    private final OutputStore store;
    private final Function<AttemptPlan,CompletionStage<PreparedOutput>> factory;
    private final Executor worker;
    private final Clock clock;
    private final BiConsumer<String,Throwable> diagnostics;
    public OutputPreparationService(OutputStore store,Function<AttemptPlan,CompletionStage<PreparedOutput>> factory,Executor worker,Clock clock,BiConsumer<String,Throwable> diagnostics) {
        this.store=Objects.requireNonNull(store);this.factory=Objects.requireNonNull(factory);this.worker=Objects.requireNonNull(worker);this.clock=Objects.requireNonNull(clock);this.diagnostics=Objects.requireNonNull(diagnostics);
    }
    public CompletionStage<Result> prepare(AttemptPlan plan) {
        if(plan.terms().output().isEmpty())return CompletableFuture.completedFuture(new Result(Status.REJECTED,Optional.empty(),"LEGACY_PLAN"));
        var pending=OutputStore.Row.pending(plan.attemptId(),plan.playerId(),JournalCodec.planDigest(plan),clock.instant());
        // Isolate cancellation so a GUI caller cannot stop the winner between creation and persistence.
        CompletableFuture<Result> operation=store.claim(pending).thenComposeAsync(claim->{
            if(claim.created()&&!claim.row().equals(pending))throw new IllegalStateException("output store acknowledged a different claim");
            if(!claim.created())return CompletableFuture.completedFuture(existing(plan,claim.row()));
            CompletionStage<PreparedOutput> created;
            try {
                if(!clock.instant().isBefore(plan.expiresAt()))throw new OutputRejectedException(OutputRejectedException.Code.EXPIRED,"quote expired");
                created=Objects.requireNonNull(factory.apply(plan));
            }catch(RuntimeException error){created=CompletableFuture.failedFuture(error);}
            return created.handleAsync((payload,error)->{
                if(error!=null) {
                    Throwable cause=unwrap(error);
                    boolean definite=cause instanceof OutputRejectedException;
                    if(!definite)report("output-create:"+plan.attemptId(),cause);
                    return new OutputStore.Row(pending.attemptId(),pending.playerId(),pending.planDigest(),definite?OutputStore.State.REJECTED:OutputStore.State.AMBIGUOUS,
                            Optional.empty(),definite?((OutputRejectedException)cause).code().name():"CREATION_UNCERTAIN",pending.createdAt());
                }
                try {
                    Objects.requireNonNull(payload).checkPlan(plan);
                    return new OutputStore.Row(pending.attemptId(),pending.playerId(),pending.planDigest(),OutputStore.State.READY,Optional.of(payload),"",pending.createdAt());
                }catch(RuntimeException invalid){
                    report("output-validate:"+plan.attemptId(),invalid);
                    return new OutputStore.Row(pending.attemptId(),pending.playerId(),pending.planDigest(),OutputStore.State.AMBIGUOUS,Optional.empty(),"INVALID_CREATION_RESULT",pending.createdAt());
                }
            },worker).thenComposeAsync(row->store.finish(pending,row).thenApplyAsync(saved->saved?existing(plan,row):new Result(Status.CONFLICT,Optional.empty(),"OUTPUT_CAS_CONFLICT"),worker),worker);
        },worker).toCompletableFuture();
        return operation.minimalCompletionStage();
    }
    public CompletionStage<Result> ready(AttemptPlan plan) {
        return store.find(plan.attemptId()).thenApplyAsync(row->row.map(value->existing(plan,value)).orElseGet(()->new Result(Status.CONFLICT,Optional.empty(),"OUTPUT_MISSING")),worker);
    }
    private static Result existing(AttemptPlan plan,OutputStore.Row row) {
        if(!row.attemptId().equals(plan.attemptId())||!row.planDigest().equals(JournalCodec.planDigest(plan))||!row.playerId().equals(plan.playerId()))return new Result(Status.CONFLICT,Optional.empty(),"OUTPUT_PLAN_CONFLICT");
        if(row.output().isPresent())row.output().orElseThrow().checkPlan(plan);
        return switch(row.state()) {
            case READY -> new Result(Status.READY,row.output(),"");
            case REJECTED -> new Result(Status.REJECTED,Optional.empty(),row.reason());
            case AMBIGUOUS -> new Result(Status.AMBIGUOUS,Optional.empty(),row.reason());
            case PREPARING -> new Result(Status.AMBIGUOUS,Optional.empty(),"PENDING_CREATION_DO_NOT_REPLAY");
        };
    }
    /** A broken diagnostic sink must not prevent the no-replay tombstone from being finalized. */
    private void report(String operation,Throwable error) {
        try { diagnostics.accept(operation,error); }
        catch(RuntimeException sinkError) {
            sinkError.addSuppressed(error);
            System.getLogger(OutputPreparationService.class.getName()).log(System.Logger.Level.ERROR,"Output diagnostics sink failed: "+operation,sinkError);
        }
    }
    private static Throwable unwrap(Throwable e){while(e instanceof CompletionException && e.getCause()!=null)e=e.getCause();return e;}
}
