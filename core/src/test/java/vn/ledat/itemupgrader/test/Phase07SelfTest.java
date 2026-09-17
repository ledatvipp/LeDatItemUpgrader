package vn.ledat.itemupgrader.test;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import vn.ledat.itemupgrader.animation.*;
import vn.ledat.itemupgrader.gui.*;
import vn.ledat.itemupgrader.transaction.*;
import vn.ledat.itemupgrader.transaction.model.*;
import vn.ledat.itemupgrader.transaction.storage.*;
import vn.ledat.itemupgrader.demo.TransactionDemo;

/** Tests production core classes, no simulated Paper API. Native inventory/packet/scheduler integration is separate. */
public final class Phase07SelfTest {
    @FunctionalInterface interface Checked { void run() throws Exception; }
    private static final Map<String, Checked> TESTS = new LinkedHashMap<>();
    private static int assertions;
    private static final UUID VIEWER = new UUID(0, 1), REFERENCE = new UUID(1234, 5678);
    private Phase07SelfTest() {}
    public static void main(String[] args) throws Exception {
        presetTests(); timelineTests(); menuTests(); sessionTests(); readerTests(); clickTests();
        int failures = 0; var xml = new StringBuilder();
        for (var entry : TESTS.entrySet()) {
            long started = System.nanoTime(); String failure = null;
            try { entry.getValue().run(); }
            catch (Exception | AssertionError error) { failure = error.toString(); failures++; error.printStackTrace(System.err); }
            System.out.println((failure == null ? "PASS " : "FAIL ") + entry.getKey());
            xml.append("  <testcase classname=\"Phase07\" name=\"").append(escape(entry.getKey())).append("\" time=\"")
                    .append(String.format(Locale.ROOT, "%.6f", (System.nanoTime()-started)/1e9)).append("\">");
            if (failure != null) xml.append("<failure message=\"").append(escape(failure)).append("\"/>");
            xml.append("</testcase>\n");
        }
        Path path = Path.of(args.length > 0 ? args[0] : "core/build/reports/phase07-self-test.xml");
        Files.createDirectories(path.toAbsolutePath().getParent());
        Files.writeString(path, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuite name=\"ItemUpgraderPhase07\" tests=\""
                +TESTS.size()+"\" failures=\""+failures+"\" errors=\"0\">\n"+xml+"<system-out>assertions="+assertions
                +"; detached animation core; not native inventory/client tests</system-out>\n</testsuite>\n", StandardCharsets.UTF_8);
        System.out.println("RESULT tests="+TESTS.size()+" assertions="+assertions+" failures="+failures);
        if (failures > 0) throw new AssertionError(failures+" Phase7 tests failed");
    }
    private static void test(String name, Checked body) { if (TESTS.put(name, body) != null) throw new IllegalArgumentException("duplicate test"); }
    private static void check(boolean actual) { assertions++; if (!actual) throw new AssertionError("expected true"); }
    private static void eq(Object expected, Object actual) { assertions++; if (!Objects.equals(expected, actual)) throw new AssertionError("expected "+expected+", got "+actual); }
    private static void rejects(Checked body) throws Exception {
        assertions++;
        try { body.run(); }
        catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException expected) { return; }
        catch (CompletionException error) { if (error.getCause() instanceof IllegalArgumentException || error.getCause() instanceof IllegalStateException) return; throw error; }
        throw new AssertionError("expected rejection");
    }
    private static String escape(String text) { return text.replace("&","&amp;").replace("<","&lt;").replace("\"","&quot;"); }
    private static AnimationPreset preset(String id, AnimationPreset.Kind kind, int intro, int a, int s, int d, int land, int hold, int turns) {
        return new AnimationPreset(id, kind, Duration.ofMillis(intro), Duration.ofMillis(a), Duration.ofMillis(s),
                Duration.ofMillis(d), Duration.ofMillis(land), Duration.ofMillis(hold), turns);
    }
    private static AnimationRequest request(AnimationRequest.Outcome outcome) { return AnimationRequest.preview(VIEWER, REFERENCE, outcome); }
    private static AnimationTimeline timeline(AnimationPreset p) { return new AnimationTimeline(p, 12, request(AnimationRequest.Outcome.WIN)); }
    private static void presetTests() {
        test("preset.defaults-bounded-wall-time", () -> {
            eq(4_450_000_000L, AnimationPreset.roulette().totalNanos()); eq(1_600_000_000L, AnimationPreset.quick().totalNanos()); eq(700_000_000L, AnimationPreset.none().totalNanos());
        });
        test("preset.identifier-validation", () -> { for (String id : List.of("", "UPPER", "../x", "<red>", "x".repeat(33))) rejects(() -> preset(id,AnimationPreset.Kind.NONE,0,0,0,0,0,700,0)); });
        test("preset.none-forbids-hidden-spin-or-turns", () -> {
            rejects(() -> preset("x",AnimationPreset.Kind.NONE,1,0,0,0,0,700,0)); rejects(() -> preset("x",AnimationPreset.Kind.NONE,0,0,0,0,0,700,1));
        });
        test("preset.negative-duration-and-too-large", () -> {
            rejects(() -> preset("x",AnimationPreset.Kind.ROULETTE,-1,450,900,1700,250,900,4));
            rejects(() -> preset("x",AnimationPreset.Kind.ROULETTE,10001,450,900,1700,250,900,4));
        });
        test("preset.total-limit", () -> { rejects(() -> preset("x",AnimationPreset.Kind.ROULETTE,10000,1000,1000,1000,1000,5000,4)); });
        test("preset.motion-and-hold-minimum", () -> {
            rejects(() -> preset("x",AnimationPreset.Kind.QUICK,0,49,0,100,0,500,1));
            rejects(() -> preset("x",AnimationPreset.Kind.QUICK,0,50,0,99,0,500,1));
            rejects(() -> preset("x",AnimationPreset.Kind.QUICK,0,50,0,100,0,249,1));
            rejects(() -> preset("x",AnimationPreset.Kind.QUICK,0,50,0,100,0,5001,1));
            rejects(() -> preset("x",AnimationPreset.Kind.QUICK,0,50,0,100,0,500,21));
        });
        test("preset.zero-cruise-and-landing-supported", () -> {
            var p = preset("x",AnimationPreset.Kind.QUICK,0,50,0,100,0,250,1); eq(400_000_000L,p.totalNanos());
            eq(AnimationTimeline.Phase.DECELERATE,timeline(p).sample(50_000_000L).phase());
        });
        test("request.preview-not-fabricated-odds", () -> {
            var r=request(AnimationRequest.Outcome.WIN); eq(AnimationRequest.Origin.ADMIN_PREVIEW,r.origin()); check(r.probability().isEmpty()); eq(-1L,r.journalVersion()); eq(VIEWER,r.viewer());
        });
    }
    private static void timelineTests() {
        test("timeline.exact-phase-boundaries", () -> {
            var t=timeline(AnimationPreset.roulette()); long[] milliseconds={0,249,250,699,700,1599,1600,3299,3300,3549,3550,4449,4450};
            var phases=List.of(AnimationTimeline.Phase.INTRO,AnimationTimeline.Phase.INTRO,AnimationTimeline.Phase.ACCELERATE,
                    AnimationTimeline.Phase.ACCELERATE,AnimationTimeline.Phase.SPIN,AnimationTimeline.Phase.SPIN,
                    AnimationTimeline.Phase.DECELERATE,AnimationTimeline.Phase.DECELERATE,AnimationTimeline.Phase.LAND,
                    AnimationTimeline.Phase.LAND,AnimationTimeline.Phase.REVEAL,AnimationTimeline.Phase.REVEAL,AnimationTimeline.Phase.DONE);
            for(int i=0;i<milliseconds.length;i++) eq(phases.get(i),t.sample(milliseconds[i]*1_000_000).phase());
        });
        test("timeline.no-outcome-before-reveal", () -> {
            var p=AnimationPreset.roulette();var t=timeline(p);
            for(long ms=0;ms<3550;ms+=25) check(t.sample(ms*1_000_000).visibleOutcome().isEmpty());
            eq(Optional.of(AnimationRequest.Outcome.WIN),t.sample(p.revealNanos()).visibleOutcome());
        });
        test("timeline.instant-none-visible-immediately", () -> {
            var t=timeline(AnimationPreset.none());eq(AnimationTimeline.Phase.REVEAL,t.sample(0).phase());eq(t.stopIndex(),t.sample(0).markerIndex());
        });
        for (var p:List.of(AnimationPreset.quick(),AnimationPreset.roulette())) for(int n:List.of(4,12,36)) {
            test("timeline.monotonic-no-overshoot-"+p.id()+"-cells-"+n, () -> {
                var t=new AnimationTimeline(p,n,request(AnimationRequest.Outcome.WIN));int prior=0;
                for(long ms=0;ms<=p.totalNanos()/1_000_000+100;ms+=10){var f=t.sample(ms*1_000_000);check(f.ordinal()>=prior);check(f.ordinal()<=t.totalSteps());check(f.markerIndex()>=0&&f.markerIndex()<n);prior=f.ordinal();}
                eq(t.totalSteps(),prior);
            });
        }
        test("timeline.cosmetic-path-independent-of-selected-outcome", () -> {
            var p=AnimationPreset.roulette();var win=new AnimationTimeline(p,12,request(AnimationRequest.Outcome.WIN));
            var loss=new AnimationTimeline(p,12,request(AnimationRequest.Outcome.LOSS));
            for(long ms=0;ms<3550;ms+=25) eq(win.sample(ms*1_000_000),loss.sample(ms*1_000_000));
            eq(win.stopIndex(),loss.stopIndex());check(!win.reveal().visibleOutcome().equals(loss.reveal().visibleOutcome()));
        });
        test("timeline.signed-uuid-cosmetic-index-in-bounds", () -> {
            for(long n:List.of(Long.MIN_VALUE,-1L,0L,1L,Long.MAX_VALUE)) {
                var t=new AnimationTimeline(AnimationPreset.roulette(),36,AnimationRequest.preview(VIEWER,new UUID(n,Long.rotateLeft(n,8)),AnimationRequest.Outcome.LOSS));
                check(t.stopIndex()>=0&&t.stopIndex()<36);eq(t.stopIndex(),t.sample(Long.MAX_VALUE).markerIndex());
            }
        });
        test("timeline.negative-time-and-route-bounds", () -> {
            rejects(()->timeline(AnimationPreset.quick()).sample(-1));
            for(int n:List.of(-1,0,3,37,100))rejects(()->new AnimationTimeline(AnimationPreset.quick(),n,request(AnimationRequest.Outcome.WIN)));
        });
        test("timeline.frame-visibility-invariant", () -> {
            rejects(()->new AnimationTimeline.Frame(AnimationTimeline.Phase.SPIN,0,0,Optional.of(AnimationRequest.Outcome.WIN)));
            rejects(()->new AnimationTimeline.Frame(AnimationTimeline.Phase.REVEAL,0,0,Optional.empty()));
            rejects(()->new AnimationTimeline.Frame(AnimationTimeline.Phase.SPIN,0,36,Optional.empty()));
        });
        test("timeline.repeated-sample-deterministic", () -> {var t=timeline(AnimationPreset.roulette());var first=t.sample(1700000000);for(int i=0;i<100;i++)eq(first,t.sample(1700000000));});
    }
    private static AnimationMenu menu() {
        Map<AnimationMenu.Palette,MenuDefinition.Element> icons=new EnumMap<>(AnimationMenu.Palette.class);
        for(var p:AnimationMenu.Palette.values())icons.put(p,new MenuDefinition.Element(MenuDefinition.Role.FILLER,"PAPER",p.name(),List.of(),"",null,false,MenuDefinition.Action.NONE,""));
        return new AnimationMenu("<gold>Test</gold>",List.of("#########","##TTTTT##","##T#I#T##","##TTTTT##","####B####","##K###X##"),
                Map.of('#',AnimationMenu.Role.FILLER,'T',AnimationMenu.Role.TRACK,'I',AnimationMenu.Role.STATUS,'B',AnimationMenu.Role.BADGE,'K',AnimationMenu.Role.SKIP,'X',AnimationMenu.Role.CLOSE),
                List.of(11,12,13,14,15,24,33,32,31,30,29,20),icons);
    }
    private static AnimationConfiguration config() {
        return new AnimationConfiguration(true,64,16,Duration.ofMillis(1500),Duration.ofSeconds(5),Duration.ofMillis(120),"roulette",
                Map.of("roulette",AnimationPreset.roulette(),"quick",AnimationPreset.quick(),"none",AnimationPreset.none()),menu(),Map.of());
    }
    private static void menuTests() {
        test("menu.full-frame-and-fixed-actions", () -> {var m=menu();eq(54,m.size());eq(54,m.slots().size());eq(AnimationMenu.Role.SKIP,m.slots().get(47));eq(AnimationMenu.Role.CLOSE,m.slots().get(51));});
        test("menu.route-must-cover-track-exactly", () -> {
            var m=menu();var route=new ArrayList<>(m.trackOrder());route.removeLast();rejects(()->new AnimationMenu(m.title(),m.matrix(),m.symbols(),route,m.icons()));
            route.add(route.getFirst());rejects(()->new AnimationMenu(m.title(),m.matrix(),m.symbols(),route,m.icons()));
        });
        test("menu.route-rejects-button-outside-and-negative", () -> {
            var m=menu();for(int slot:List.of(-1,22,47,54)){var r=new ArrayList<>(m.trackOrder());r.set(0,slot);rejects(()->new AnimationMenu(m.title(),m.matrix(),m.symbols(),r,m.icons()));}
        });
        test("menu.ordered-route-is-used-not-sorted", () -> {
            var m=menu();var r=new ArrayList<>(m.trackOrder());Collections.reverse(r);var changed=new AnimationMenu(m.title(),m.matrix(),m.symbols(),r,m.icons());
            eq(AnimationMenu.Palette.MARKER,changed.frame(new AnimationTimeline.Frame(AnimationTimeline.Phase.SPIN,0,0,Optional.empty())).get(20));
        });
        test("menu.missing-badge-rejected", () -> {var m=menu();var matrix=new ArrayList<>(m.matrix());matrix.set(4,"#########");rejects(()->new AnimationMenu(m.title(),matrix,m.symbols(),m.trackOrder(),m.icons()));});
        test("menu.duplicate-status-rejected", () -> {var m=menu();var matrix=new ArrayList<>(m.matrix());matrix.set(0,"I########");rejects(()->new AnimationMenu(m.title(),matrix,m.symbols(),m.trackOrder(),m.icons()));});
        test("menu.bad-matrix-and-unknown-symbol", () -> {
            var m=menu();for(String row:List.of("########","##########","########?","######## ")){var a=new ArrayList<>(m.matrix());a.set(0,row);rejects(()->new AnimationMenu(m.title(),a,m.symbols(),m.trackOrder(),m.icons()));}
        });
        test("menu.palette-coverage-and-gameplay-actions-denied", () -> {
            var m=menu();var icons=new EnumMap<>(m.icons());icons.remove(AnimationMenu.Palette.WIN);rejects(()->new AnimationMenu(m.title(),m.matrix(),m.symbols(),m.trackOrder(),icons));
            icons.put(AnimationMenu.Palette.WIN,new MenuDefinition.Element(MenuDefinition.Role.BUTTON,"PAPER","x",List.of(),"",null,false,MenuDefinition.Action.UPGRADE,""));
            rejects(()->new AnimationMenu(m.title(),m.matrix(),m.symbols(),m.trackOrder(),icons));
        });
        test("menu.immutable-copy", () -> {
            var m=menu();rejects(()->m.trackOrder().clear());rejects(()->m.symbols().clear());rejects(()->m.icons().clear());rejects(()->m.frame(timeline(AnimationPreset.quick()).sample(0)).clear());
        });
        test("menu.dirty-update-usual-step-is-two-slots", () -> {
            var m=menu();var a=m.frame(new AnimationTimeline.Frame(AnimationTimeline.Phase.SPIN,1,1,Optional.empty()));
            var b=m.frame(new AnimationTimeline.Frame(AnimationTimeline.Phase.SPIN,2,2,Optional.empty()));
            eq(Set.of(12,13),SlotDiff.changed(a,b,m.size()).keySet());eq(0,SlotDiff.changed(b,b,m.size()).size());
        });
        test("menu.reveal-paints-result-not-whole-ring", () -> {
            var m=menu();var t=timeline(AnimationPreset.quick());var result=m.frame(t.reveal());
            eq(2L,result.values().stream().filter(p->p==AnimationMenu.Palette.WIN).count());eq(AnimationMenu.Palette.WIN,result.get(22));
            eq(AnimationMenu.Palette.WIN,result.get(m.trackOrder().get(t.stopIndex())));
        });
        test("menu.out-of-layout-frame-rejected", () -> {rejects(()->menu().frame(new AnimationTimeline.Frame(AnimationTimeline.Phase.SPIN,13,13,Optional.empty())));});
        test("config.default-layout-presets", () -> {var c=config();eq(64,c.maximumSessions());eq(16,c.visitsPerTick());eq(3,c.presets().size());});
        test("config.capacity-budget-validation", () -> {
            var c=config();for(int[] bounds:List.of(new int[]{0,1},new int[]{129,1},new int[]{2,3},new int[]{64,33},new int[]{64,0}))
                rejects(()->new AnimationConfiguration(true,bounds[0],bounds[1],c.openCooldown(),c.callbackTimeout(),c.pulseInterval(),c.defaultPreset(),c.presets(),c.menu(),c.sounds()));
        });
        test("config.default-preset-and-preset-key-validation", () -> {
            var c=config();rejects(()->new AnimationConfiguration(true,64,16,c.openCooldown(),c.callbackTimeout(),c.pulseInterval(),"missing",c.presets(),c.menu(),Map.of()));
            rejects(()->new AnimationConfiguration(true,64,16,c.openCooldown(),c.callbackTimeout(),c.pulseInterval(),"wrong",Map.of("wrong",AnimationPreset.none()),c.menu(),Map.of()));
        });
        test("config.no-sound-for-none-and-interval-limits", () -> {
            var c=config();rejects(()->new AnimationConfiguration(true,64,16,c.openCooldown(),c.callbackTimeout(),c.pulseInterval(),c.defaultPreset(),c.presets(),c.menu(),Map.of(AnimationSessionStore.Cue.NONE,new GuiSettings.Cue("",0,1))));
            rejects(()->new AnimationConfiguration(true,64,16,c.openCooldown(),c.callbackTimeout(),Duration.ofMillis(99),c.defaultPreset(),c.presets(),c.menu(),Map.of()));
        });
    }
    private record Harness(AtomicLong clock,AnimationSessionStore store,AnimationSessionStore.Token token) {}
    private static Harness opened(AnimationPreset preset) {return opened(preset,0);}
    private static Harness opened(AnimationPreset preset,long start) {
        var clock=new AtomicLong(start);var store=new AnimationSessionStore(clock::get);
        var token=store.open(UUID.randomUUID(),1,request(AnimationRequest.Outcome.WIN),preset,12,128,Duration.ofSeconds(5),Duration.ofMillis(120)).orElseThrow();
        return new Harness(clock,store,token);
    }
    private static void sessionTests() {
        test("session.open-bounded-one-per-viewer", () -> {
            var h=opened(AnimationPreset.quick());check(h.store().open(UUID.randomUUID(),1,request(AnimationRequest.Outcome.LOSS),AnimationPreset.quick(),12,128,Duration.ofSeconds(5),Duration.ofMillis(120)).isEmpty());eq(1,h.store().size());
        });
        test("session.capacity-is-hard-not-cache-eviction", () -> {
            var store=new AnimationSessionStore(()->0);for(int i=0;i<4;i++)check(store.open(UUID.randomUUID(),1,AnimationRequest.preview(new UUID(0,i),REFERENCE,AnimationRequest.Outcome.LOSS),AnimationPreset.quick(),12,4,Duration.ofSeconds(5),Duration.ofMillis(120)).isPresent());
            check(store.open(UUID.randomUUID(),1,request(AnimationRequest.Outcome.WIN),AnimationPreset.quick(),12,4,Duration.ofSeconds(5),Duration.ofMillis(120)).isEmpty());eq(4,store.size());
        });
        test("session.only-one-inflight-callback", () -> {
            var h=opened(AnimationPreset.roulette());var u=h.store().poll(1).getFirst();check(h.store().current(u));eq(0,h.store().poll(32).size());
            eq(Optional.of(AnimationSessionStore.Cue.START),h.store().acknowledge(u));check(h.store().acknowledge(u).isEmpty());
        });
        test("session.caller-cannot-ack-forged-sequence-or-frame", () -> {
            var h=opened(AnimationPreset.roulette());var u=h.store().poll(1).getFirst();
            var forged=new AnimationSessionStore.Update(u.token(),u.sequence()+1,u.frame(),u.end());check(h.store().acknowledge(forged).isEmpty());
            var wrongFrame=new AnimationSessionStore.Update(u.token(),u.sequence(),Optional.of(timeline(AnimationPreset.quick()).reveal()),Optional.empty());check(h.store().acknowledge(wrongFrame).isEmpty());check(h.store().current(u));
        });
        test("session.skip-invalidates-pending-render", () -> {
            var h=opened(AnimationPreset.roulette());var old=h.store().poll(1).getFirst();check(h.store().skip(h.token()));check(!h.store().current(old));check(h.store().acknowledge(old).isEmpty());
            var reveal=h.store().poll(1).getFirst();eq(AnimationTimeline.Phase.REVEAL,reveal.frame().orElseThrow().phase());eq(Optional.of(AnimationSessionStore.Cue.WIN),h.store().acknowledge(reveal));
        });
        test("session.repeat-skip-does-not-extend-result-hold", () -> {
            var h=opened(AnimationPreset.quick());check(h.store().skip(h.token()));var u=h.store().poll(1).getFirst();h.store().acknowledge(u);
            h.clock().set(699_000_000);check(!h.store().skip(h.token()));eq(0,h.store().poll(1).size());
            h.clock().set(700_000_000);eq(Optional.of(AnimationSessionStore.End.FINISHED),h.store().poll(1).getFirst().end());eq(0,h.store().size());
        });
        test("session.lag-jump-still-acknowledges-reveal-once", () -> {
            var h=opened(AnimationPreset.roulette());h.clock().set(20_000_000_000L);var u=h.store().poll(1).getFirst();
            eq(AnimationTimeline.Phase.REVEAL,u.frame().orElseThrow().phase());eq(Optional.of(AnimationSessionStore.Cue.WIN),h.store().acknowledge(u));
            h.clock().addAndGet(899_000_000);eq(0,h.store().poll(1).size());h.clock().addAndGet(1_000_000);eq(AnimationSessionStore.End.FINISHED,h.store().poll(1).getFirst().end().orElseThrow());
        });
        test("session.reveal-hold-begins-at-ack-not-offer", () -> {
            var h=opened(AnimationPreset.none());var u=h.store().poll(1).getFirst();h.clock().set(4_000_000_000L);h.store().acknowledge(u);
            h.clock().set(4_699_000_000L);eq(0,h.store().poll(1).size());h.clock().set(4_700_000_000L);check(h.store().poll(1).getFirst().end().isPresent());
        });
        test("session.callback-timeout-no-result-or-replay", () -> {
            var h=opened(AnimationPreset.roulette());var old=h.store().poll(1).getFirst();h.clock().set(5_000_000_000L);
            check(!h.store().current(old));check(h.store().acknowledge(old).isEmpty());
            var closed=h.store().poll(1).getFirst();eq(AnimationSessionStore.End.CALLBACK_TIMEOUT,closed.end().orElseThrow());check(closed.frame().isEmpty());eq(0,h.store().size());
        });
        test("session.revision-cleanup-cannot-close-replacement", () -> {
            var h=opened(AnimationPreset.quick());var old=h.store().poll(1).getFirst();eq(List.of(h.token()),h.store().invalidateRevision(2));check(!h.store().current(old));
            var fresh=h.store().open(UUID.randomUUID(),2,request(AnimationRequest.Outcome.LOSS),AnimationPreset.quick(),12,128,Duration.ofSeconds(5),Duration.ofMillis(120)).orElseThrow();
            check(!h.store().close(h.token()));check(h.store().current(fresh));
        });
        test("session.same-revision-does-not-stop-animation", () -> {var h=opened(AnimationPreset.quick());eq(List.of(),h.store().invalidateRevision(1));eq(1,h.store().size());});
        test("session.close-cleanup-no-zombie-queue", () -> {var h=opened(AnimationPreset.quick());check(h.store().close(h.token()));eq(0,h.store().poll(32).size());check(!h.store().skip(h.token()));eq(0,h.store().size());});
        test("session.stop-clears-all-and-rejects-late-work", () -> {
            var h=opened(AnimationPreset.quick());var old=h.store().poll(1).getFirst();eq(List.of(h.token()),h.store().stop());check(!h.store().current(old));check(h.store().acknowledge(old).isEmpty());eq(0,h.store().poll(1).size());eq(List.of(),h.store().stop());
            check(h.store().open(UUID.randomUUID(),1,request(AnimationRequest.Outcome.WIN),AnimationPreset.quick(),12,1,Duration.ofSeconds(5),Duration.ofMillis(120)).isEmpty());
        });
        test("session.nano-time-wrap", () -> {
            var h=opened(AnimationPreset.quick(),Long.MAX_VALUE-100_000_000);var first=h.store().poll(1).getFirst();h.store().acknowledge(first);
            h.clock().addAndGet(200_000_000);var next=h.store().poll(1).getFirst();eq(AnimationTimeline.Phase.SPIN,next.frame().orElseThrow().phase());check(h.store().current(next));
        });
        test("session.clock-backstep-does-not-rewind", () -> {
            var h=opened(AnimationPreset.roulette());h.clock().set(2_000_000_000);var first=h.store().poll(1).getFirst();h.store().acknowledge(first);
            h.clock().set(1_000_000_000);eq(0,h.store().poll(1).size());
        });
        test("session.frame-backlog-coalesced", () -> {
            var h=opened(AnimationPreset.roulette());var first=h.store().poll(1).getFirst();h.store().acknowledge(first);
            h.clock().set(3_000_000_000L);var updates=h.store().poll(32);eq(1,updates.size());eq(AnimationTimeline.Phase.DECELERATE,updates.getFirst().frame().orElseThrow().phase());eq(0,h.store().poll(32).size());
        });
        test("session.round-robin-budget-fairness", () -> {
            var clock=new AtomicLong();var store=new AnimationSessionStore(clock::get);Set<UUID> seen=new HashSet<>();
            for(int i=0;i<64;i++)store.open(UUID.randomUUID(),1,AnimationRequest.preview(new UUID(0,i),REFERENCE,AnimationRequest.Outcome.LOSS),AnimationPreset.roulette(),12,64,Duration.ofSeconds(5),Duration.ofMillis(120)).orElseThrow();
            for(int batch=0;batch<4;batch++){var updates=store.poll(16);eq(16,updates.size());for(var update:updates){check(seen.add(update.token().viewer()));store.acknowledge(update);}}
            eq(64,seen.size());eq(0,store.poll(16).size());
        });
        test("session.changed-only-still-rotates-to-others", () -> {
            var store=new AnimationSessionStore(()->0);
            for(int i=0;i<3;i++)store.open(UUID.randomUUID(),1,AnimationRequest.preview(new UUID(0,i),REFERENCE,AnimationRequest.Outcome.WIN),AnimationPreset.roulette(),12,4,Duration.ofSeconds(5),Duration.ofMillis(120)).orElseThrow();
            for(int i=0;i<3;i++)store.acknowledge(store.poll(1).getFirst());for(int i=0;i<6;i++)eq(0,store.poll(1).size());eq(3,store.size());
        });
        test("session.pulse-cues-rate-limited-not-replayed", () -> {
            var h=opened(AnimationPreset.roulette());int pulse=0,reveal=0;long last=-1;
            for(long ms=0;ms<5000;ms+=10){h.clock().set(ms*1_000_000);for(var u:h.store().poll(1))if(u.frame().isPresent()){
                var cue=h.store().acknowledge(u).orElseThrow();if(cue==AnimationSessionStore.Cue.PULSE){if(last>=0)check(ms-last>=120);last=ms;pulse++;}if(cue==AnimationSessionStore.Cue.WIN)reveal++;
            }}check(pulse>0&&pulse<=37);eq(1,reveal);
        });
        test("session.concurrent-open-single-winner", () -> {
            var store=new AnimationSessionStore(()->0);var winners=new AtomicInteger();
            try(var pool=Executors.newFixedThreadPool(8)){var jobs=new ArrayList<Future<?>>();for(int i=0;i<32;i++)jobs.add(pool.submit(()->{
                if(store.open(UUID.randomUUID(),1,request(AnimationRequest.Outcome.WIN),AnimationPreset.quick(),12,64,Duration.ofSeconds(5),Duration.ofMillis(120)).isPresent())winners.incrementAndGet();
            }));for(var job:jobs)job.get(5,TimeUnit.SECONDS);}eq(1,winners.get());eq(1,store.size());
        });
        test("session.reused-session-id-still-fenced-by-incarnation", () -> {
            var h=opened(AnimationPreset.quick());var old=h.store().poll(1).getFirst();h.store().close(h.token());
            var fresh=h.store().open(h.token().session(),1,request(AnimationRequest.Outcome.WIN),AnimationPreset.quick(),12,128,Duration.ofSeconds(5),Duration.ofMillis(120)).orElseThrow();
            var update=h.store().poll(1).getFirst();check(!fresh.equals(h.token()));check(h.store().current(update));check(!h.store().current(old));check(h.store().acknowledge(old).isEmpty());
        });
        test("session.skip-cannot-renew-expired-callback", () -> {
            var h=opened(AnimationPreset.quick());h.store().poll(1);h.clock().set(5_000_000_000L);check(!h.store().skip(h.token()));
            eq(AnimationSessionStore.End.CALLBACK_TIMEOUT,h.store().poll(1).getFirst().end().orElseThrow());
        });
        test("session.loss-reveal-cue-once-without-start-on-none", () -> {
            var store=new AnimationSessionStore(()->0);store.open(UUID.randomUUID(),1,request(AnimationRequest.Outcome.LOSS),AnimationPreset.none(),12,16,Duration.ofSeconds(5),Duration.ofMillis(120)).orElseThrow();
            var frame=store.poll(1).getFirst();eq(Optional.of(AnimationSessionStore.Cue.LOSS),store.acknowledge(frame));check(store.acknowledge(frame).isEmpty());eq(0,store.poll(1).size());
        });
        test("session.open-close-churn-does-not-grow-queue", () -> {
            var store=new AnimationSessionStore(()->0);
            for(int i=0;i<200;i++) {var token=store.open(new UUID(0,1),1,request(AnimationRequest.Outcome.WIN),AnimationPreset.quick(),12,1,Duration.ofSeconds(5),Duration.ofMillis(120)).orElseThrow();check(store.close(token));}
            eq(0,store.size());eq(0,store.poll(32).size());
        });
        test("session.invalid-budget-and-token-rejected", () -> {
            var h=opened(AnimationPreset.quick());for(int n:List.of(0,-1,33))rejects(()->h.store().poll(n));rejects(()->new AnimationSessionStore.Token(VIEWER,UUID.randomUUID(),REFERENCE,0));
        });
    }
    private static AttemptRecord drawn(boolean success) {
        var machine=new TransactionMachine();var record=AttemptRecord.initial(TransactionDemo.samplePlan(new UUID(0,9)),TransactionDemo.NOW);
        while(record.state()!=AttemptRecord.State.DRAW_INTENT){var d=machine.next(record,TransactionDemo.NOW);record=d.next();if(d.work()==TransactionMachine.Work.EFFECT)record=machine.receipt(record,record.steps().size()-1,new EffectReceipt(EffectReceipt.Status.APPLIED,"fixture"),TransactionDemo.NOW);}
        return machine.commitDraw(record,success?0:999_999_999L,TransactionDemo.NOW);
    }
    private static final class ReadOnlyJournal implements AsyncTransactionJournal {
        final CompletableFuture<Optional<AttemptRecord>> response;int reads;
        ReadOnlyJournal(AttemptRecord record){response=CompletableFuture.completedFuture(Optional.ofNullable(record));}
        ReadOnlyJournal(){response=new CompletableFuture<>();}
        public CompletionStage<Optional<AttemptRecord>> find(UUID id){reads++;return response;}
        public CompletionStage<TransactionJournal.Claim> claim(AttemptPlan plan,Instant now){throw new AssertionError("animation attempted claim");}
        public CompletionStage<Boolean> compareAndSet(AttemptRecord before,AttemptRecord after){throw new AssertionError("animation attempted CAS");}
        public CompletionStage<List<AttemptRecord>> unfinished(Optional<UUID> after,int limit){throw new AssertionError("animation attempted scan");}
        public CompletionStage<Optional<UUID>> activeAttempt(UUID viewer){throw new AssertionError("animation attempted lock");}
    }
    private static Optional<AnimationRequest> read(AttemptRecord record){return new CommittedAnimationReader(new ReadOnlyJournal(record)).read(record.plan().playerId(),record.id()).toCompletableFuture().join();}
    private static void readerTests() {
        test("reader.only-find-no-financial-write", () -> {
            var r=drawn(true);byte[] before=JournalCodec.encodeRecord(r);var repo=new ReadOnlyJournal(r);
            var request=new CommittedAnimationReader(repo).read(r.plan().playerId(),r.id()).toCompletableFuture().join().orElseThrow();
            eq(1,repo.reads);eq(AnimationRequest.Origin.JOURNAL_OUTCOME,request.origin());eq(AnimationRequest.Outcome.WIN,request.outcome());eq(r.version(),request.journalVersion());
            eq("9",request.probability().orElseThrow().percent().toPlainString());check(Arrays.equals(before,JournalCodec.encodeRecord(r)));
        });
        test("reader.committed-loss-is-not-internal-error", () -> {eq(AnimationRequest.Outcome.LOSS,read(drawn(false)).orElseThrow().outcome());});
        test("reader.absent-and-wrong-player-denied", () -> {
            var r=drawn(true);check(new CommittedAnimationReader(new ReadOnlyJournal(null)).read(VIEWER,r.id()).toCompletableFuture().join().isEmpty());
            check(new CommittedAnimationReader(new ReadOnlyJournal(r)).read(new UUID(0,999),r.id()).toCompletableFuture().join().isEmpty());
        });
        test("reader.wrong-attempt-id-not-trusted", () -> {var r=drawn(true);rejects(()->new CommittedAnimationReader(new ReadOnlyJournal(r)).read(r.plan().playerId(),UUID.randomUUID()).toCompletableFuture().join());});
        test("reader.prepared-does-not-reveal", () -> {var r=AttemptRecord.initial(TransactionDemo.samplePlan(new UUID(0,10)),TransactionDemo.NOW);check(read(r).isEmpty());});
        test("reader.draw-intent-no-redraw", () -> {
            var r=drawn(true);var intent=new AttemptRecord(r.plan(),AttemptRecord.State.DRAW_INTENT,r.version()-1,r.steps(),null,r.createdAt(),r.updatedAt(),"");check(read(intent).isEmpty());
        });
        test("reader.reconciliation-even-with-sample-denied", () -> {var r=new TransactionMachine().quarantine(drawn(true),TransactionDemo.NOW,"TEST");check(read(r).isEmpty());});
        test("reader.corrupt-journal-fails-not-random-fallback", () -> {
            var r=drawn(true);var corrupted=new AttemptRecord(r.plan(),AttemptRecord.State.OUTCOME_COMMITTED,r.version(),List.of(),r.sample(),r.createdAt(),r.updatedAt(),"");rejects(()->read(corrupted));
        });
        test("reader.settling-and-completed-allowed-outcome-not-delivery-proof", () -> {
            var machine=new TransactionMachine();var r=machine.next(drawn(true),TransactionDemo.NOW).next();eq(AttemptRecord.State.SETTLING,r.state());check(read(r).isPresent());
            while(!r.terminal()){var d=machine.next(r,TransactionDemo.NOW);r=d.next();if(d.work()==TransactionMachine.Work.EFFECT)r=machine.receipt(r,r.steps().size()-1,new EffectReceipt(EffectReceipt.Status.APPLIED,"fixture"),TransactionDemo.NOW);}
            eq(AttemptRecord.State.COMPLETED,r.state());check(read(r).isPresent());
        });
        test("reader.async-no-blocking-wait-or-owner-work", () -> {
            var r=drawn(true);var repo=new ReadOnlyJournal();var future=new CommittedAnimationReader(repo).read(r.plan().playerId(),r.id()).toCompletableFuture();
            check(!future.isDone());repo.response.complete(Optional.of(r));check(future.join().isPresent());eq(1,repo.reads);
        });
        test("reader.storage-error-propagates-no-fake-outcome", () -> {
            var r=drawn(true);var repo=new ReadOnlyJournal();var future=new CommittedAnimationReader(repo).read(r.plan().playerId(),r.id()).toCompletableFuture();repo.response.completeExceptionally(new IllegalStateException("storage"));rejects(future::join);
        });
    }
    private static GuiClickPolicy.Input click(int raw,GuiClickPolicy.Click type){return new GuiClickPolicy.Input(true,true,false,false,true,false,54,raw,0,type,GuiClickPolicy.Effect.NOTHING);}
    private static void clickTests() {
        test("click.top-skip-close-only", () -> {var p=new AnimationClickPolicy();var m=menu();eq(Optional.of(AnimationMenu.Role.SKIP),p.decide(click(47,GuiClickPolicy.Click.LEFT),m).action());eq(Optional.of(AnimationMenu.Role.CLOSE),p.decide(click(51,GuiClickPolicy.Click.LEFT),m).action());check(p.decide(click(22,GuiClickPolicy.Click.LEFT),m).action().isEmpty());});
        test("click.all-non-left-blocked", () -> {var p=new AnimationClickPolicy();for(var type:GuiClickPolicy.Click.values())if(type!=GuiClickPolicy.Click.LEFT){var result=p.decide(click(47,type),menu());check(result.cancel());check(result.action().isEmpty());}});
        test("click.bottom-outside-cannot-select-source", () -> {var p=new AnimationClickPolicy();for(int raw:List.of(-999,-1,54,55,89,90,999,Integer.MAX_VALUE)){var result=p.decide(click(raw,GuiClickPolicy.Click.LEFT),menu());check(result.cancel());check(result.action().isEmpty());}});
        test("click.foreign-menu-not-cancelled", () -> {var i=new GuiClickPolicy.Input(false,true,false,false,true,false,54,47,0,GuiClickPolicy.Click.LEFT,GuiClickPolicy.Effect.NOTHING);var r=new AnimationClickPolicy().decide(i,menu());check(!r.cancel());check(r.action().isEmpty());});
        test("click.stale-busy-cursor-cancelled-creative-denied", () -> {
            var p=new AnimationClickPolicy();for(int flag=0;flag<5;flag++){
                var input=new GuiClickPolicy.Input(true,flag!=0,flag==1,flag==2,flag!=3,flag==4,54,47,0,GuiClickPolicy.Click.LEFT,GuiClickPolicy.Effect.NOTHING);
                var decision=p.decide(input,menu());check(decision.cancel());check(decision.action().isEmpty());
            }
        });
        test("click.layout-size-mismatch-denied", () -> {var input=new GuiClickPolicy.Input(true,true,false,false,true,false,27,22,0,GuiClickPolicy.Click.LEFT,GuiClickPolicy.Effect.NOTHING);check(new AnimationClickPolicy().decide(input,menu()).action().isEmpty());});
    }
}
