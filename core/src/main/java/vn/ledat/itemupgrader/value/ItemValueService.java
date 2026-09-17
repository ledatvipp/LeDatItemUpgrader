package vn.ledat.itemupgrader.value;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import vn.ledat.itemupgrader.item.ItemFacts;
import vn.ledat.itemupgrader.item.ItemSnapshot;
import vn.ledat.itemupgrader.util.Decimals;

/** Stateless and thread-safe. Only immutable snapshots/definitions enter this service. */
public final class ItemValueService {
    public ValueResult evaluate(ItemSnapshot snapshot, ValueDefinitions definitions, long revision) {
        if (revision < 1) throw new IllegalArgumentException("invalid runtime revision");
        ItemFacts item = snapshot.facts();
        ValueLimits limits = definitions.limits();
        if (definitions.blockedItems().contains(item.key())) return fail(ValueResult.Status.BLOCKED_ITEM, item.key().value());
        if (!item.risks().isEmpty()) return fail(ValueResult.Status.UNSAFE_METADATA, item.risks().toString());
        if (item.amount() > limits.maxAmount()) return fail(ValueResult.Status.AMOUNT_LIMIT, "amount exceeds limit");
        if (snapshot.byteSize() > limits.maxSnapshotBytes()) return fail(ValueResult.Status.SNAPSHOT_LIMIT, "snapshot exceeds limit");
        if (item.enchantments().values().stream().anyMatch(level -> level > limits.maxEnchantLevel()))
            return fail(ValueResult.Status.UNSAFE_ENCHANTMENT, "enchantment level exceeds limit");
        BaseValueResolver resolver = new BaseValueResolver(definitions);
        try {
            var base = resolver.resolve(item);
            if (base.isEmpty()) return fail(ValueResult.Status.UNKNOWN_VALUE, "no explicit valuation resolved; " + String.join("; ", resolver.diagnostics()));
            BigDecimal raw = base.get().value();
            List<ValueResult.Adjustment> adjustments = new ArrayList<>();
            BigDecimal enchant = item.enchantments().entrySet().stream()
                    .map(entry -> definitions.modifiers().enchantPerLevel().getOrDefault(entry.getKey(), BigDecimal.ZERO)
                            .multiply(BigDecimal.valueOf(entry.getValue())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (enchant.signum() > 0) { adjustments.add(new ValueResult.Adjustment("enchant", raw, raw.add(enchant))); raw = raw.add(enchant); }
            // Explicit rarity multiplier is independent of base resolver; defaults are empty to avoid double counting.
            BigDecimal rarity = definitions.modifiers().rarityMultipliers().getOrDefault(item.rarity(), BigDecimal.ONE);
            if (rarity.compareTo(BigDecimal.ONE) != 0) {
                BigDecimal next = raw.multiply(rarity); adjustments.add(new ValueResult.Adjustment("rarity", raw, next)); raw = next;
            }
            if (definitions.modifiers().durabilityEnabled() && item.maximumDamage() > 0) {
                BigDecimal remaining = BigDecimal.valueOf(item.maximumDamage() - item.damage())
                        .divide(BigDecimal.valueOf(item.maximumDamage()), new MathContext(28, RoundingMode.DOWN));
                BigDecimal floor = definitions.modifiers().minimumDurabilityFactor();
                BigDecimal factor = floor.add(BigDecimal.ONE.subtract(floor).multiply(remaining));
                BigDecimal next = raw.multiply(factor);
                adjustments.add(new ValueResult.Adjustment("durability", raw, next)); raw = next;
            }
            if (raw.compareTo(limits.maxUnitValue()) > 0) return fail(ValueResult.Status.VALUE_LIMIT, "unit value above limit");
            // Round per unit, then multiply: splitting/merging stacks cannot increase quoted total value.
            BigDecimal unit = Decimals.floor(raw, limits.scale());
            if (unit.signum() <= 0) return fail(ValueResult.Status.ZERO_AFTER_ROUNDING, "value rounds to zero");
            BigDecimal total = unit.multiply(BigDecimal.valueOf(item.amount()));
            if (total.compareTo(limits.maxTotalValue()) > 0) return fail(ValueResult.Status.VALUE_LIMIT, "total value above limit");
            return ValueResult.success(new ValueResult.Quote(revision, snapshot.fingerprint(), base.get().source(),
                    base.get().rule(), base.get().value(), unit, total, adjustments, resolver.diagnostics()));
        } catch (BaseValueResolver.Rejected rejected) { return fail(rejected.status, rejected.getMessage()); }
    }
    private static ValueResult fail(ValueResult.Status status, String detail) { return ValueResult.fail(status, detail); }
}
