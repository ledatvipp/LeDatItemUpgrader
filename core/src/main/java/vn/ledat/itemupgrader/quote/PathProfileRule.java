package vn.ledat.itemupgrader.quote;

import java.util.Set;

/** Optional restriction on the already structurally selected path. No permission-based fallback. */
public record PathProfileRule(String pathId, Set<String> allowedProfiles, String defaultProfile) {
    public PathProfileRule {
        RuleValidation.id(pathId, "path-profile.path"); RuleValidation.id(defaultProfile, "path-profile.default-profile");
        allowedProfiles = RuleValidation.ids(allowedProfiles, 64, "path-profile.allowed-profiles");
        if (!allowedProfiles.contains(defaultProfile)) throw new IllegalArgumentException("path profile default must be allowed");
    }
}
