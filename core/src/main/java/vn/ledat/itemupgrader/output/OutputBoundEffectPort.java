package vn.ledat.itemupgrader.output;

import java.util.Objects;
import java.util.concurrent.*;
import vn.ledat.itemupgrader.transaction.*;
import vn.ledat.itemupgrader.transaction.model.*;
import vn.ledat.itemupgrader.output.storage.OutputCodec;

/** Mandatory decorator for live Phase-5 transactions. Native delegate never receives DELIVER_TARGET templates.
 * The delegate is still responsible for source/fee escrow, cost receipts, ledger and exact source refunds.
 */
public final class OutputBoundEffectPort implements EffectPort {
    private final OutputPreparationService outputs;
    private final EffectPort nativeEffects;
    private final PreparedDeliveryPort deliveries;
    private final Executor worker;
    public OutputBoundEffectPort(OutputPreparationService outputs,EffectPort nativeEffects,PreparedDeliveryPort deliveries,Executor worker) {
        this.outputs=Objects.requireNonNull(outputs);this.nativeEffects=Objects.requireNonNull(nativeEffects);this.deliveries=Objects.requireNonNull(deliveries);this.worker=Objects.requireNonNull(worker);
    }
    @Override public CompletionStage<EffectReceipt> execute(Call call) {
        TransactionMachine.validate(call.intent());
        var kind=call.effect().kind();var plan=call.intent().plan();
        if(kind==Effect.Kind.PREPARE_OUTPUT)return outputs.prepare(plan).thenApplyAsync(result->switch(result.status()) {
            case READY -> new EffectReceipt(EffectReceipt.Status.APPLIED,"output:"+OutputCodec.digest(result.output().orElseThrow()));
            case REJECTED -> new EffectReceipt(EffectReceipt.Status.NOT_APPLIED,result.reason());
            default -> EffectReceipt.unknown(result.reason());
        },worker);
        boolean prepared=plan.terms().output().isPresent();
        if(!prepared && kind==Effect.Kind.LEDGER_CLAIM)return CompletableFuture.completedFuture(new EffectReceipt(EffectReceipt.Status.NOT_APPLIED,"LEGACY_OUTPUT_REQUIRES_REVIEW"));
        if(!prepared && (kind==Effect.Kind.HOLD_SOURCE||kind==Effect.Kind.HOLD_FEE_ITEM||kind==Effect.Kind.DEBIT_CURRENCY||kind==Effect.Kind.DELIVER_TARGET||kind==Effect.Kind.DELIVER_FAILURE_OUTPUT))
            return CompletableFuture.completedFuture(EffectReceipt.unknown("LEGACY_OUTPUT_REQUIRES_REVIEW"));
        boolean requireReady=prepared && switch(kind) {
            case HOLD_SOURCE,HOLD_FEE_ITEM,DEBIT_CURRENCY,DELIVER_TARGET,DELIVER_FAILURE_OUTPUT -> true;
            default -> false;
        };
        if(!requireReady)return nativeEffects.execute(call);
        return outputs.ready(plan).thenComposeAsync(result->{
            if(result.status()!=OutputPreparationService.Status.READY)return CompletableFuture.completedFuture(EffectReceipt.unknown("PREPARED_OUTPUT_NOT_READY"));
            PreparedOutput output=result.output().orElseThrow();
            // The source must not be acquired after a different preparation receipt was substituted.
            String evidence="output:"+OutputCodec.digest(output);
            boolean acknowledged=call.intent().steps().stream().anyMatch(step->step.effect().kind()==Effect.Kind.PREPARE_OUTPUT
                    &&step.receipt().status()==EffectReceipt.Status.APPLIED&&step.receipt().evidence().equals(evidence));
            if(!acknowledged)return CompletableFuture.completedFuture(EffectReceipt.unknown("OUTPUT_RECEIPT_MISMATCH"));
            if(kind!=Effect.Kind.DELIVER_TARGET&&kind!=Effect.Kind.DELIVER_FAILURE_OUTPUT)return nativeEffects.execute(call);
            if(call.intent().sample()==null)return CompletableFuture.completedFuture(EffectReceipt.unknown("OUTCOME_NOT_COMMITTED"));
            boolean success=kind==Effect.Kind.DELIVER_TARGET;
            if(call.intent().successfulRoll()!=success)return CompletableFuture.completedFuture(EffectReceipt.unknown("OUTPUT_OUTCOME_MISMATCH"));
            var item=success?java.util.Optional.of(output.success()):output.failure();
            if(item.isEmpty())return CompletableFuture.completedFuture(new EffectReceipt(EffectReceipt.Status.APPLIED,"destroyed:"+OutputCodec.digest(output)));
            return deliveries.deliver(new PreparedDeliveryPort.Delivery(plan.playerId(),plan.attemptId(),call.planDigest(),call.operationKey(),OutputCodec.digest(output),
                    success?PreparedDeliveryPort.Role.SUCCESS:PreparedDeliveryPort.Role.FAILURE,item.orElseThrow()));
        },worker);
    }
}
