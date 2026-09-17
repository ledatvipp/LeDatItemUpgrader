package vn.ledat.itemupgrader.quote;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record QuoteRequest(UUID sessionId, String targetId, String profileId, List<String> boostIds, int sourceSlot) {
    public QuoteRequest {
        Objects.requireNonNull(sessionId); RuleValidation.id(targetId, "quote.target"); Objects.requireNonNull(profileId);
        if (!profileId.isEmpty()) RuleValidation.id(profileId, "quote.profile");
        boostIds = RuleValidation.ids(boostIds, 8, "quote.boosts").stream().sorted().toList();
        if (sourceSlot < 0 || sourceSlot > 35) throw new IllegalArgumentException("quote source slot outside storage inventory");
    }
}
