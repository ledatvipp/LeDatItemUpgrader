package vn.ledat.itemupgrader.animation;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Small state fence for the optional packet presentation layer. It deliberately captures exactly
 * one OPEN_WINDOW while an owned inventory is being opened. Packets arriving before begin, after
 * activation, or for a retired token are ignored, so a foreign GUI can never replace our container.
 */
public final class TitlePacketFence<T> {
    public record Container(int id, int type) {
        public Container {
            if (id < 1 || type < 0) throw new IllegalArgumentException("invalid container identity");
        }
    }
    private static final class Entry {
        final Object token;
        Container captured;
        boolean active;
        Entry(Object token) { this.token = token; }
    }
    private final Map<UUID, Entry> entries = new HashMap<>();
    private final Function<T, UUID> viewer;

    public TitlePacketFence(Function<T, UUID> viewer) { this.viewer = Objects.requireNonNull(viewer); }

    public synchronized boolean begin(T token) {
        Objects.requireNonNull(token);
        UUID id = viewer.apply(token);
        if (entries.containsKey(id)) return false;
        entries.put(id, new Entry(token));
        return true;
    }

    /** Called by the packet listener. Active sessions intentionally reject later/foreign opens. */
    public synchronized boolean capture(UUID viewer, int containerId, int type) {
        Entry entry = entries.get(Objects.requireNonNull(viewer));
        if (entry == null || entry.active || entry.captured != null) return false;
        entry.captured = new Container(containerId, type);
        return true;
    }

    public synchronized Optional<Container> activate(T token) {
        Entry entry = exact(token);
        if (entry == null || entry.active || entry.captured == null) return Optional.empty();
        entry.active = true;
        return Optional.of(entry.captured);
    }

    public synchronized Optional<Container> current(T token) {
        Entry entry = exact(token);
        return entry != null && entry.active ? Optional.of(entry.captured) : Optional.empty();
    }

    public synchronized void release(T token) {
        Entry entry = exact(token);
        if (entry != null) entries.remove(viewer.apply(token));
    }

    public synchronized void clear() { entries.clear(); }

    private Entry exact(T token) {
        if (token == null) return null;
        Entry entry = entries.get(viewer.apply(token));
        return entry != null && entry.token.equals(token) ? entry : null;
    }
}
