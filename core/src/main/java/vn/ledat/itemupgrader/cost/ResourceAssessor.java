package vn.ledat.itemupgrader.cost;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import vn.ledat.itemupgrader.item.ItemKey;

/** Read-only allocation preview. Never uses the source/cursor/armor/offhand as fee or booster supply. */
public final class ResourceAssessor {
    public ResourceAssessment assess(CostPlan plan, ResourceSnapshot snapshot) {
        var issues = new ArrayList<ResourceAssessment.Issue>(); var allocations = new ArrayList<ResourceAssessment.Allocation>();
        boolean unavailable = false;
        for (CostPlan.Line line : plan.lines()) {
            CostResource resource = line.resource(); BigDecimal required = line.reserve();
            if (resource.kind() == CostResource.Kind.CURRENCY) {
                BigDecimal balance = snapshot.balances().get(resource);
                if (balance == null) { unavailable = true; issues.add(new ResourceAssessment.Issue(resource, ResourceAssessment.Reason.PROVIDER_UNAVAILABLE, required, BigDecimal.ZERO)); }
                else if (balance.compareTo(required) < 0) issues.add(new ResourceAssessment.Issue(resource, ResourceAssessment.Reason.INSUFFICIENT_BALANCE, required, balance));
                continue;
            }
            ItemKey key = ItemKey.of(resource.key());
            if (!snapshot.availableItemMatchers().contains(key)) {
                unavailable = true; issues.add(new ResourceAssessment.Issue(resource, ResourceAssessment.Reason.ITEM_MATCHER_UNAVAILABLE, required, BigDecimal.ZERO)); continue;
            }
            var candidates = snapshot.supplies().stream().filter(supply -> supply.key().equals(key) && !snapshot.excludedSlots().contains(supply.slot()))
                    .sorted(Comparator.comparingInt(ResourceSnapshot.ItemSupply::slot)).toList();
            int available = candidates.stream().mapToInt(ResourceSnapshot.ItemSupply::amount).sum();
            int remaining = required.intValueExact();
            if (available < remaining) {
                issues.add(new ResourceAssessment.Issue(resource, ResourceAssessment.Reason.INSUFFICIENT_ITEMS, required, BigDecimal.valueOf(available))); continue;
            }
            for (var supply : candidates) {
                if (remaining == 0) break;
                int used = Math.min(supply.amount(), remaining); remaining -= used;
                allocations.add(new ResourceAssessment.Allocation(resource, supply.slot(), used, supply.fingerprint()));
            }
        }
        var status = unavailable ? ResourceAssessment.Status.UNAVAILABLE : issues.isEmpty() ? ResourceAssessment.Status.AVAILABLE_PREVIEW : ResourceAssessment.Status.INSUFFICIENT;
        // Partial allocation must not be mistaken for a complete reservation when any resource is missing.
        return new ResourceAssessment(status, issues, status == ResourceAssessment.Status.AVAILABLE_PREVIEW ? allocations : java.util.List.of());
    }
}
