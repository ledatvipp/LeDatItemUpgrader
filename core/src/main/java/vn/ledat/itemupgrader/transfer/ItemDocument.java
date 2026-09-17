package vn.ledat.itemupgrader.transfer;

import java.util.Objects;
import vn.ledat.itemupgrader.item.ItemSnapshot;

/** Everything leaving a native ItemStack boundary is detached and immutable. */
public record ItemDocument(ItemSnapshot snapshot, MetadataView metadata, MutationCapabilities capabilities) {
    public ItemDocument {
        Objects.requireNonNull(snapshot); Objects.requireNonNull(metadata); Objects.requireNonNull(capabilities);
        if (!snapshot.facts().key().provider().equals(capabilities.provider()) || !snapshot.facts().risks().isEmpty()
                || snapshot.facts().amount() > capabilities.maxStackSize()) throw new IllegalArgumentException("unsafe item document");
        if (capabilities.uniqueIdentityRequired() && metadata.uniqueTokens().isEmpty()) throw new IllegalArgumentException("missing unique identity evidence");
        if (!metadata.uniqueTokens().isEmpty() && snapshot.facts().amount() != 1) throw new IllegalArgumentException("unique identity cannot represent a stack");
    }
}
