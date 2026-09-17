package vn.ledat.itemupgrader.cost;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Objects;
import vn.ledat.itemupgrader.item.ItemKey;
import vn.ledat.itemupgrader.util.Decimals;

/** Trusted adapter evidence only. A supply slot has exactly one verified exact-template identity. */
public record ResourceSnapshot(UUID playerId, Map<CostResource, BigDecimal> balances,
                               Set<ItemKey> availableItemMatchers, List<ItemSupply> supplies, Set<Integer> excludedSlots) {
    public record ItemSupply(int slot, ItemKey key, int amount, String fingerprint) {
        public ItemSupply {
            if (slot < 0 || slot > 35 || amount < 1 || amount > 4096) throw new IllegalArgumentException("invalid item supply");
            Objects.requireNonNull(key);
            if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("supply fingerprint required");
        }
    }
    public ResourceSnapshot {
        Objects.requireNonNull(playerId); balances = Map.copyOf(balances); availableItemMatchers = Set.copyOf(availableItemMatchers);
        supplies = List.copyOf(supplies); excludedSlots = Set.copyOf(excludedSlots);
        if (balances.size() > 2 || availableItemMatchers.size() > 32 || supplies.size() > 36 || excludedSlots.size() > 36)
            throw new IllegalArgumentException("resource snapshot budget exceeded");
        balances.forEach((resource, balance) -> {
            if (resource.kind() != CostResource.Kind.CURRENCY) throw new IllegalArgumentException("non-currency balance");
            Decimals.nonNegative(balance, "balance");
            if (balance.compareTo(new BigDecimal("1000000000000000000")) > 0) throw new IllegalArgumentException("balance overflow");
            if (resource.key().equals("playerpoints")) {
                try { balance.intValueExact(); }
                catch (ArithmeticException invalid) { throw new IllegalArgumentException("PlayerPoints balance must fit a nonnegative int", invalid); }
            }
        });
        if (supplies.stream().map(ItemSupply::slot).distinct().count() != supplies.size()) throw new IllegalArgumentException("duplicate physical slot");
        for (ItemSupply supply : supplies) if (!availableItemMatchers.contains(supply.key())) throw new IllegalArgumentException("supply matcher not verified");
        if (excludedSlots.stream().anyMatch(slot -> slot < 0 || slot > 35)) throw new IllegalArgumentException("invalid excluded slot");
    }
    public static ResourceSnapshot empty(UUID playerId, int sourceSlot) {
        return new ResourceSnapshot(playerId, Map.of(), Set.of(), List.of(), Set.of(sourceSlot));
    }
}
