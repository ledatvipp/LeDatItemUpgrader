package vn.ledat.itemupgrader.output;

import java.util.*;
import java.util.concurrent.CompletionStage;
import vn.ledat.itemupgrader.item.ItemSnapshot;
import vn.ledat.itemupgrader.transaction.model.EffectReceipt;
import vn.ledat.itemupgrader.transfer.MetadataView;

/** Future mailbox/delivery boundary. Only persist the selected entitlement; NEVER give both private candidates.
 * An APPLIED receipt requires durable ownership transfer, not addItem() success in unsaved player memory.
 * Player offline/full inventory must remain in a durable pending delivery, never drop the valuable item.
 */
@FunctionalInterface
public interface PreparedDeliveryPort {
    enum Role { SUCCESS, FAILURE }
    record Delivery(UUID playerId,UUID attemptId,String planDigest,String operationKey,String outputDigest,Role role,ItemSnapshot item) {
        public Delivery {
            Objects.requireNonNull(playerId);Objects.requireNonNull(attemptId);Objects.requireNonNull(role);Objects.requireNonNull(item);
            MetadataView.digest(planDigest);MetadataView.digest(outputDigest);
            if(operationKey==null||!operationKey.startsWith("iup:"+attemptId+":"))throw new IllegalArgumentException("delivery operation binding");
        }
    }
    CompletionStage<EffectReceipt> deliver(Delivery delivery);
}
