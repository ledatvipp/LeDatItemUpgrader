package vn.ledat.itemupgrader.output;

/** Definite rejection BEFORE any item/currency acquisition. Never use for an ambiguous external effect. */
public final class OutputRejectedException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;
    public enum Code { RECONFIRM_REQUIRED, CAPABILITY_UNAVAILABLE, INVALID_SNAPSHOT, UNSAFE_TRANSFER,
        INVALID_FAILURE, REUSED_IDENTITY, UNKNOWN_VALUE, EXPIRED, LEGACY_PLAN }
    private final Code code;
    public OutputRejectedException(Code code, String diagnostic) { super(diagnostic); this.code = java.util.Objects.requireNonNull(code); }
    public Code code() { return code; }
}
