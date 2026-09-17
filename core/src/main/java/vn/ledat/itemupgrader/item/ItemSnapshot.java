package vn.ledat.itemupgrader.item;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Full serialized payload, not selected PDC only. Digest verifies these bytes, not global uniqueness. */
public final class ItemSnapshot {
    public static final int HARD_MAX_BYTES = 1_048_576;
    private final ItemFacts facts;
    private final byte[] bytes;
    private final String fingerprint;
    public ItemSnapshot(ItemFacts facts, byte[] bytes) {
        this.facts = Objects.requireNonNull(facts, "facts");
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 0 || bytes.length > HARD_MAX_BYTES) throw new IllegalArgumentException("snapshot size");
        this.bytes = bytes.clone();
        this.fingerprint = digest(facts, this.bytes);
    }
    public ItemFacts facts() { return facts; }
    public byte[] bytes() { return bytes.clone(); }
    public int byteSize() { return bytes.length; }
    public String fingerprint() { return fingerprint; }
    private static String digest(ItemFacts facts, byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("item-upgrader-payload-v1\n".getBytes(StandardCharsets.UTF_8));
            byte[] key = facts.key().value().getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(4).putInt(key.length).array());
            digest.update(key);
            digest.update(ByteBuffer.allocate(4).putInt(facts.amount()).array());
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("JVM missing SHA-256", error);
        }
    }
}
