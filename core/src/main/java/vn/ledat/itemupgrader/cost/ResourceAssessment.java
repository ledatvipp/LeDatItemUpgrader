package vn.ledat.itemupgrader.cost;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public record ResourceAssessment(Status status, List<Issue> issues, List<Allocation> allocations) {
    public enum Status { AVAILABLE_PREVIEW, INSUFFICIENT, UNAVAILABLE }
    public enum Reason { PROVIDER_UNAVAILABLE, ITEM_MATCHER_UNAVAILABLE, INSUFFICIENT_BALANCE, INSUFFICIENT_ITEMS }
    public record Issue(CostResource resource, Reason reason, BigDecimal required, BigDecimal available) {}
    public record Allocation(CostResource resource, int slot, int amount, String fingerprint) {}
    public ResourceAssessment { Objects.requireNonNull(status); issues = List.copyOf(issues); allocations = List.copyOf(allocations); }
    public boolean available() { return status == Status.AVAILABLE_PREVIEW; }
}
