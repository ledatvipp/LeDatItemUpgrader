package vn.ledat.itemupgrader.demo;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import vn.ledat.itemupgrader.animation.*;

/** Text-only admin simulation; no Bukkit/Platform, database, provider, RNG or transaction effects. */
public final class AnimationDemo {
    private AnimationDemo() {}
    public static void main(String[] args) {
        var preset = AnimationPreset.roulette();
        var clock = new AtomicLong(); var sessions = new AnimationSessionStore(clock::get);
        UUID viewer = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var request = AnimationRequest.preview(viewer, UUID.fromString("12345678-1234-5678-1234-567812345678"), AnimationRequest.Outcome.WIN);
        var token = sessions.open(UUID.randomUUID(), 1, request, preset, 12, 16, Duration.ofSeconds(5), Duration.ofMillis(120)).orElseThrow();
        System.out.println("ADMIN UI SIMULATION. Selected outcome=WIN, no odds fabricated; no items, money, RNG or rewards.");
        AnimationTimeline.Phase previous = null;
        for (int millis = 0; millis < 6500; millis += 50) {
            clock.set(millis * 1_000_000L);
            for (var update : sessions.poll(16)) {
                if (update.end().isPresent()) { System.out.println(millis + "ms CLOSE " + update.end().orElseThrow()); continue; }
                var frame = update.frame().orElseThrow(); var cue = sessions.acknowledge(update).orElseThrow();
                if (frame.phase() != previous || cue == AnimationSessionStore.Cue.WIN) {
                    System.out.println(millis + "ms " + frame.phase() + " marker=" + frame.markerIndex() + " outcome="
                            + frame.visibleOutcome().map(Enum::name).orElse("HIDDEN") + " cue=" + cue); previous = frame.phase();
                }
            }
        }
        System.out.println("Active after completion=" + sessions.size() + ", stale skip=" + sessions.skip(token));
        System.out.println("Lag test: first rendering callback at t=20s still shows exactly one reveal then holds it.");
        clock.set(0);
        var second = sessions.open(UUID.randomUUID(), 1, request, preset, 12, 16, Duration.ofSeconds(5), Duration.ofMillis(120)).orElseThrow();
        clock.set(20_000_000_000L);
        var reveal = sessions.poll(1).getFirst();
        System.out.println("t=20s phase=" + reveal.frame().orElseThrow().phase() + " cue=" + sessions.acknowledge(reveal).orElseThrow());
        System.out.println("Skip after reveal=" + sessions.skip(second) + " (does not extend result hold)");
        clock.addAndGet(preset.revealHold().toNanos());
        System.out.println("After hold=" + sessions.poll(1).getFirst().end().orElseThrow());
    }
}
