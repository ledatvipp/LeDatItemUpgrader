package vn.ledat.itemupgrader.catalog;

import java.util.Set;

final class CatalogValidation {
    private CatalogValidation() {}
    static String id(String value, String field) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9_.-]{0,63}"))
            throw new IllegalArgumentException(field + ": use lowercase identifier, maximum 64 characters");
        return value;
    }
    static String permission(String value) {
        if (value == null || (!value.isEmpty() && !value.matches("[a-z0-9][a-z0-9_.-]{0,127}")))
            throw new IllegalArgumentException("permission: invalid node (wildcards are not configured here)");
        return value;
    }
    static Set<String> ids(Set<String> values, int maximum, String field) {
        Set<String> result = Set.copyOf(values);
        if (result.size() > maximum) throw new IllegalArgumentException(field + ": too many entries");
        result.forEach(value -> id(value, field));
        return result;
    }
}
