package vn.ledat.itemupgrader.transfer;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;

/** Computes a selective patch; never changes a snapshot or reads the current live config. */
public final class TransferPlanner {
    public MetadataPatch plan(ItemDocument source, ItemDocument target, TransferPolicy policy) {
        if (policy.empty()) return MetadataPatch.none();
        var caps = target.capabilities();
        if (!caps.vanillaTransfer()) throw new IllegalArgumentException("adapter does not permit selective transfer");
        var enchantments = new TreeMap<>(target.snapshot().facts().enchantments());
        for (var allowed : policy.enchantments().entrySet()) {
            Integer level = source.snapshot().facts().enchantments().get(allowed.getKey());
            if (level == null) continue;
            Integer maximum = caps.applicableEnchants().get(allowed.getKey());
            if (maximum == null || level > Math.min(maximum, allowed.getValue()))
                throw new IllegalArgumentException("inapplicable/over-limit source enchant: " + allowed.getKey());
            enchantments.merge(allowed.getKey(), level, Math::max);
        }
        for (var conflict : caps.conflicts()) if (enchantments.containsKey(conflict.first()) && enchantments.containsKey(conflict.second()))
            throw new IllegalArgumentException("conflicting transferred enchantments");
        Map<String,PdcValue> pdc = new TreeMap<>();
        for (var rule : policy.pdc().entrySet()) {
            if (caps.protectedPdc().contains(rule.getKey()) || caps.writablePdc().get(rule.getKey()) != rule.getValue())
                throw new IllegalArgumentException("PDC transfer is not attested safe: " + rule.getKey());
            var value = source.metadata().pdc().get(rule.getKey());
            if (value == null) continue;
            if (value.type() != rule.getValue()) throw new IllegalArgumentException("PDC type mismatch: " + rule.getKey());
            pdc.put(rule.getKey(), value);
        }
        OptionalInt damage = OptionalInt.empty();
        if (policy.durability() == TransferPolicy.Durability.DAMAGE_RATIO_CEIL) {
            var from = source.snapshot().facts(); var to = target.snapshot().facts();
            if (!caps.durabilityMutation() || from.maximumDamage() <= 0 || to.maximumDamage() <= 0
                    || from.amount() != 1 || to.amount() != 1 || from.damage() >= from.maximumDamage())
                throw new IllegalArgumentException("durability transfer requires two usable unstackable damageable items");
            long numerator = (long) from.damage() * to.maximumDamage();
            int result = (int) ((numerator + from.maximumDamage() - 1L) / from.maximumDamage());
            if (result >= to.maximumDamage()) throw new IllegalArgumentException("durability transfer would create a broken target");
            damage = OptionalInt.of(result);
        }
        return new MetadataPatch(policy.customName() ? source.metadata().customName() : Optional.empty(),
                policy.repairCost() ? OptionalInt.of(source.metadata().repairCost()) : OptionalInt.empty(), damage,
                enchantments.equals(target.snapshot().facts().enchantments()) ? Optional.empty() : Optional.of(enchantments), pdc);
    }
    /** Detect faulty adapters writing outside the allowed patch (including identity, quantity and provider state). */
    public static void verify(ItemDocument before, ItemDocument after, MetadataPatch patch) {
        var a = before.snapshot().facts(); var b = after.snapshot().facts();
        var expectedPdc = new TreeMap<>(before.metadata().pdc()); expectedPdc.putAll(patch.pdc());
        if (!before.capabilities().equals(after.capabilities()) || !a.key().equals(b.key()) || a.amount() != b.amount()
                || a.maximumDamage() != b.maximumDamage() || !a.rarity().equals(b.rarity()) || !b.risks().isEmpty()
                || b.damage() != patch.damage().orElse(a.damage())
                || !b.enchantments().equals(patch.enchantments().orElse(a.enchantments()))
                || !after.metadata().customName().equals(patch.customName().isPresent() ? patch.customName() : before.metadata().customName())
                || after.metadata().repairCost() != patch.repairCost().orElse(before.metadata().repairCost())
                || before.metadata().unbreakable() != after.metadata().unbreakable()
                || !after.metadata().pdc().equals(expectedPdc)
                || !before.metadata().protectedDigest().equals(after.metadata().protectedDigest())
                || !before.metadata().uniqueTokens().equals(after.metadata().uniqueTokens()))
            throw new IllegalArgumentException("native adapter mutated fields outside the authorized patch");
        if (patch.empty() && !before.snapshot().fingerprint().equals(after.snapshot().fingerprint()))
            throw new IllegalArgumentException("no-op mutation changed payload");
    }
}
