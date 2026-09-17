package vn.ledat.itemupgrader.transaction.storage;

import java.time.Instant;
import java.util.*;
import vn.ledat.itemupgrader.transaction.model.*;

/** Blocking storage port: caller MUST dispatch on a bounded worker. Never run JDBC from an owner/event thread.
 * Atomic claim = attempt row + unique player lock + first audit event. Atomic CAS = row + event + terminal unlock.
 * Preserve idempotency tombstones and all nonterminal payloads. A timeout does not prove rollback. */
public interface TransactionJournal {
    enum ClaimStatus { CREATED, DUPLICATE, IDEMPOTENCY_CONFLICT, PLAYER_BUSY, EXPIRED }
    record Claim(ClaimStatus status, Optional<AttemptRecord> record, Optional<UUID> blockingAttempt) {
        public Claim { Objects.requireNonNull(status); Objects.requireNonNull(record); Objects.requireNonNull(blockingAttempt); }
    }
    Claim claim(AttemptPlan plan, Instant now);
    Optional<AttemptRecord> find(UUID attemptId);
    boolean compareAndSet(AttemptRecord expected, AttemptRecord next);
    /** Stable keyset page ordered by UUID TEXT, not Java UUID.compareTo. afterExclusive may be empty. */
    List<AttemptRecord> unfinished(Optional<UUID> afterExclusive, int limit);
    Optional<UUID> activeAttempt(UUID playerId);
}
