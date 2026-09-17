package vn.ledat.itemupgrader.transaction.model;

import java.util.Objects;

/** APPLIED means the exact operation is confirmed; NOT_APPLIED must mean no partial mutation occurred.
 * UNKNOWN includes exceptions, timeouts, ambiguous provider replies and partial inventory mutation.
 * Receipt references must not contain raw player text, credentials or complete item payloads. */
public record EffectReceipt(Status status, String evidence) {
    public enum Status { INTENT, APPLIED, NOT_APPLIED, UNKNOWN }
    public EffectReceipt {
        Objects.requireNonNull(status); Objects.requireNonNull(evidence);
        if (evidence.length() > 192 || !evidence.matches("[A-Za-z0-9_.:/-]*")) throw new IllegalArgumentException("invalid evidence reference");
        if (status == Status.INTENT ? !evidence.isEmpty() : evidence.isEmpty()) throw new IllegalArgumentException("missing/unexpected evidence");
    }
    public static EffectReceipt intent() { return new EffectReceipt(Status.INTENT, ""); }
    public static EffectReceipt unknown(String reason) { return new EffectReceipt(Status.UNKNOWN, reason); }
}
