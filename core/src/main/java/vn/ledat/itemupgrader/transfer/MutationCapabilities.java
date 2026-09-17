package vn.ledat.itemupgrader.transfer;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Registered adapter capabilities, NOT something a player/YAML may assert for an arbitrary provider.
 * Pure creation must not grant an item or modify external ownership. Each operation is owner-thread routed by the port.
 */
public record MutationCapabilities(String provider, boolean pureFreshCreation, boolean vanillaTransfer,
        boolean durabilityMutation, int maxStackSize, boolean uniqueIdentityRequired,
        Map<String, Integer> applicableEnchants, Set<Conflict> conflicts,
        Map<String, PdcValue.Type> writablePdc, Set<String> protectedPdc) {
    public record Conflict(String first, String second) {
        public Conflict {
            TransferPolicy.key(first); TransferPolicy.key(second);
            if (first.equals(second)) throw new IllegalArgumentException("self enchant conflict");
            if (first.compareTo(second) > 0) { String swap = first; first = second; second = swap; }
        }
    }
    public MutationCapabilities {
        if (provider == null || !provider.matches("[a-z0-9_-]{1,32}") || maxStackSize < 1 || maxStackSize > 99)
            throw new IllegalArgumentException("invalid capability provider/stack limit");
        applicableEnchants = Map.copyOf(applicableEnchants); conflicts = Set.copyOf(conflicts);
        writablePdc = Map.copyOf(writablePdc); protectedPdc = Set.copyOf(protectedPdc);
        if (applicableEnchants.size() > 256 || conflicts.size() > 4096 || writablePdc.size() > 64 || protectedPdc.size() > 256)
            throw new IllegalArgumentException("capability bound exceeded");
        applicableEnchants.forEach((key, limit) -> { TransferPolicy.key(key); if (limit < 1 || limit > 32767) throw new IllegalArgumentException("enchant limit"); });
        writablePdc.forEach((key, type) -> { TransferPolicy.key(key); Objects.requireNonNull(type); });
        protectedPdc.forEach(TransferPolicy::key);
        if (writablePdc.keySet().stream().anyMatch(protectedPdc::contains)) throw new IllegalArgumentException("protected PDC cannot be writable");
        if (uniqueIdentityRequired && maxStackSize != 1) throw new IllegalArgumentException("unique item must be unstackable");
    }
}
