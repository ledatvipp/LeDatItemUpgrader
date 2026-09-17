package vn.ledat.itemupgrader.transfer;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/** Detached, typed scalar data only. No nested container, arbitrary object or Java deserialization. */
public final class PdcValue {
    public enum Type { STRING, INTEGER, LONG, BYTE_ARRAY }
    public static final int MAX_BYTES = 4096;
    private final Type type;
    private final byte[] bytes;
    public PdcValue(Type type, byte[] bytes) {
        this.type = Objects.requireNonNull(type);
        Objects.requireNonNull(bytes);
        if (bytes.length > MAX_BYTES || type == Type.INTEGER && bytes.length != 4
                || type == Type.LONG && bytes.length != 8) throw new IllegalArgumentException("invalid PDC value size");
        if (type == Type.STRING) {
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (!Arrays.equals(text.getBytes(StandardCharsets.UTF_8), bytes) || text.indexOf('\0') >= 0)
                throw new IllegalArgumentException("invalid PDC UTF-8 string");
        }
        this.bytes = bytes.clone();
    }
    public static PdcValue text(String text) { return new PdcValue(Type.STRING, text.getBytes(StandardCharsets.UTF_8)); }
    public static PdcValue integer(int value) { return new PdcValue(Type.INTEGER, ByteBuffer.allocate(4).putInt(value).array()); }
    public static PdcValue longValue(long value) { return new PdcValue(Type.LONG, ByteBuffer.allocate(8).putLong(value).array()); }
    public Type type() { return type; }
    public byte[] bytes() { return bytes.clone(); }
    public int byteSize() { return bytes.length; }
    public String text() { if (type != Type.STRING) throw new IllegalStateException("not a string"); return new String(bytes, StandardCharsets.UTF_8); }
    @Override public boolean equals(Object object) { return object instanceof PdcValue other && type == other.type && Arrays.equals(bytes, other.bytes); }
    @Override public int hashCode() { return 31 * type.hashCode() + Arrays.hashCode(bytes); }
    @Override public String toString() { return "PdcValue[" + type + ", bytes=" + bytes.length + "]"; }
}
