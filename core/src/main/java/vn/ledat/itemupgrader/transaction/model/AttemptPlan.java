package vn.ledat.itemupgrader.transaction.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import vn.ledat.itemupgrader.item.ItemSnapshot;
import vn.ledat.itemupgrader.cost.*;
import vn.ledat.itemupgrader.quote.UpgradeQuote;

/** Pinned, detached server-side terms. This is not proof that inventory ownership was acquired. */
public record AttemptPlan(UUID playerId, UUID sessionId, UUID quoteId, long configRevision,
        UUID catalogGeneration, int sourceSlot, ItemSnapshot source, String targetId, ItemSnapshot target,
        BigDecimal sourceTotal, BigDecimal targetTotal, UpgradeQuote.Terms terms,
        List<ItemHold> feeItems, Instant expiresAt) {
    public static final int MAX_PAYLOAD_BYTES = 4 * 1024 * 1024;
    public record ItemHold(int slot, int amount, ItemSnapshot snapshot) {
        public ItemHold {
            Objects.requireNonNull(snapshot);
            if (slot < 0 || slot > 35 || amount < 1 || amount > snapshot.facts().amount())
                throw new IllegalArgumentException("invalid fee item hold");
            if (!snapshot.facts().risks().isEmpty()) throw new IllegalArgumentException("unsafe fee snapshot");
        }
    }
    public AttemptPlan {
        Objects.requireNonNull(playerId); Objects.requireNonNull(sessionId); Objects.requireNonNull(quoteId);
        Objects.requireNonNull(catalogGeneration); Objects.requireNonNull(source); Objects.requireNonNull(target);
        Objects.requireNonNull(terms); Objects.requireNonNull(expiresAt);
        if (configRevision < 1 || sourceSlot < 0 || sourceSlot > 35) throw new IllegalArgumentException("invalid revision/source slot");
        id(targetId); id(terms.profileId());
        Objects.requireNonNull(terms.probability()); Objects.requireNonNull(terms.failure()); Objects.requireNonNull(terms.costs());
        if (terms.boosts().size() > 128 || terms.permissionBonuses().size() > 128) throw new IllegalArgumentException("too many modifiers");
        if (new HashSet<>(terms.boosts()).size() != terms.boosts().size()
                || new HashSet<>(terms.permissionBonuses()).size() != terms.permissionBonuses().size())
            throw new IllegalArgumentException("duplicate modifiers");
        terms.boosts().forEach(AttemptPlan::id); terms.permissionBonuses().forEach(AttemptPlan::id);
        if (!source.facts().risks().isEmpty() || !target.facts().risks().isEmpty()) throw new IllegalArgumentException("unsafe item snapshot");
        sourceTotal = price(sourceTotal); targetTotal = price(targetTotal);
        if (targetTotal.compareTo(sourceTotal) <= 0) throw new IllegalArgumentException("target must be an upgrade");
        if (feeItems.size() > 35) throw new IllegalArgumentException("too many item allocations");
        feeItems = feeItems.stream().sorted(Comparator.comparingInt(ItemHold::slot)).toList();
        Set<Integer> slots = new HashSet<>(); slots.add(sourceSlot);
        Map<CostResource, BigDecimal> allocations = new TreeMap<>();
        long bytes = source.byteSize() + (long) target.byteSize();
        for (ItemHold hold : feeItems) {
            if (!slots.add(hold.slot())) throw new IllegalArgumentException("duplicate/source fee slot");
            allocations.merge(CostResource.item(hold.snapshot().facts().key()), BigDecimal.valueOf(hold.amount()), BigDecimal::add);
            bytes += hold.snapshot().byteSize();
        }
        if (bytes > MAX_PAYLOAD_BYTES) throw new IllegalArgumentException("attempt payload budget exceeded");
        Map<CostResource, BigDecimal> required = new TreeMap<>();
        for (CostPlan.Line line : terms.costs().lines()) if (line.resource().kind() == CostResource.Kind.ITEM)
            required.put(line.resource(), line.reserve());
        if (allocations.size() != required.size()) throw new IllegalArgumentException("fee allocation resource mismatch");
        for (var entry : required.entrySet()) if (!allocations.containsKey(entry.getKey())
                || allocations.get(entry.getKey()).compareTo(entry.getValue()) != 0)
            throw new IllegalArgumentException("fee allocations must cover reservation exactly");
    }
    public String idempotencyKey() { return "iup:" + playerId + ":" + quoteId; }
    public UUID attemptId() { return UUID.nameUUIDFromBytes(idempotencyKey().getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    public static String id(String value) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9_.-]{0,63}")) throw new IllegalArgumentException("invalid rule id");
        return value;
    }
    private static BigDecimal price(BigDecimal value) {
        Objects.requireNonNull(value);
        if (value.signum() <= 0 || value.precision() > 32 || value.scale() < -16 || value.scale() > 8
                || value.compareTo(new BigDecimal("100000000000000000")) > 0) throw new IllegalArgumentException("invalid pinned value");
        return value.stripTrailingZeros();
    }
}
