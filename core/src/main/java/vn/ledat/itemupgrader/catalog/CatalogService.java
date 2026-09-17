package vn.ledat.itemupgrader.catalog;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import vn.ledat.itemupgrader.item.ItemSnapshot;
import vn.ledat.itemupgrader.value.ItemValueService;
import vn.ledat.itemupgrader.value.ValueResult;

/** Stateless read-only discovery. No item creation, Bukkit calls, fee, RNG or reward logic. */
public final class CatalogService {
    private final ItemValueService values = new ItemValueService();
    public CatalogResult browse(ItemSnapshot source, CatalogIndex index, CatalogAccess access, CatalogQuery query) {
        ValueResult value = values.evaluate(source, index.values(), index.revision());
        Optional<UpgradePath> path = index.definitions().pathFor(source.facts().key());
        if (value.quote().isEmpty()) return empty(CatalogResult.Status.SOURCE_REJECTED, value, path, query, 0, 0);
        if (path.isPresent() && !access.allows(path.get().permission(), path.get().requiredConditions()))
            return empty(CatalogResult.Status.PATH_DENIED, value, path, query, 0, 0);
        CatalogSettings settings = index.definitions().settings();
        if (path.isEmpty() && !settings.allowUnpathed()) return empty(CatalogResult.Status.NO_PATH, value, path, query, 0, 0);
        BigDecimal total = value.quote().orElseThrow().totalValue();
        List<CatalogIndex.PricedTarget> priceRange = index.between(total.multiply(settings.minimumRatio()), total.multiply(settings.maximumRatio()));
        List<CatalogIndex.PricedTarget> eligible = priceRange.stream()
                .filter(row -> eligible(source, total, row, path, access))
                .filter(row -> matches(row.definition(), query))
                .sorted(order(query.sort(), path, total.multiply(settings.preferredRatio())))
                .toList();
        if (eligible.isEmpty()) return empty(CatalogResult.Status.NO_TARGETS, value, path, query, 0, 0);
        int pages = (eligible.size() + query.pageSize() - 1) / query.pageSize();
        // Check page BEFORE multiplication; a malicious Integer.MAX_VALUE page cannot overflow offset.
        if (query.page() > pages) return empty(CatalogResult.Status.PAGE_OUT_OF_RANGE, value, path, query, eligible.size(), pages);
        int offset = (query.page() - 1) * query.pageSize();
        return new CatalogResult(CatalogResult.Status.OK, value, path,
                eligible.subList(offset, Math.min(offset + query.pageSize(), eligible.size())),
                query.page(), query.pageSize(), eligible.size(), pages);
    }
    public CatalogResult recommend(ItemSnapshot source, CatalogIndex index, CatalogAccess access) {
        return browse(source, index, access, CatalogQuery.firstPage(index.definitions().settings().recommendationCount()));
    }
    boolean eligible(ItemSnapshot source, BigDecimal sourceTotal, CatalogIndex.PricedTarget row,
                     Optional<UpgradePath> path, CatalogAccess access) {
        TargetDefinition target = row.definition();
        // Equal-value swaps and same-key quantity reshuffles are never upgrades in this phase.
        if (target.item().equals(source.facts().key()) || row.value().totalValue().compareTo(sourceTotal) <= 0) return false;
        if (!target.allowedSources().isEmpty() && !target.allowedSources().contains(source.facts().key())) return false;
        if (!access.allows(target.permission(), target.requiredConditions())) return false;
        return path.isEmpty() || path.get().mode() == UpgradePath.Mode.OPEN || path.get().targets().contains(target.id());
    }
    private static boolean matches(TargetDefinition target, CatalogQuery query) {
        if (!query.category().isEmpty() && !target.category().equals(query.category())) return false;
        if (!query.provider().isEmpty() && !target.item().provider().equals(query.provider())) return false;
        if (!target.tags().containsAll(query.tags())) return false;
        if (query.search().isEmpty()) return true;
        // Name may contain configured font/MiniMessage markup. Do not parse it or expose markup to the search engine.
        return target.id().contains(query.search()) || target.item().value().toLowerCase(Locale.ROOT).contains(query.search())
                || target.category().contains(query.search()) || target.tags().stream().anyMatch(tag -> tag.contains(query.search()));
    }
    private static Comparator<CatalogIndex.PricedTarget> order(CatalogQuery.Sort sort, Optional<UpgradePath> path, BigDecimal preferred) {
        Comparator<CatalogIndex.PricedTarget> id = Comparator.comparing(row -> row.definition().id());
        Comparator<CatalogIndex.PricedTarget> value = Comparator.comparing(row -> row.value().totalValue());
        if (sort == CatalogQuery.Sort.ID) return id;
        if (sort == CatalogQuery.Sort.VALUE_ASC) return value.thenComparing(id);
        if (sort == CatalogQuery.Sort.VALUE_DESC) return value.reversed().thenComparing(id);
        Map<String, Integer> ranks = new HashMap<>();
        path.ifPresent(p -> { for (int i = 0; i < p.targets().size(); i++) ranks.put(p.targets().get(i), i); });
        return Comparator.<CatalogIndex.PricedTarget>comparingInt(row -> ranks.getOrDefault(row.definition().id(), Integer.MAX_VALUE))
                .thenComparing(Comparator.comparingInt((CatalogIndex.PricedTarget row) -> row.definition().priority()).reversed())
                .thenComparing(row -> row.value().totalValue().subtract(preferred).abs())
                .thenComparing(value).thenComparing(id);
    }
    private static CatalogResult empty(CatalogResult.Status status, ValueResult value, Optional<UpgradePath> path,
                                       CatalogQuery query, int matches, int pages) {
        return new CatalogResult(status, value, path, List.of(), query.page(), query.pageSize(), matches, pages);
    }
}
