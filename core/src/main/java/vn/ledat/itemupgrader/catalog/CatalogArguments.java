package vn.ledat.itemupgrader.catalog;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Framework-independent argument validation for /upgrader catalog [page] [category|all] [sort]. */
public final class CatalogArguments {
    private CatalogArguments() {}
    public static CatalogQuery parse(List<String> arguments, int pageSize) {
        if (arguments.size() > 3) throw new IllegalArgumentException("too many catalog arguments");
        int page = 1;
        if (!arguments.isEmpty()) {
            String value = arguments.getFirst();
            if (!value.matches("[1-9][0-9]{0,9}")) throw new IllegalArgumentException("page must be a positive integer");
            try { page = Integer.parseInt(value); }
            catch (NumberFormatException error) { throw new IllegalArgumentException("page is too large", error); }
        }
        String category = arguments.size() >= 2 ? arguments.get(1) : "";
        if (category.equals("all")) category = "";
        CatalogQuery.Sort sort = CatalogQuery.Sort.RECOMMENDED;
        if (arguments.size() >= 3) {
            String token = arguments.get(2);
            if (token.length() > 16 || !token.matches("[a-z_]+")) throw new IllegalArgumentException("invalid sort token");
            sort = CatalogQuery.Sort.valueOf(token.toUpperCase(Locale.ROOT));
        }
        return new CatalogQuery(page, pageSize, category, "", Set.of(), "", sort);
    }
}
