package vn.ledat.itemupgrader.animation;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Wall-clock presentation durations, not server ticks or financial delays. */
public record AnimationPreset(String id, Kind kind, Duration intro, Duration acceleration,
        Duration cruise, Duration deceleration, Duration landing, Duration revealHold, int turns) {
    public enum Kind { NONE, QUICK, ROULETTE }
    public AnimationPreset {
        if (id == null || !id.matches("[a-z0-9][a-z0-9_-]{0,31}")) throw new IllegalArgumentException("invalid animation preset id");
        Objects.requireNonNull(kind);
        for (Duration duration : List.of(intro, acceleration, cruise, deceleration, landing, revealHold)) {
            if (duration.isNegative() || duration.compareTo(Duration.ofSeconds(10)) > 0)
                throw new IllegalArgumentException("animation stage duration outside 0..10s");
        }
        if (revealHold.compareTo(Duration.ofMillis(250)) < 0 || revealHold.compareTo(Duration.ofSeconds(5)) > 0)
            throw new IllegalArgumentException("reveal hold must be 250..5000ms");
        if (kind == Kind.NONE) {
            if (turns != 0 || !intro.isZero() || !acceleration.isZero() || !cruise.isZero()
                    || !deceleration.isZero() || !landing.isZero()) throw new IllegalArgumentException("NONE is reveal only");
        } else {
            if (turns < 1 || turns > 20 || acceleration.compareTo(Duration.ofMillis(50)) < 0
                    || deceleration.compareTo(Duration.ofMillis(100)) < 0)
                throw new IllegalArgumentException("animated presets require 1..20 turns, acceleration >=50ms, deceleration >=100ms");
        }
        if (intro.plus(acceleration).plus(cruise).plus(deceleration).plus(landing).plus(revealHold).compareTo(Duration.ofSeconds(15)) > 0)
            throw new IllegalArgumentException("animation total exceeds 15s");
    }
    public long movementNanos() { return acceleration.plus(cruise).plus(deceleration).toNanos(); }
    public long revealNanos() { return intro.toNanos() + movementNanos() + landing.toNanos(); }
    public long totalNanos() { return revealNanos() + revealHold.toNanos(); }
    public static AnimationPreset roulette() { return new AnimationPreset("roulette", Kind.ROULETTE,
            Duration.ofMillis(250), Duration.ofMillis(450), Duration.ofMillis(900), Duration.ofMillis(1700),
            Duration.ofMillis(250), Duration.ofMillis(900), 4); }
    public static AnimationPreset quick() { return new AnimationPreset("quick", Kind.QUICK,
            Duration.ofMillis(100), Duration.ofMillis(100), Duration.ofMillis(150), Duration.ofMillis(400),
            Duration.ofMillis(150), Duration.ofMillis(700), 2); }
    public static AnimationPreset none() { return new AnimationPreset("none", Kind.NONE,
            Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ofMillis(700), 0); }
}
