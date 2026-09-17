package vn.ledat.itemupgrader.history;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import vn.ledat.itemupgrader.item.ItemKey;
import vn.ledat.itemupgrader.chance.Probability;
import vn.ledat.itemupgrader.transaction.TransactionMachine;
import vn.ledat.itemupgrader.transaction.model.AttemptRecord;
import vn.ledat.itemupgrader.transaction.model.AttemptPlan;
/** Public projection only: no raw sample, serialized item bytes, receipt tokens or arbitrary player text. */
public record HistoryEntry(UUID transactionId,UUID playerId,long version,AttemptRecord.State state,Outcome outcome,
        ItemKey sourceKey,int sourceAmount,String targetId,ItemKey targetKey,int targetAmount,
        BigDecimal sourceValue,BigDecimal targetValue,Probability probability,String profile,String failure,
        Instant createdAt,Instant updatedAt) {
    public enum Outcome { NOT_ROLLED, WIN, LOSS }
    public HistoryEntry {
        Objects.requireNonNull(transactionId);Objects.requireNonNull(playerId);Objects.requireNonNull(state);Objects.requireNonNull(outcome);
        Objects.requireNonNull(sourceKey);Objects.requireNonNull(targetKey);Objects.requireNonNull(probability);
        Objects.requireNonNull(createdAt);Objects.requireNonNull(updatedAt);AttemptPlan.id(targetId);AttemptPlan.id(profile);
        if(version<0||version>10000||sourceAmount<1||sourceAmount>4096||targetAmount<1||targetAmount>4096||updatedAt.isBefore(createdAt)
                ||createdAt.toEpochMilli()<0)throw new IllegalArgumentException("invalid history fields");
        if(sourceValue==null||targetValue==null||sourceValue.signum()<=0||targetValue.compareTo(sourceValue)<=0
                ||sourceValue.precision()>32||targetValue.precision()>32||sourceValue.scale() < -16||targetValue.scale() < -16
                ||sourceValue.scale()>8||targetValue.scale()>8||targetValue.compareTo(new BigDecimal("100000000000000000"))>0)
            throw new IllegalArgumentException("invalid history values");
        sourceValue=sourceValue.stripTrailingZeros();targetValue=targetValue.stripTrailingZeros();
        vn.ledat.itemupgrader.profile.RiskProfile.FailureMode.valueOf(failure);
        boolean drawn=Set.of(AttemptRecord.State.OUTCOME_COMMITTED,AttemptRecord.State.SETTLING,AttemptRecord.State.COMPLETED).contains(state);
        boolean undrawn=Set.of(AttemptRecord.State.PREPARED,AttemptRecord.State.RESERVING,AttemptRecord.State.DRAW_INTENT,
                AttemptRecord.State.COMPENSATING,AttemptRecord.State.ABORTED).contains(state);
        if(drawn&&outcome==Outcome.NOT_ROLLED||undrawn&&outcome!=Outcome.NOT_ROLLED)throw new IllegalArgumentException("history outcome/state mismatch");
    }
    public static HistoryEntry from(AttemptRecord record) {
        TransactionMachine.validate(record);var p=record.plan();
        return new HistoryEntry(record.id(),p.playerId(),record.version(),record.state(),record.sample()==null?Outcome.NOT_ROLLED:
                record.successfulRoll()?Outcome.WIN:Outcome.LOSS,p.source().facts().key(),p.source().facts().amount(),p.targetId(),
                p.target().facts().key(),p.target().facts().amount(),p.sourceTotal(),p.targetTotal(),p.terms().probability(),
                p.terms().profileId(),p.terms().failure().name(),record.createdAt(),record.updatedAt());
    }
    public boolean settled(){return state==AttemptRecord.State.COMPLETED;}
    public boolean terminal(){return settled()||state==AttemptRecord.State.ABORTED;}
}
