package vn.ledat.itemupgrader.output;

import java.util.UUID;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import vn.ledat.itemupgrader.item.*;
import vn.ledat.itemupgrader.transfer.*;

/** The only native item boundary. Methods MUST route all Bukkit/provider work to the correct owner thread.
 * No method may give an item, take ownership, change an inventory or call a reward command.
 * createFresh uses provider creation, never copies a catalog/source item's opaque identity.
 * inspect re-derives ALL facts from the payload; never trusts ItemSnapshot.facts() as proof of native contents.
 * Only register providers with verified pure creation, identity and mutation capabilities.
 */
public interface ItemMutationPort {
    record Context(UUID playerId, UUID attemptId, String planDigest) {
        public Context { Objects.requireNonNull(playerId); Objects.requireNonNull(attemptId); MetadataView.digest(planDigest); }
    }
    CompletionStage<ItemDocument> inspect(Context context, ItemSnapshot snapshot);
    CompletionStage<ItemDocument> createFresh(Context context, ItemKey key, int amount);
    CompletionStage<ItemDocument> apply(Context context, ItemDocument original, MetadataPatch patch);
}
