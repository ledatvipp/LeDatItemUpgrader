package vn.ledat.itemupgrader.quote;

import java.util.Optional;
import java.util.Objects;

public record QuoteResult(Status status, Optional<UpgradeQuote> quote) {
    public enum Status { QUOTED, STALE_RULES, TARGET_DENIED, PROFILE_DENIED, BOOST_DENIED, BOOST_CONFLICT,
        FREE_PROTECTION_DENIED, COST_LIMIT_EXCEEDED, WRONG_RESOURCE_OWNER, SOURCE_SLOT_NOT_EXCLUDED, PITY_UNAVAILABLE, PITY_POLICY_DENIED }
    public QuoteResult {
        Objects.requireNonNull(status); Objects.requireNonNull(quote);
        if ((status == Status.QUOTED) != quote.isPresent()) throw new IllegalArgumentException("quote/result mismatch");
    }
    public static QuoteResult rejected(Status reason) { return new QuoteResult(reason, Optional.empty()); }
}
