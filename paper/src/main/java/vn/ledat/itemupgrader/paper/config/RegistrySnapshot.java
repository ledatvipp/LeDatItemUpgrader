package vn.ledat.itemupgrader.paper.config;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.Material;
import org.bukkit.Registry;
import vn.ledat.itemupgrader.item.ItemKey;

/** Capture on the Paper main thread once; async YAML validation reads immutable strings only. */
public record RegistrySnapshot(Set<String> materials, Set<String> vanillaKeys, Set<String> enchantments, Set<String> sounds) {
    public RegistrySnapshot { materials = Set.copyOf(materials); vanillaKeys = Set.copyOf(vanillaKeys); enchantments = Set.copyOf(enchantments); sounds = Set.copyOf(sounds); }
    public static RegistrySnapshot capture() {
        Set<String> materials = Arrays.stream(Material.values()).filter(Material::isItem)
                .filter(material -> !material.isAir()).map(Enum::name).collect(Collectors.toUnmodifiableSet());
        Set<String> vanilla = Arrays.stream(Material.values()).filter(Material::isItem)
                .filter(material -> !material.isAir()).map(material -> material.getKey().toString()).collect(Collectors.toUnmodifiableSet());
        Set<String> enchants = Registry.ENCHANTMENT.stream().map(enchantment -> enchantment.key().asString()).collect(Collectors.toUnmodifiableSet());
        return new RegistrySnapshot(materials, vanilla, enchants, Registry.SOUND_EVENT.stream().map(sound -> sound.key().asString()).collect(Collectors.toUnmodifiableSet()));
    }
    public void validate(ItemKey key, String path) {
        if (key.vanilla() && !vanillaKeys.contains(key.value())) throw new IllegalArgumentException(path + ": invalid vanilla item " + key);
    }
}
