package vn.ledat.itemupgrader.transfer;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Adapter-attested snapshot. customName is plain literal text, never MiniMessage or executable Component JSON.
 * protectedDigest covers ALL opaque provider fields except the explicitly mutable fields/keys for this call.
 * Identity tokens are SHA-256(provider + canonical unique identity), NOT inferred from display name/lore.
 */
public record MetadataView(Optional<String> customName, int repairCost, boolean unbreakable,
                           Map<String, PdcValue> pdc, String protectedDigest, String templateDigest, Set<String> uniqueTokens) {
    public MetadataView {
        Objects.requireNonNull(customName); customName.ifPresent(MetadataView::name);
        if (repairCost < 0 || repairCost > 1_000_000) throw new IllegalArgumentException("repair cost bound");
        pdc = Map.copyOf(pdc); uniqueTokens = Set.copyOf(uniqueTokens);
        if (pdc.size() > 64 || pdc.values().stream().mapToLong(PdcValue::byteSize).sum() > 65536)
            throw new IllegalArgumentException("metadata projection budget exceeded");
        pdc.keySet().forEach(TransferPolicy::key);
        digest(protectedDigest); digest(templateDigest);
        if (uniqueTokens.size() > 16) throw new IllegalArgumentException("too many unique identity tokens");
        uniqueTokens.forEach(MetadataView::digest);
    }
    public static String name(String name) {
        if (name.length() > 256 || name.codePoints().anyMatch(c -> Character.isISOControl(c)))
            throw new IllegalArgumentException("custom name exceeds limit or contains control characters");
        return name;
    }
    public static String digest(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("invalid SHA-256 digest");
        return value;
    }
}
