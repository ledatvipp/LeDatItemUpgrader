package vn.ledat.itemupgrader.catalog;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import vn.ledat.itemupgrader.value.ValueResult;

/** Rejection/empty are distinct from page overflow. Rows are already access-filtered before pagination. */
public record CatalogResult(Status status, ValueResult sourceValue, Optional<UpgradePath> path,
                            List<CatalogIndex.PricedTarget> rows, int page, int pageSize,
                            int totalMatches, int totalPages) {
    public enum Status { OK, SOURCE_REJECTED, PATH_DENIED, NO_PATH, NO_TARGETS, PAGE_OUT_OF_RANGE }
    public CatalogResult {
        Objects.requireNonNull(status, "status"); Objects.requireNonNull(sourceValue, "sourceValue");
        Objects.requireNonNull(path, "path"); rows = List.copyOf(rows);
        if (page < 1 || pageSize < 1 || pageSize > 45 || totalMatches < 0 || totalPages < 0)
            throw new IllegalArgumentException("invalid catalog result pagination");
        if (rows.size() > pageSize || (status != Status.OK && !rows.isEmpty()))
            throw new IllegalArgumentException("invalid catalog result rows");
    }
}
