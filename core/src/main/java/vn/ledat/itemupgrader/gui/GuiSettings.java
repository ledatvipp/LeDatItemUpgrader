package vn.ledat.itemupgrader.gui;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** Limits apply to preview UI only. No option in this model can enable live transactions. */
public record GuiSettings(boolean enabled, int maximumSessions, Duration idleTimeout, Duration requestTimeout,
                          Duration clickCooldown, Duration openCooldown, int maximumIconBytes, Map<String, Cue> sounds) {
    public record Cue(String key, float volume, float pitch) {
        public Cue {
            Objects.requireNonNull(key);
            if (!key.isEmpty() && !key.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("invalid GUI sound key");
            if (!Float.isFinite(volume) || volume < 0 || volume > 2 || !Float.isFinite(pitch) || pitch < 0.5f || pitch > 2)
                throw new IllegalArgumentException("invalid GUI sound volume/pitch");
        }
    }
    public GuiSettings {
        if (maximumSessions < 1 || maximumSessions > 256 || maximumIconBytes < 1024 || maximumIconBytes > 65536)
            throw new IllegalArgumentException("invalid GUI capacity/preview bytes");
        bounded(idleTimeout, Duration.ofSeconds(30), Duration.ofMinutes(30));
        bounded(requestTimeout, Duration.ofSeconds(1), Duration.ofSeconds(30));
        bounded(clickCooldown, Duration.ofMillis(100), Duration.ofSeconds(3));
        bounded(openCooldown, Duration.ofMillis(250), Duration.ofSeconds(10));
        sounds = Map.copyOf(sounds);
        if (!java.util.Set.of("open", "click", "error", "close", "title-pulse").containsAll(sounds.keySet())) throw new IllegalArgumentException("unknown GUI sound cue");
    }
    private static void bounded(Duration d, Duration min, Duration max) {
        Objects.requireNonNull(d);
        if (d.compareTo(min) < 0 || d.compareTo(max) > 0) throw new IllegalArgumentException("GUI duration outside bounds");
    }
    public static GuiSettings defaults() { return new GuiSettings(true, 128, Duration.ofMinutes(10), Duration.ofSeconds(15),
            Duration.ofMillis(150), Duration.ofMillis(750), 32768, Map.of()); }
}
