package vn.ledat.itemupgrader.catalog;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** One-based pages. Literal search over stable ids/keys/tags, never arbitrary regex or MiniMessage parsing. */
public record CatalogQuery(int page, int pageSize, String category, String provider, Set<String> tags,
                           String search, Sort sort) {
    public enum Sort { RECOMMENDED, VALUE_ASC, VALUE_DESC, ID }
    public CatalogQuery {
        if (page < 1 || pageSize < 1 || pageSize > 45) throw new IllegalArgumentException("invalid catalog page/page-size");
        Objects.requireNonNull(category, "category"); if (!category.isEmpty()) CatalogValidation.id(category, "query.category");
        Objects.requireNonNull(provider, "provider");
        if (!provider.isEmpty() && !Set.of("minecraft", "mmoitems", "itemsadder", "oraxen", "nexo").contains(provider))
            throw new IllegalArgumentException("invalid catalog provider");
        tags = CatalogValidation.ids(tags, 32, "query.tags"); Objects.requireNonNull(search, "search");
        if (search.length() > 64 || search.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("search: at most 64 printable characters");
        search = search.strip().toLowerCase(Locale.ROOT); Objects.requireNonNull(sort, "sort");
    }
    public static CatalogQuery firstPage(int size) { return new CatalogQuery(1, size, "", "", Set.of(), "", Sort.RECOMMENDED); }
}
