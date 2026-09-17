package vn.ledat.itemupgrader.failure;

import java.util.OptionalInt;
import vn.ledat.itemupgrader.transfer.ItemDocument;

public final class FailurePlanner {
    public record DamageResult(boolean destroyed, OptionalInt damage) {
        public DamageResult { if (destroyed == damage.isPresent()) throw new IllegalArgumentException("damage result must be destroy XOR mutated item"); }
    }
    public DamageResult damage(ItemDocument source, FailurePolicy.Damage policy) {
        var facts = source.snapshot().facts();
        if (!source.capabilities().durabilityMutation() || facts.amount() != 1 || facts.maximumDamage() <= 0
                || facts.damage() >= facts.maximumDamage() || source.metadata().unbreakable())
            throw new IllegalArgumentException("DAMAGE requires a usable, breakable, unstackable damageable item");
        long delta = ((long) facts.maximumDamage() * policy.basisPoints() + 9999) / 10000;
        long next = facts.damage() + delta;
        if (next >= facts.maximumDamage() && policy.breakBehavior() == FailurePolicy.BreakBehavior.DESTROY)
            return new DamageResult(true, OptionalInt.empty());
        int result = (int) Math.min(next, facts.maximumDamage() - 1L);
        if (result <= facts.damage()) throw new IllegalArgumentException("DAMAGE would have no loss; choose DESTROY-on-break or another profile");
        return new DamageResult(false, OptionalInt.of(result));
    }
}
