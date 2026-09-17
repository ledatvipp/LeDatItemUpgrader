package vn.ledat.itemupgrader.catalog;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import vn.ledat.itemupgrader.item.ItemSnapshot;
import vn.ledat.itemupgrader.value.ItemValueService;
import vn.ledat.itemupgrader.value.ValueDefinitions;
import vn.ledat.itemupgrader.value.ValueResult;

/** Price only verified templates. Unavailable entries remain diagnostic data, never purchasable fallbacks. */
public final class CatalogIndex {
    public record PricedTarget(TargetDefinition definition, ItemSnapshot snapshot, ValueResult.Quote value) {}
    public record Rejection(String code, String detail) {}
    private final long revision;
    private final UUID generation;
    private final CatalogDefinitions definitions;
    private final ValueDefinitions values;
    private final Map<String, PricedTarget> ready;
    private final Map<String, Rejection> rejected;
    private final NavigableMap<BigDecimal, List<PricedTarget>> byValue;
    private final Map<String, List<PricedTarget>> byCategory;
    private final Map<String, List<PricedTarget>> byProvider;
    private final long snapshotBytes;
    private CatalogIndex(long revision, CatalogDefinitions definitions, ValueDefinitions values,
                         Map<String, PricedTarget> ready, Map<String, Rejection> rejected) {
        this.revision = revision; this.generation = UUID.randomUUID(); this.definitions = definitions; this.values = values;
        this.ready = Map.copyOf(ready); this.rejected = Map.copyOf(rejected);
        TreeMap<BigDecimal, List<PricedTarget>> prices = new TreeMap<>();
        Map<String, List<PricedTarget>> categories = new HashMap<>(); Map<String, List<PricedTarget>> providers = new HashMap<>();
        List<PricedTarget> ordered = ready.values().stream().sorted(Comparator.comparing(row -> row.definition().id())).toList();
        for (PricedTarget row : ordered) {
            prices.computeIfAbsent(row.value().totalValue(), unused -> new ArrayList<>()).add(row);
            categories.computeIfAbsent(row.definition().category(), unused -> new ArrayList<>()).add(row);
            providers.computeIfAbsent(row.definition().item().provider(), unused -> new ArrayList<>()).add(row);
        }
        prices.replaceAll((key, list) -> List.copyOf(list)); this.byValue = Collections.unmodifiableNavigableMap(prices);
        categories.replaceAll((key, list) -> List.copyOf(list)); this.byCategory = Map.copyOf(categories);
        providers.replaceAll((key, list) -> List.copyOf(list)); this.byProvider = Map.copyOf(providers);
        this.snapshotBytes = ready.values().stream().mapToLong(row -> row.snapshot().byteSize()).sum();
    }
    public static CatalogIndex build(long revision, CatalogDefinitions definitions, ValueDefinitions values,
                                     Map<String, TargetProbe> probes) {
        if (revision < 1) throw new IllegalArgumentException("invalid revision");
        Objects.requireNonNull(definitions, "definitions"); Objects.requireNonNull(values, "values");
        probes = Map.copyOf(probes);
        for (String id : probes.keySet()) if (!definitions.targets().containsKey(id))
            throw new IllegalArgumentException("probe for unknown target " + id);
        Map<String, PricedTarget> ready = new HashMap<>(); Map<String, Rejection> rejected = new HashMap<>();
        ItemValueService engine = new ItemValueService(); long bytes = 0;
        for (TargetDefinition target : definitions.targets().values().stream().sorted(Comparator.comparing(TargetDefinition::id)).toList()) {
            if (!target.enabled()) { rejected.put(target.id(), new Rejection("DISABLED", "")); continue; }
            TargetProbe probe = probes.getOrDefault(target.id(), TargetProbe.failed(TargetProbe.Failure.NOT_PROBED));
            if (probe.snapshot().isEmpty()) { rejected.put(target.id(), new Rejection(probe.failure().name(), "")); continue; }
            ItemSnapshot snapshot = probe.snapshot().orElseThrow();
            if (!snapshot.facts().key().equals(target.item()) || snapshot.facts().amount() != target.amount()) {
                rejected.put(target.id(), new Rejection("TEMPLATE_MISMATCH", "key/quantity differs from definition")); continue;
            }
            ValueResult valuation = engine.evaluate(snapshot, values, revision);
            if (valuation.quote().isEmpty()) {
                rejected.put(target.id(), new Rejection("VALUE_" + valuation.status().name(), valuation.detail())); continue;
            }
            bytes += snapshot.byteSize();
            // Independent of target count: large provider NBT must not cause an unbounded catalog cache.
            if (bytes > 16L * 1024 * 1024) throw new IllegalArgumentException("catalog verified payload budget exceeds 16 MiB");
            ready.put(target.id(), new PricedTarget(target, snapshot, valuation.quote().orElseThrow()));
        }
        return new CatalogIndex(revision, definitions, values, ready, rejected);
    }
    public long revision() { return revision; }
    public UUID generation() { return generation; }
    public CatalogDefinitions definitions() { return definitions; }
    public ValueDefinitions values() { return values; }
    public Map<String, PricedTarget> ready() { return ready; }
    public Map<String, Rejection> rejected() { return rejected; }
    public long snapshotBytes() { return snapshotBytes; }
    public Map<String, List<PricedTarget>> byCategory() { return byCategory; }
    public Map<String, List<PricedTarget>> byProvider() { return byProvider; }
    public List<PricedTarget> between(BigDecimal minimum, BigDecimal maximum) {
        if (minimum.compareTo(maximum) > 0) throw new IllegalArgumentException("invalid price range");
        return byValue.subMap(minimum, true, maximum, true).values().stream().flatMap(List::stream).toList();
    }
}
