package vn.ledat.itemupgrader.catalog;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Captured on the player owner thread. Missing/unknown condition is DENIED; no script/PAPI execution here. */
public record CatalogAccess(UUID playerId, Set<String> grantedPermissions, Set<String> satisfiedConditions) {
    public CatalogAccess {
        Objects.requireNonNull(playerId, "playerId"); grantedPermissions = Set.copyOf(grantedPermissions);
        satisfiedConditions = Set.copyOf(satisfiedConditions);
        if (grantedPermissions.size() > 12000 || satisfiedConditions.size() > 12000)
            throw new IllegalArgumentException("access snapshot budget exceeded");
    }
    public boolean allows(String permission, Set<String> conditions) {
        return (permission.isEmpty() || grantedPermissions.contains(permission)) && satisfiedConditions.containsAll(conditions);
    }
}
