package vn.ledat.itemupgrader.catalog;

import java.util.Objects;
import java.util.Optional;
import vn.ledat.itemupgrader.item.ItemSnapshot;

/** Adapter-supplied verified, detached template. Core still checks exact key, quantity and value safety. */
public record TargetProbe(Optional<ItemSnapshot> snapshot, Failure failure) {
    public enum Failure { NONE, NOT_PROBED, PROVIDER_UNAVAILABLE, IDENTITY_UNVERIFIED, INVALID_TEMPLATE }
    public TargetProbe {
        Objects.requireNonNull(snapshot, "snapshot"); Objects.requireNonNull(failure, "failure");
        if (snapshot.isPresent() != (failure == Failure.NONE)) throw new IllegalArgumentException("inconsistent target probe");
    }
    public static TargetProbe verified(ItemSnapshot snapshot) { return new TargetProbe(Optional.of(snapshot), Failure.NONE); }
    public static TargetProbe failed(Failure failure) { return new TargetProbe(Optional.empty(), failure); }
}
