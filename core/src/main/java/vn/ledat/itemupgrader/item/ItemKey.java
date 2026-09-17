package vn.ledat.itemupgrader.item;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Preserve provider-specific case; never guess MMOItems/Oraxen IDs from lore. */
public record ItemKey(String value) implements Comparable<ItemKey> {
    private static final Set<String> PROVIDERS = Set.of("minecraft", "mmoitems", "itemsadder", "oraxen", "nexo");
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_./-]{1,96}");
    public ItemKey {
        Objects.requireNonNull(value, "item key");
        if (value.length() > 200) throw new IllegalArgumentException("item key too long");
        String[] parts = value.split(":", -1);
        if (parts.length < 2) throw new IllegalArgumentException("missing provider");
        parts[0] = parts[0].toLowerCase(Locale.ROOT);
        if (!PROVIDERS.contains(parts[0])) throw new IllegalArgumentException("unsupported provider: " + parts[0]);
        int count = (parts[0].equals("mmoitems") || parts[0].equals("itemsadder")) ? 3 : 2;
        if (parts.length != count) throw new IllegalArgumentException("wrong item key arity");
        for (int i = 1; i < parts.length; i++) {
            if (!SEGMENT.matcher(parts[i]).matches()) throw new IllegalArgumentException("invalid item key segment");
            if ((parts[0].equals("minecraft") || parts[0].equals("itemsadder"))
                    && !parts[i].equals(parts[i].toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("namespace/item key must be lowercase");
        }
        value = String.join(":", parts);
    }
    public static ItemKey of(String value) { return new ItemKey(value); }
    public String provider() { return value.substring(0, value.indexOf(':')); }
    public boolean vanilla() { return provider().equals("minecraft"); }
    @Override public int compareTo(ItemKey other) { return value.compareTo(other.value); }
    @Override public String toString() { return value; }
}
