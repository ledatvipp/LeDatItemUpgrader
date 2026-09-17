package vn.ledat.itemupgrader.transaction;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import vn.ledat.itemupgrader.transaction.model.*;
import vn.ledat.itemupgrader.transaction.storage.*;

/** Generic transaction driver. Does not own a thread pool and never calls Bukkit/economy directly.
 * No automatic effect replay, refund-on-exception, timeout unlock, or re-roll of a durable DRAW_INTENT.
 * Every async continuation uses the supplied worker, never the common pool. Native effects route through EffectPort.
 */
public final class UpgradeTransactionEngine {
    public enum Status { COMPLETED, ABORTED, DUPLICATE, IDEMPOTENCY_CONFLICT, PLAYER_BUSY, CAPACITY,
        STOPPED, EXPIRED, CONFLICT, RECONCILIATION_REQUIRED, STORAGE_OR_DRIVER_ERROR }
    public record Result(Status status, Optional<AttemptRecord> record) {
        public Result { Objects.requireNonNull(status); Objects.requireNonNull(record); }
    }
    private final AsyncTransactionJournal journal;
    private final EffectPort effects;
    private final TicketSource random;
    private final Executor worker;
    private final Clock clock;
    private final AttemptRunGate gate;
    private final BiConsumer<String,Throwable> diagnostics;
    private final TransactionMachine machine=new TransactionMachine();
    public UpgradeTransactionEngine(AsyncTransactionJournal journal, EffectPort effects, TicketSource random,
            Executor worker, Clock clock, int maxInFlight, BiConsumer<String,Throwable> diagnostics) {
        this.journal=Objects.requireNonNull(journal);this.effects=Objects.requireNonNull(effects);this.random=Objects.requireNonNull(random);
        this.worker=Objects.requireNonNull(worker);this.clock=Objects.requireNonNull(clock);this.gate=new AttemptRunGate(maxInFlight);
        this.diagnostics=Objects.requireNonNull(diagnostics);
    }
    public CompletionStage<Result> submit(AttemptPlan plan) {
        Objects.requireNonNull(plan);
        return admitted(plan,()->journal.claim(plan,clock.instant()).toCompletableFuture().thenComposeAsync(claim->switch(claim.status()) {
            case CREATED -> drive(claim.record().orElseThrow());
            case DUPLICATE -> done(Status.DUPLICATE,claim.record().orElseThrow());
            case IDEMPOTENCY_CONFLICT -> done(Status.IDEMPOTENCY_CONFLICT,claim.record().orElse(null));
            case PLAYER_BUSY -> done(Status.PLAYER_BUSY,null);
            case EXPIRED -> done(Status.EXPIRED,null);
        },worker));
    }
    /** Only for a recovery operator after the old executor/process has been fenced/quiesced. Not a player retry API. */
    public CompletionStage<Result> resumeQuiescent(AttemptRecord candidate) {
        Objects.requireNonNull(candidate);
        return admitted(candidate.plan(),()->journal.find(candidate.id()).toCompletableFuture().thenComposeAsync(optional->{
            if(optional.isEmpty())return done(Status.CONFLICT,null);
            var current=optional.orElseThrow();
            if(!Arrays.equals(JournalCodec.encodeRecord(candidate),JournalCodec.encodeRecord(current)))return done(Status.CONFLICT,current);
            var action=new RecoveryPlanner().inspect(current).action();
            if(action==RecoveryPlanner.Action.NONE)return terminal(current);
            if(action!=RecoveryPlanner.Action.RESUME_WITH_SAME_PLAN)return done(Status.RECONCILIATION_REQUIRED,current);
            return drive(current);
        },worker));
    }
    @FunctionalInterface private interface Start { CompletableFuture<Result> get(); }
    private CompletionStage<Result> admitted(AttemptPlan plan,Start start) {
        var admission=gate.enter(plan.playerId(),plan.attemptId());
        if(admission.status()!=AttemptRunGate.Status.ENTERED)return done(switch(admission.status()) {
            case STOPPED->Status.STOPPED;case CAPACITY->Status.CAPACITY;default->Status.PLAYER_BUSY;
        },null).minimalCompletionStage();
        var ticket=admission.ticket().orElseThrow();
        CompletableFuture<Result> operation;
        try { operation=start.get(); } catch(RuntimeException e){operation=CompletableFuture.failedFuture(e);}
        CompletableFuture<Result> returned=new CompletableFuture<>();
        operation.whenComplete((result,error)->{
            Throwable failure=null;Result completion=result;
            if(error!=null) {
                try { diagnostics.accept("transaction-driver",unwrap(error));
                    completion=new Result(Status.STORAGE_OR_DRIVER_ERROR,Optional.empty()); }
                catch(RuntimeException reportingError){failure=reportingError;}
            }
            gate.leave(ticket);
            if(failure!=null)returned.completeExceptionally(failure);else returned.complete(completion);
        });
        // Caller cancellation only cancels its view; never cancels a debit/ack task or unlocks an in-flight attempt.
        return returned.minimalCompletionStage();
    }
    private CompletableFuture<Result> drive(AttemptRecord record) {
        if(record.terminal())return terminal(record);
        if(!gate.accepting())return done(Status.STOPPED,record);
        var decision=machine.next(record,clock.instant());
        if(decision.stopped())return done(Status.RECONCILIATION_REQUIRED,record);
        return journal.compareAndSet(record,decision.next()).toCompletableFuture().thenComposeAsync(saved->{
            if(!saved)return done(Status.CONFLICT,record);
            var next=decision.next();
            return switch(decision.work()) {
                case NONE->drive(next);
                case DRAW->draw(next);
                case EFFECT->effect(next);
            };
        },worker);
    }
    private CompletableFuture<Result> draw(AttemptRecord intent) {
        final AttemptRecord committed;
        try { committed=machine.commitDraw(intent,random.draw(),clock.instant()); }
        catch(RuntimeException e) {
            diagnostics.accept("draw-failed",e);
            var frozen=machine.quarantine(intent,clock.instant(),"DRAW_FAILED_DO_NOT_REDRAW");
            return journal.compareAndSet(intent,frozen).toCompletableFuture().thenApplyAsync(saved->
                    new Result(saved?Status.RECONCILIATION_REQUIRED:Status.CONFLICT,Optional.of(saved?frozen:intent)),worker);
        }
        return journal.compareAndSet(intent,committed).toCompletableFuture().thenComposeAsync(saved->
                saved?drive(committed):done(Status.CONFLICT,intent),worker);
    }
    private CompletableFuture<Result> effect(AttemptRecord intent) {
        int ordinal=intent.steps().size()-1;
        if(!gate.accepting())return acknowledge(intent,ordinal,new EffectReceipt(EffectReceipt.Status.NOT_APPLIED,"stopped-before-dispatch"));
        CompletionStage<EffectReceipt> response;
        try { response=Objects.requireNonNull(effects.execute(new EffectPort.Call(intent,ordinal,JournalCodec.planDigest(intent.plan())))); }
        catch(RuntimeException error) {
            diagnostics.accept("effect-dispatch-failed",error);
            response=CompletableFuture.completedFuture(EffectReceipt.unknown("dispatch-exception"));
        }
        return response.handleAsync((receipt,error)->{
            if(error!=null) {
                diagnostics.accept("effect-result-failed",unwrap(error));
                return EffectReceipt.unknown("effect-exception");
            }
            if(receipt==null || receipt.status()==EffectReceipt.Status.INTENT) {
                diagnostics.accept("invalid-effect-reply",new IllegalStateException("adapter did not provide a final receipt"));
                return EffectReceipt.unknown("invalid-adapter-reply");
            }
            return receipt;
        },worker).thenComposeAsync(receipt->acknowledge(intent,ordinal,receipt),worker).toCompletableFuture();
    }
    private CompletableFuture<Result> acknowledge(AttemptRecord intent,int ordinal,EffectReceipt receipt) {
        // Even when stop was requested, persist the actual result of the original in-flight operation.
        var ack=machine.receipt(intent,ordinal,receipt,clock.instant());
        return journal.compareAndSet(intent,ack).toCompletableFuture().thenComposeAsync(saved->
                saved?drive(ack):done(Status.CONFLICT,intent),worker);
    }
    private CompletableFuture<Result> terminal(AttemptRecord r){return done(r.state()==AttemptRecord.State.COMPLETED?Status.COMPLETED:Status.ABORTED,r);}
    private static CompletableFuture<Result> done(Status status,AttemptRecord record){return CompletableFuture.completedFuture(new Result(status,Optional.ofNullable(record)));}
    private static Throwable unwrap(Throwable error){return error instanceof CompletionException && error.getCause()!=null?error.getCause():error;}
    public CompletionStage<Void> stop(){return gate.stop();}
    public int inFlight(){return gate.inFlight();}
}
