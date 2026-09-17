package vn.ledat.itemupgrader.animation;

import java.util.Objects;
import java.util.Optional;

/** Pure O(1) sampling: missed frames are skipped, never replayed. Route geometry does NOT encode odds. */
public final class AnimationTimeline {
    public enum Phase { INTRO, ACCELERATE, SPIN, DECELERATE, LAND, REVEAL, DONE }
    public record Frame(Phase phase, int ordinal, int markerIndex, Optional<AnimationRequest.Outcome> visibleOutcome) {
        public Frame {
            Objects.requireNonNull(phase); Objects.requireNonNull(visibleOutcome);
            if (ordinal < 0 || markerIndex < 0 || markerIndex > 35) throw new IllegalArgumentException("invalid animation position");
            if ((phase == Phase.REVEAL || phase == Phase.DONE) != visibleOutcome.isPresent())
                throw new IllegalArgumentException("outcome visibility does not match phase");
        }
    }
    private final AnimationPreset preset;
    private final AnimationRequest request;
    private final int routeLength, stopIndex, totalSteps;
    public AnimationTimeline(AnimationPreset preset, int routeLength, AnimationRequest request) {
        this.preset = Objects.requireNonNull(preset); this.request = Objects.requireNonNull(request);
        if (routeLength < 4 || routeLength > 36) throw new IllegalArgumentException("route needs 4..36 cells");
        this.routeLength = routeLength;
        // Deterministic cosmetic landing, unrelated to chance/sample. No near-miss manipulation or RNG.
        this.stopIndex = Math.floorMod(Long.hashCode(request.reference().getMostSignificantBits()
                ^ Long.rotateLeft(request.reference().getLeastSignificantBits(), 17)), routeLength);
        this.totalSteps = preset.turns() * routeLength + stopIndex;
    }
    public int stopIndex() { return stopIndex; }
    public int totalSteps() { return totalSteps; }
    public Frame reveal() { return new Frame(Phase.REVEAL, totalSteps, stopIndex, Optional.of(request.outcome())); }
    public Frame sample(long elapsedNanos) {
        if (elapsedNanos < 0) throw new IllegalArgumentException("negative animation elapsed time");
        if (elapsedNanos >= preset.totalNanos()) return new Frame(Phase.DONE, totalSteps, stopIndex, Optional.of(request.outcome()));
        if (elapsedNanos >= preset.revealNanos()) return reveal();
        long movement = elapsedNanos - preset.intro().toNanos();
        if (movement < 0) return new Frame(Phase.INTRO, 0, 0, Optional.empty());
        if (movement >= preset.movementNanos()) return new Frame(Phase.LAND, totalSteps, stopIndex, Optional.empty());
        double a = preset.acceleration().toNanos(), s = preset.cruise().toNanos(), d = preset.deceleration().toNanos();
        double distance;
        Phase phase;
        if (movement < a) {
            phase = Phase.ACCELERATE; distance = movement * (double) movement / (2 * a);
        } else if (movement < a + s) {
            phase = Phase.SPIN; distance = a / 2 + movement - a;
        } else {
            phase = Phase.DECELERATE; double u = (movement - a - s) / d;
            // Integral of v*(1-u)^2: velocity continuous at cruise boundary, reaches zero without overshoot.
            distance = a / 2 + s + d * (u - u * u + u * u * u / 3);
        }
        int ordinal = Math.max(0, Math.min(totalSteps, (int) Math.floor(totalSteps * distance / (a / 2 + s + d / 3))));
        return new Frame(phase, ordinal, ordinal % routeLength, Optional.empty());
    }
}
