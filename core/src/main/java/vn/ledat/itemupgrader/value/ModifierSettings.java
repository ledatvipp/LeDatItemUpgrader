package vn.ledat.itemupgrader.value;

import java.math.BigDecimal;
import java.util.Map;
import vn.ledat.itemupgrader.util.Decimals;

public record ModifierSettings(Map<String, BigDecimal> enchantPerLevel, Map<String, BigDecimal> rarityMultipliers,
                               boolean durabilityEnabled, BigDecimal minimumDurabilityFactor) {
    public ModifierSettings {
        enchantPerLevel = Map.copyOf(enchantPerLevel);
        rarityMultipliers = Map.copyOf(rarityMultipliers);
        if (enchantPerLevel.size() > 256 || rarityMultipliers.size() > 256)
            throw new IllegalArgumentException("too many modifier definitions");
        enchantPerLevel.forEach((key, value) -> {
            if (!key.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("invalid enchant key");
            Decimals.nonNegative(value, key);
        });
        rarityMultipliers.forEach((key, value) -> {
            if (!key.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("invalid rarity key");
            Decimals.positive(value, key);
            if (value.compareTo(new BigDecimal("100")) > 0) throw new IllegalArgumentException("rarity multiplier above 100");
        });
        Decimals.nonNegative(minimumDurabilityFactor, "durability floor");
        if (minimumDurabilityFactor.compareTo(BigDecimal.ONE) > 0) throw new IllegalArgumentException("durability floor above one");
    }
    public static ModifierSettings none() { return new ModifierSettings(Map.of(), Map.of(), false, BigDecimal.ZERO); }
}
