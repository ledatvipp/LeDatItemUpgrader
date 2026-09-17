package vn.ledat.itemupgrader.pity;
import java.math.BigDecimal;
import java.util.*;
import vn.ledat.itemupgrader.transaction.model.AttemptPlan;
/** Explicit allowlists, no global fallback. Empty/wildcard scopes are forbidden to prevent cheap-path pity farming. */
public record PityPolicy(String id,int epoch,Set<String> paths,Set<String> targets,Set<String> profiles,
                         BigDecimal minimumSourceValue,BigDecimal incrementPoints,BigDecimal maximumPoints) {
    public PityPolicy {
        AttemptPlan.id(id);paths=validated(paths);targets=validated(targets);profiles=validated(profiles);
        Objects.requireNonNull(minimumSourceValue);
        if(minimumSourceValue.signum()<=0 || minimumSourceValue.scale()>8 || minimumSourceValue.scale() < -16 || minimumSourceValue.precision()>24
                || minimumSourceValue.compareTo(new BigDecimal("100000000000000000"))>0)
            throw new IllegalArgumentException("invalid pity source floor");
        minimumSourceValue=minimumSourceValue.stripTrailingZeros();
        var probe=new PityStamp(id,epoch,"0".repeat(64),0,0,incrementPoints,maximumPoints);
        incrementPoints=probe.incrementPoints();maximumPoints=probe.maximumPoints();
    }
    private static Set<String> validated(Set<String> values) {
        if(values.isEmpty() || values.size()>256)throw new IllegalArgumentException("pity allow-list requires 1..256 exact ids");
        values.forEach(AttemptPlan::id);return Set.copyOf(values);
    }
    public boolean matches(String path,String target,String profile) {
        return paths.contains(path)&&targets.contains(target)&&profiles.contains(profile);
    }
}
