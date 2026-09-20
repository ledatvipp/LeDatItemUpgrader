package vn.ledat.itemupgrader.animation;

import java.time.Duration;
import java.util.*;
import java.util.function.LongSupplier;

/**
 * Bounded presentation-only sessions. UUIDs only; no Player, executor, transaction/effect port or RNG.
 * One outstanding callback per session. Returning from this store never authorizes reward or refund.
 */
public final class AnimationSessionStore {
    public enum Cue { NONE, START, PULSE, WIN, LOSS }
    public enum End { FINISHED, CALLBACK_TIMEOUT }
    public record Token(UUID viewer, UUID session, UUID reference, long revision, long incarnation) {
        public Token(UUID viewer, UUID session, UUID reference, long revision) { this(viewer, session, reference, revision, 1); }
        public Token {
            Objects.requireNonNull(viewer); Objects.requireNonNull(session); Objects.requireNonNull(reference);
            if (revision < 1 || incarnation < 1) throw new IllegalArgumentException("invalid animation revision/incarnation");
        }
    }
    public record Update(Token token, long sequence, long titleFrame, Optional<AnimationTimeline.Frame> frame, Optional<End> end) {
        public Update(Token token, long sequence, Optional<AnimationTimeline.Frame> frame, Optional<End> end) {
            this(token, sequence, 0, frame, end);
        }
        public Update {
            Objects.requireNonNull(token); Objects.requireNonNull(frame); Objects.requireNonNull(end);
            if (sequence < 1 || titleFrame < 0 || frame.isPresent() == end.isPresent()) throw new IllegalArgumentException("invalid animation update");
        }
    }
    private static final class Entry {
        final Token token; final AnimationTimeline timeline; final AnimationPreset preset;
        final long started, callbackTimeout, pulseInterval, titleInterval;
        final int titleFrames;
        long elapsed, sequence, sentAt, lastSoundAt, revealAt, lastTitleFrame = -1, offeredTitleFrame = -1;
        boolean skipped, outstanding, sounded, revealed;
        AnimationTimeline.Frame lastPresented, offered;
        Entry(Token token, AnimationRequest request, AnimationPreset preset, int cells, long started, long timeout,
              long interval, int titleFrames, long titleInterval) {
            this.token = token; this.preset = preset; this.started = started; this.callbackTimeout = timeout; this.pulseInterval = interval;
            this.titleFrames = titleFrames; this.titleInterval = titleInterval;
            timeline = new AnimationTimeline(preset, cells, request);
        }
    }
    private final LongSupplier clock;
    private final Map<UUID, Entry> entries = new HashMap<>();
    private final ArrayDeque<Token> order = new ArrayDeque<>();
    private boolean stopped;
    private long incarnation;
    public AnimationSessionStore(LongSupplier clock) { this.clock = Objects.requireNonNull(clock); }
    public synchronized Optional<Token> open(UUID session, long revision, AnimationRequest request, AnimationPreset preset,
                                             int cells, int capacity, Duration timeout, Duration pulseInterval) {
        return open(session, revision, request, preset, cells, capacity, timeout, pulseInterval, 1, Duration.ofMillis(50));
    }
    public synchronized Optional<Token> open(UUID session, long revision, AnimationRequest request, AnimationPreset preset,
                                             int cells, int capacity, Duration timeout, Duration pulseInterval,
                                             int titleFrames, Duration titleInterval) {
        Objects.requireNonNull(request); Objects.requireNonNull(timeout); Objects.requireNonNull(pulseInterval);
        if (capacity < 1 || capacity > 128 || timeout.compareTo(Duration.ofSeconds(1)) < 0 || timeout.compareTo(Duration.ofSeconds(10)) > 0
                || pulseInterval.compareTo(Duration.ofMillis(100)) < 0 || pulseInterval.compareTo(Duration.ofSeconds(1)) > 0
                || titleFrames < 1 || titleFrames > 200 || titleInterval == null
                || titleInterval.compareTo(Duration.ofMillis(50)) < 0 || titleInterval.compareTo(Duration.ofSeconds(1)) > 0)
            throw new IllegalArgumentException("invalid animation limits");
        if (stopped || entries.containsKey(request.viewer()) || entries.size() >= capacity) return Optional.empty();
        if (incarnation == Long.MAX_VALUE) throw new IllegalStateException("animation incarnation exhausted");
        Token token = new Token(request.viewer(), session, request.reference(), revision, ++incarnation);
        entries.put(request.viewer(), new Entry(token, request, preset, cells, clock.getAsLong(), timeout.toNanos(),
                pulseInterval.toNanos(), titleFrames, titleInterval.toNanos()));
        order.addLast(token); return Optional.of(token);
    }
    public synchronized int size() { return entries.size(); }
    public synchronized boolean current(Token token) { return entry(token) != null; }
    public synchronized boolean current(Update update) {
        var e = entry(update.token());
        return e != null && update.end().isEmpty() && e.outstanding && e.sequence == update.sequence()
                && clock.getAsLong() - e.sentAt < e.callbackTimeout
                && e.offeredTitleFrame == update.titleFrame() && Objects.equals(e.offered, update.frame().orElse(null));
    }
    /** At most budget sessions inspected. Round-robin fairness, no full player scan or catch-up frame queue. */
    public synchronized List<Update> poll(int budget) {
        if (budget < 1 || budget > 32) throw new IllegalArgumentException("animation visit budget 1..32 required");
        if (stopped) return List.of();
        List<Update> updates = new ArrayList<>(); long now = clock.getAsLong();
        int count = Math.min(budget, order.size());
        for (int i = 0; i < count; i++) {
            Token token = order.removeFirst(); Entry e = entry(token);
            if (e == null) continue;
            e.elapsed = Math.max(e.elapsed, Math.max(0, now - e.started)); // nanoTime wrap safe within bounded durations; never rewinds
            if (e.outstanding && now - e.sentAt >= e.callbackTimeout) {
                entries.remove(token.viewer()); updates.add(closing(e, End.CALLBACK_TIMEOUT)); continue;
            }
            if (e.revealed && now - e.revealAt >= e.preset.revealHold().toNanos()) {
                entries.remove(token.viewer()); updates.add(closing(e, End.FINISHED)); continue;
            }
            order.addLast(token);
            if (e.outstanding) continue;
            AnimationTimeline.Frame frame = e.skipped || e.revealed ? e.timeline.reveal() : e.timeline.sample(e.elapsed);
            // Even a long lag gap gets one acknowledged reveal before its hold timer starts.
            if (frame.phase() == AnimationTimeline.Phase.DONE) frame = e.timeline.reveal();
            long titleFrame = e.titleFrames == 1 ? 0 : (e.elapsed / e.titleInterval) % e.titleFrames;
            if (frame.equals(e.lastPresented) && titleFrame == e.lastTitleFrame) continue;
            e.offered = frame; e.offeredTitleFrame = titleFrame; e.outstanding = true; e.sentAt = now;
            updates.add(new Update(token, ++e.sequence, titleFrame, Optional.of(frame), Optional.empty()));
        }
        return List.copyOf(updates);
    }
    /** Call only after actual owner-thread render succeeded. Acknowledgement is idempotent per offered frame. */
    public synchronized Optional<Cue> acknowledge(Update update) {
        if (!current(update)) return Optional.empty();
        Entry e = entry(update.token()); long now = clock.getAsLong(); var frame = e.offered;
        Cue cue = Cue.NONE;
        if (frame.visibleOutcome().isPresent() && !e.revealed) {
            e.revealed = true; e.revealAt = now;
            cue = frame.visibleOutcome().orElseThrow() == AnimationRequest.Outcome.WIN ? Cue.WIN : Cue.LOSS;
        } else if (e.lastPresented == null) cue = Cue.START;
        else if (!e.revealed && frame.ordinal() != e.lastPresented.ordinal()
                && (!e.sounded || now - e.lastSoundAt >= e.pulseInterval)) cue = Cue.PULSE;
        if (cue != Cue.NONE) { e.sounded = true; e.lastSoundAt = now; }
        e.lastPresented = frame; e.lastTitleFrame = e.offeredTitleFrame;
        e.offered = null; e.offeredTitleFrame = -1; e.outstanding = false;
        return Optional.of(cue);
    }
    /** Skips only remaining visuals. A pending older render loses its sequence; repeated skip cannot extend hold. */
    public synchronized boolean skip(Token token) {
        Entry e = entry(token);
        if (e == null || e.skipped || e.revealed || e.outstanding && clock.getAsLong() - e.sentAt >= e.callbackTimeout) return false;
        e.skipped = true; e.outstanding = false; e.offered = null; e.offeredTitleFrame = -1; e.sequence++;
        return true;
    }
    public synchronized boolean close(Token token) {
        Entry e = entry(token); if (e == null) return false;
        entries.remove(token.viewer()); order.remove(token); return true;
    }
    /** Reload cleanup only; caller closes exact owned holders. It never touches a transaction journal. */
    public synchronized List<Token> invalidateRevision(long revision) {
        List<Token> removed = entries.values().stream().map(e -> e.token).filter(t -> t.revision() != revision).toList();
        removed.forEach(this::close); return removed;
    }
    public synchronized List<Token> stop() {
        if (stopped) return List.of(); stopped = true;
        var removed = entries.values().stream().map(e -> e.token).toList(); entries.clear(); order.clear(); return removed;
    }
    private Entry entry(Token token) {
        if (token == null) return null;
        Entry e = entries.get(token.viewer()); return e != null && e.token.equals(token) ? e : null;
    }
    private static Update closing(Entry e, End end) { return new Update(e.token, ++e.sequence, 0, Optional.empty(), Optional.of(end)); }
}
