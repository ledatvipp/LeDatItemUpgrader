package vn.ledat.itemupgrader.transaction.model;

import java.math.BigDecimal;
import java.util.Objects;

/** One deterministic external operation. The operation key must be bound to the full plan digest. */
public record Effect(Kind kind, int reference, BigDecimal amount) {
    public enum Kind { LEDGER_CLAIM, PREPARE_OUTPUT, HOLD_SOURCE, HOLD_FEE_ITEM, DEBIT_CURRENCY,
        RETURN_SOURCE, RETURN_FEE_ITEM, REFUND_CURRENCY, DELIVER_TARGET, DELIVER_FAILURE_OUTPUT, LEDGER_SUCCESS, LEDGER_FAIL }
    public Effect {
        Objects.requireNonNull(kind); Objects.requireNonNull(amount);
        if (amount.signum() < 0 || amount.precision() > 32 || amount.scale() < -16 || amount.scale() > 8
                || amount.compareTo(new BigDecimal("1000000000000")) > 0) throw new IllegalArgumentException("invalid effect amount");
        amount = amount.stripTrailingZeros();
        boolean referenced = switch (kind) {
            case HOLD_FEE_ITEM, RETURN_FEE_ITEM, DEBIT_CURRENCY, REFUND_CURRENCY -> true;
            default -> false;
        };
        if (referenced && (reference < 0 || reference > 35) || !referenced && reference != -1)
            throw new IllegalArgumentException("invalid effect reference");
        boolean quantified = referenced;
        if (quantified ? amount.signum() <= 0 : amount.signum() != 0) throw new IllegalArgumentException("invalid effect quantity");
    }
    public static Effect simple(Kind kind) { return new Effect(kind, -1, BigDecimal.ZERO); }
}
