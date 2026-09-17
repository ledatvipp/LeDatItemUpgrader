package vn.ledat.itemupgrader.animation;

import java.time.Duration;
import java.util.*;
import vn.ledat.itemupgrader.gui.GuiSettings;

/** Preview switch never enables transactions. One batch task services a bounded number of active viewers. */
public record AnimationConfiguration(boolean previewEnabled, int maximumSessions, int visitsPerTick,
        Duration openCooldown, Duration callbackTimeout, Duration pulseInterval, String defaultPreset,
        Map<String, AnimationPreset> presets, AnimationMenu menu, Map<AnimationSessionStore.Cue, GuiSettings.Cue> sounds) {
    public AnimationConfiguration {
        if (maximumSessions < 1 || maximumSessions > 128 || visitsPerTick < 1 || visitsPerTick > 32 || visitsPerTick > maximumSessions)
            throw new IllegalArgumentException("animation capacity/batch outside limits");
        bounded(openCooldown, 500, 30000); bounded(callbackTimeout, 1000, 10000); bounded(pulseInterval, 100, 1000);
        presets = Map.copyOf(presets); Objects.requireNonNull(menu); sounds = Map.copyOf(sounds);
        if (presets.isEmpty() || presets.size() > 16 || !presets.containsKey(defaultPreset)
                || presets.entrySet().stream().anyMatch(e -> !e.getKey().equals(e.getValue().id())))
            throw new IllegalArgumentException("animation presets/default mismatch");
        if (sounds.containsKey(AnimationSessionStore.Cue.NONE)) throw new IllegalArgumentException("NONE cannot have a sound");
    }
    private static void bounded(Duration value, int min, int max) {
        Objects.requireNonNull(value);
        if (value.compareTo(Duration.ofMillis(min)) < 0 || value.compareTo(Duration.ofMillis(max)) > 0)
            throw new IllegalArgumentException("animation duration outside " + min + ".." + max + "ms");
    }
}
