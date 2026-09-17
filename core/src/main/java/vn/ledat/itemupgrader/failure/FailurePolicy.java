package vn.ledat.itemupgrader.failure;

import java.util.Objects;
import vn.ledat.itemupgrader.item.ItemKey;
import vn.ledat.itemupgrader.profile.RiskProfile.FailureMode;
import vn.ledat.itemupgrader.transfer.TransferPolicy;

/** Explicit immutable loss terms, pinned into the quote and journal. No arbitrary scripts/commands. */
public sealed interface FailurePolicy permits FailurePolicy.Destroy, FailurePolicy.Keep, FailurePolicy.Damage, FailurePolicy.Downgrade {
    FailureMode mode();
    record Destroy() implements FailurePolicy { @Override public FailureMode mode() { return FailureMode.DESTROY; } }
    record Keep() implements FailurePolicy { @Override public FailureMode mode() { return FailureMode.KEEP; } }
    enum BreakBehavior { DESTROY, CLAMP_ONE }
    /** Basis points of MAX durability, rounded up. 2000 = add 20% damage, not remove 20% remaining life. */
    record Damage(int basisPoints, BreakBehavior breakBehavior) implements FailurePolicy {
        public Damage { if (basisPoints < 1 || basisPoints > 10000) throw new IllegalArgumentException("damage basis points 1..10000"); Objects.requireNonNull(breakBehavior); }
        @Override public FailureMode mode() { return FailureMode.DAMAGE; }
    }
    /** Creates an explicit lower-value template; never mutates material/id on the original item. */
    record Downgrade(ItemKey key, int amount, TransferPolicy transfer) implements FailurePolicy {
        public Downgrade { Objects.requireNonNull(key); Objects.requireNonNull(transfer); if (amount < 1 || amount > 64) throw new IllegalArgumentException("downgrade amount 1..64"); }
        @Override public FailureMode mode() { return FailureMode.DOWNGRADE; }
    }
}
