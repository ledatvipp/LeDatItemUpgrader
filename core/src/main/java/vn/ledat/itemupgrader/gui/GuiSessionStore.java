package vn.ledat.itemupgrader.gui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Bounded UI-only identities. No Player, inventory, ItemStack, escrow, fee or ledger lives here. */
public final class GuiSessionStore {
    public record Handle(UUID viewer, UUID session, UUID view, long revision, long sequence) {
        public Handle { Objects.requireNonNull(viewer); Objects.requireNonNull(session); Objects.requireNonNull(view); if (revision < 1 || sequence < 0) throw new IllegalArgumentException("invalid GUI handle"); }
    }
    public record State(Handle handle, GuiContext context, boolean busy) {}
    private static final class Entry {
        State state; final GuiSettings settings; long touched; long requestStarted; long lastAction; boolean acted;
        Entry(State state, GuiSettings settings, long now) { this.state=state; this.settings=settings; this.touched=now; }
    }
    private final Map<UUID, Entry> sessions = new HashMap<>();
    private final LongSupplier clock;
    private boolean stopped;
    public GuiSessionStore(LongSupplier clock) { this.clock = Objects.requireNonNull(clock); }
    public synchronized Optional<State> open(UUID viewer, long revision, GuiContext context, GuiSettings settings) {
        if (stopped || !settings.enabled() || (!sessions.containsKey(viewer) && sessions.size() >= settings.maximumSessions())) return Optional.empty();
        var state = new State(new Handle(viewer, UUID.randomUUID(), UUID.randomUUID(), revision, 0), Objects.requireNonNull(context), false);
        sessions.put(viewer, new Entry(state, settings, clock.getAsLong())); return Optional.of(state);
    }
    /** Claim synchronously inside click event; repeated click cannot queue a second action. */
    public synchronized Optional<State> reserve(Handle expected) {
        Entry e = match(expected);
        if (e == null || e.state.busy()) return Optional.empty();
        long now = clock.getAsLong();
        if (e.acted && now-e.lastAction < e.settings.clickCooldown().toNanos()) return Optional.empty();
        var h = e.state.handle();
        e.state = new State(new Handle(h.viewer(),h.session(),h.view(),h.revision(),h.sequence()+1),e.state.context(),true);
        e.touched=now; e.requestStarted=now; e.lastAction=now; e.acted=true;
        return Optional.of(e.state);
    }
    /** Called on the deferred owner callback. Changing screen creates a new view identity BEFORE opening it. */
    public synchronized Optional<State> transition(Handle expected, GuiContext context) {
        Entry e=match(expected);
        if (e==null || !e.state.busy()) return Optional.empty();
        var h=e.state.handle(); GuiContext old=e.state.context();
        UUID view=old.screen()==context.screen()&&old.page()==context.page()&&old.sort()==context.sort()&&old.category().equals(context.category())?h.view():UUID.randomUUID();
        e.state=new State(new Handle(h.viewer(),h.session(),view,h.revision(),h.sequence()+1),context,true);
        return Optional.of(e.state);
    }
    /** Async response may adopt recommended target only; never change source/profile/page/view silently. */
    public synchronized Optional<State> complete(Handle expected, GuiContext effective) {
        Entry e=match(expected);
        if (e==null || !e.state.busy() || !sameSelectionExceptRecommended(e.state.context(),effective)) return Optional.empty();
        e.state=new State(e.state.handle(),effective,false); e.touched=clock.getAsLong(); return Optional.of(e.state);
    }
    private static boolean sameSelectionExceptRecommended(GuiContext before, GuiContext after) {
        return before.equals(after) || (before.targetId().isEmpty() && before.target(after.targetId()).equals(after));
    }
    public synchronized Optional<State> get(UUID viewer) {
        Entry e=sessions.get(viewer); return stopped || e==null || expired(e) ? Optional.empty():Optional.of(e.state);
    }
    public synchronized boolean current(Handle handle) { return match(handle)!=null; }
    /** Compare session AND view: an old close event cannot close a newly opened page. Sequence may change mid-view. */
    public synchronized boolean close(UUID viewer, UUID session, UUID view) {
        Entry e=sessions.get(viewer);
        if(e==null || !e.state.handle().session().equals(session) || !e.state.handle().view().equals(view)) return false;
        sessions.remove(viewer); return true;
    }
    public synchronized Optional<State> forget(UUID viewer) { Entry e=sessions.remove(viewer); return e==null?Optional.empty():Optional.of(e.state); }
    public synchronized List<State> sweep(long activeRevision) {
        var removed=new ArrayList<State>();
        sessions.values().removeIf(e->{boolean remove=expired(e)||e.state.handle().revision()!=activeRevision;
            if(remove) removed.add(e.state); return remove;}); return List.copyOf(removed);
    }
    public synchronized List<State> stop() { stopped=true; var all=sessions.values().stream().map(e->e.state).toList(); sessions.clear(); return all; }
    public synchronized int size() { return sessions.size(); }
    private Entry match(Handle h) {
        Entry e=sessions.get(h.viewer()); return stopped||e==null||expired(e)||!e.state.handle().equals(h)?null:e;
    }
    private boolean expired(Entry e) {
        long now=clock.getAsLong(); return now-e.touched>=e.settings.idleTimeout().toNanos()
                || (e.state.busy() && now-e.requestStarted>=e.settings.requestTimeout().toNanos());
    }
}
