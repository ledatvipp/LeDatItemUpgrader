package vn.ledat.itemupgrader.transfer;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Objects;

/** Only the named fields may be written. Empty optional means leave the fresh target's field untouched. */
public record MetadataPatch(Optional<String> customName, OptionalInt repairCost, OptionalInt damage,
                            Optional<Map<String,Integer>> enchantments, Map<String,PdcValue> pdc) {
    public MetadataPatch {
        Objects.requireNonNull(customName); Objects.requireNonNull(repairCost); Objects.requireNonNull(damage); Objects.requireNonNull(enchantments);
        customName.ifPresent(MetadataView::name); pdc = Map.copyOf(pdc); enchantments = enchantments.map(Map::copyOf);
        if (repairCost.isPresent() && (repairCost.getAsInt() < 0 || repairCost.getAsInt() > 1_000_000)
                || damage.isPresent() && damage.getAsInt() < 0) throw new IllegalArgumentException("invalid patch scalar");
        if (pdc.size() > 32) throw new IllegalArgumentException("patch PDC budget");
        pdc.keySet().forEach(TransferPolicy::key);
        enchantments.ifPresent(map -> { if (map.size() > 128) throw new IllegalArgumentException("patch enchant budget"); map.forEach((k,v) -> { TransferPolicy.key(k); if (v < 1 || v > 32767) throw new IllegalArgumentException("patch enchant level"); }); });
    }
    public static MetadataPatch none() { return new MetadataPatch(Optional.empty(), OptionalInt.empty(), OptionalInt.empty(), Optional.empty(), Map.of()); }
    public boolean empty() { return customName.isEmpty() && repairCost.isEmpty() && damage.isEmpty() && enchantments.isEmpty() && pdc.isEmpty(); }
}
