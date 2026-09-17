package vn.ledat.itemupgrader.test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import vn.ledat.itemupgrader.catalog.*;
import vn.ledat.itemupgrader.condition.ConditionDefinition;
import vn.ledat.itemupgrader.cost.ResourceSnapshot;
import vn.ledat.itemupgrader.demo.GuiDemo;
import vn.ledat.itemupgrader.gui.*;
import static vn.ledat.itemupgrader.demo.GuiDemo.*;

/** Tests the actual core GUI model/policy/facade. NOT mocks of Bukkit, packets, inventory clicks or providers. */
public final class Phase06SelfTest {
    @FunctionalInterface interface Checked { void run() throws Exception; }
    private static final Map<String,Checked> TESTS=new LinkedHashMap<>();
    private static int assertions;
    private Phase06SelfTest() {}
    public static void main(String[] args)throws Exception {
        contextTests();settingsTests();menuTests();sessionTests();clickTests();diffTests();previewTests();
        int failures=0;var xml=new StringBuilder();
        for(var entry:TESTS.entrySet()) {
            long start=System.nanoTime();String failure=null;
            try {entry.getValue().run();} catch(Exception|AssertionError error) {failure=error.toString();failures++;error.printStackTrace(System.err);}
            System.out.println((failure==null?"PASS ":"FAIL ")+entry.getKey());
            xml.append("  <testcase classname=\"Phase06\" name=\"").append(escape(entry.getKey())).append("\" time=\"").append(String.format(Locale.ROOT,"%.6f",(System.nanoTime()-start)/1e9)).append("\">");
            if(failure!=null)xml.append("<failure message=\"").append(escape(failure)).append("\"/>");xml.append("</testcase>\n");
        }
        Path path=Path.of(args.length>0?args[0]:"core/build/reports/phase06-self-test.xml");Files.createDirectories(path.toAbsolutePath().getParent());
        Files.writeString(path,"<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuite name=\"ItemUpgraderPhase06\" tests=\""+TESTS.size()+"\" failures=\""+failures+"\" errors=\"0\">\n"+xml+"<system-out>assertions="+assertions+"; detached GUI core only, no native inventory integration</system-out>\n</testsuite>\n",StandardCharsets.UTF_8);
        System.out.println("RESULT tests="+TESTS.size()+" assertions="+assertions+" failures="+failures);
        if(failures>0)throw new AssertionError(failures+" Phase6 tests failed");
    }
    private static void test(String name,Checked t) {if(TESTS.put(name,t)!=null)throw new IllegalStateException("duplicate test");}
    private static void check(boolean value) {assertions++;if(!value)throw new AssertionError("expected true");}
    private static void eq(Object expected,Object actual) {assertions++;if(!Objects.equals(expected,actual))throw new AssertionError("expected "+expected+", got "+actual);}
    private static void rejects(Checked t)throws Exception {assertions++;try{t.run();}catch(IllegalArgumentException|IllegalStateException|UnsupportedOperationException expected){return;}throw new AssertionError("expected rejection");}
    private static String escape(String s) {return s.replace("&","&amp;").replace("<","&lt;").replace("\"","&quot;");}
    private static void contextTests() {
        test("context.initial-slot-reference-no-ownership",()->{var c=GuiContext.initial(0);eq(GuiContext.Screen.MAIN,c.screen());eq(-1,GuiContext.initial(-1).sourceSlot());eq("",c.targetId());eq(List.of(),c.boosts());});
        test("context.storage-slot-boundaries",()->{eq(35,GuiContext.initial(35).sourceSlot());rejects(()->GuiContext.initial(36));rejects(()->GuiContext.initial(-2));});
        test("context.page-bounds-avoid-overflow",()->{eq(10000,GuiContext.initial(0).page(10000).page());for(int n:List.of(-1,0,10001,Integer.MAX_VALUE))rejects(()->GuiContext.initial(0).page(n));});
        test("context.ids-preserve-path-period",()->{eq("path.t2-a_b",GuiContext.initial(0).target("path.t2-a_b").targetId());for(String id:List.of("<red>","../bad","UPPER","bad id","a".repeat(65)))rejects(()->GuiContext.initial(0).target(id));});
        test("context.boosts-canonical-copy",()->{var input=new ArrayList<>(List.of("b","a"));var c=GuiContext.initial(0).boosts(input);input.clear();eq(List.of("a","b"),c.boosts());rejects(()->c.boosts().add("c"));});
        test("context.boosts-duplicate-and-size",()->{rejects(()->GuiContext.initial(0).boosts(List.of("a","a")));rejects(()->GuiContext.initial(0).boosts(java.util.stream.IntStream.range(0,9).mapToObj(i->"b"+i).toList()));});
        test("context.filter-screen-sort-reset-page-only",()->{var c=GuiContext.initial(3).target("diamond").profile("safe").boosts(List.of("shard")).screen(GuiContext.Screen.CATALOG).page(9);eq(1,c.category("gems").page());eq(1,c.sort(CatalogQuery.Sort.ID).page());var b=c.screen(GuiContext.Screen.BOOSTS);eq(1,b.page());eq(3,b.sourceSlot());eq(c.targetId(),b.targetId());eq(c.boosts(),b.boosts());});
    }
    private static GuiSettings limits(int capacity,long idleSeconds,long requestSeconds) {var d=GuiSettings.defaults();return new GuiSettings(true,capacity,Duration.ofSeconds(idleSeconds),Duration.ofSeconds(requestSeconds),d.clickCooldown(),d.openCooldown(),d.maximumIconBytes(),d.sounds());}
    private static void settingsTests() {
        test("settings.defaults-finite-bounded",()->{var d=GuiSettings.defaults();eq(128,d.maximumSessions());eq(32768,d.maximumIconBytes());eq(Duration.ofSeconds(15),d.requestTimeout());});
        test("settings.capacity-limits",()->{for(int n:List.of(0,257))rejects(()->limits(n,60,5));eq(256,limits(256,60,5).maximumSessions());});
        test("settings.timeouts-bounded",()->{rejects(()->limits(1,29,5));rejects(()->limits(1,1801,5));rejects(()->limits(1,60,0));rejects(()->limits(1,60,31));});
        test("settings.sound-nan-volume-pitch-rejected",()->{for(float n:new float[]{Float.NaN,Float.POSITIVE_INFINITY,-1,3})rejects(()->new GuiSettings.Cue("minecraft:ui.button.click",n,1));for(float n:new float[]{Float.NaN,Float.NEGATIVE_INFINITY,0,3})rejects(()->new GuiSettings.Cue("minecraft:ui.button.click",1,n));});
        test("settings.sound-key-and-disable",()->{eq("",new GuiSettings.Cue("",0,1).key());rejects(()->new GuiSettings.Cue("UI_BUTTON_CLICK",1,1));});
        test("settings.unknown-sound-role-rejected",()->{var d=GuiSettings.defaults();rejects(()->new GuiSettings(true,1,d.idleTimeout(),d.requestTimeout(),d.clickCooldown(),d.openCooldown(),1024,Map.of("random",new GuiSettings.Cue("",0,1))));});
        test("settings.icon-boundaries",()->{var d=GuiSettings.defaults();for(int n:List.of(0,1023,65537))rejects(()->new GuiSettings(true,1,d.idleTimeout(),d.requestTimeout(),d.clickCooldown(),d.openCooldown(),n,Map.of()));});
    }
    private static MenuDefinition.Element element(MenuDefinition.Role role,MenuDefinition.Action action,String arg) {
        return new MenuDefinition.Element(role,"PAPER","{entry_id}",List.of("<gray>Preview"),"",null,false,action,arg);
    }
    private static MenuCompiler.CompiledMenu menu(GuiContext.Screen screen) {
        boolean main=screen==GuiContext.Screen.MAIN;
        var role=main?MenuDefinition.Role.SOURCE_INPUT:GuiMenus.entryRole(screen);
        var action=switch(screen) {case MAIN->MenuDefinition.Action.SOURCE_INPUT;case CATALOG->MenuDefinition.Action.SELECT_TARGET;case PROFILES->MenuDefinition.Action.SELECT_PROFILE;case BOOSTS->MenuDefinition.Action.TOGGLE_BOOST;};
        return new MenuCompiler().compile(new MenuDefinition(screen.name().toLowerCase(Locale.ROOT),"<white>UI",List.of(main?"#E#U###HX":"#EEE###HX"),Map.of(
            '#',element(MenuDefinition.Role.FILLER,MenuDefinition.Action.NONE,""), 'E',element(role,action,""),
            'U',element(MenuDefinition.Role.BUTTON,MenuDefinition.Action.UPGRADE,""),
            'H',element(MenuDefinition.Role.BUTTON,MenuDefinition.Action.BACK_MAIN,""),'X',element(MenuDefinition.Role.BUTTON,MenuDefinition.Action.CLOSE,""))),main);
    }
    private static Map<GuiContext.Screen,MenuCompiler.CompiledMenu> allMenus() {var m=new EnumMap<GuiContext.Screen,MenuCompiler.CompiledMenu>(GuiContext.Screen.class);for(var s:GuiContext.Screen.values())m.put(s,menu(s));return m;}
    private static MenuCompiler.CompiledMenu transform(MenuCompiler.CompiledMenu menu,int slot,MenuDefinition.Element replacement) {
        var slots=new HashMap<>(menu.slots());slots.put(slot,replacement);return new MenuCompiler.CompiledMenu(menu.id(),menu.title(),menu.size(),slots,menu.sourceSlot());
    }
    private static void menuTests() {
        test("menus.all-four-compile-reference-only",()->{var m=new GuiMenus(GuiSettings.defaults(),allMenus());eq(1,m.menu(GuiContext.Screen.MAIN).sourceSlot());eq(List.of(1,2,3),m.entrySlots(GuiContext.Screen.CATALOG));eq(3,m.pageSize(GuiContext.Screen.BOOSTS));m.validateReferences(fixture().rules());});
        test("menus.compiled-model-rejects-incomplete-or-forged-source",()->{
            var m=menu(GuiContext.Screen.MAIN);
            rejects(()->new MenuCompiler.CompiledMenu(m.id(),m.title(),m.size(),m.slots(),2));
            var incomplete=new HashMap<>(m.slots());incomplete.remove(0);
            rejects(()->new MenuCompiler.CompiledMenu(m.id(),m.title(),m.size(),incomplete,m.sourceSlot()));
            rejects(()->new MenuCompiler.CompiledMenu(m.id(),m.title(),0,Map.of(),-1));
        });
        test("menus.missing-screen-rejected",()->{var m=allMenus();m.remove(GuiContext.Screen.BOOSTS);rejects(()->new GuiMenus(GuiSettings.defaults(),m));});
        test("menus.no-source-in-catalog",()->{var m=allMenus();m.put(GuiContext.Screen.CATALOG,menu(GuiContext.Screen.MAIN));rejects(()->new GuiMenus(GuiSettings.defaults(),m));});
        test("menus.wrong-entry-role",()->{var m=allMenus();m.put(GuiContext.Screen.CATALOG,menu(GuiContext.Screen.PROFILES));rejects(()->new GuiMenus(GuiSettings.defaults(),m));});
        test("menus.back-required",()->{var m=allMenus();m.put(GuiContext.Screen.CATALOG,transform(m.get(GuiContext.Screen.CATALOG),7,element(MenuDefinition.Role.FILLER,MenuDefinition.Action.NONE,"")));rejects(()->new GuiMenus(GuiSettings.defaults(),m));});
        test("menus.close-required",()->{var m=allMenus();m.put(GuiContext.Screen.MAIN,transform(m.get(GuiContext.Screen.MAIN),8,element(MenuDefinition.Role.FILLER,MenuDefinition.Action.NONE,"")));rejects(()->new GuiMenus(GuiSettings.defaults(),m));});
        test("menus.configured-profile-must-exist",()->{var m=allMenus();m.put(GuiContext.Screen.MAIN,transform(m.get(GuiContext.Screen.MAIN),3,element(MenuDefinition.Role.BUTTON,MenuDefinition.Action.SELECT_PROFILE,"missing")));var definitions=new GuiMenus(GuiSettings.defaults(),m);rejects(()->definitions.validateReferences(fixture().rules()));});
        test("menus.configured-disabled-option-known-not-enabled-by-layout",()->{var m=allMenus();m.put(GuiContext.Screen.MAIN,transform(m.get(GuiContext.Screen.MAIN),3,element(MenuDefinition.Role.BUTTON,MenuDefinition.Action.TOGGLE_BOOST,"disabled")));new GuiMenus(GuiSettings.defaults(),m).validateReferences(fixture().rules());});
        test("menus.dynamic-entry-id-cannot-hardcode-or-misbind",()->{rejects(()->element(MenuDefinition.Role.CATALOG_ENTRY,MenuDefinition.Action.SELECT_TARGET,"diamond"));rejects(()->element(MenuDefinition.Role.BOOST_ENTRY,MenuDefinition.Action.SELECT_PROFILE,""));rejects(()->element(MenuDefinition.Role.SOURCE_PREVIEW,MenuDefinition.Action.SOURCE_INPUT,""));});
        test("menus.static-select-needs-id",()->{rejects(()->element(MenuDefinition.Role.BUTTON,MenuDefinition.Action.SELECT_PROFILE,""));rejects(()->element(MenuDefinition.Role.BUTTON,MenuDefinition.Action.TOGGLE_BOOST,""));});
        test("menus.literal-display-name-not-action-id",()->{var e=new MenuDefinition.Element(MenuDefinition.Role.BUTTON,"DIAMOND","<red>not-a-command",List.of(),"my:ui/button",6,true,MenuDefinition.Action.SELECT_PROFILE,"standard");eq("standard",e.argument());eq("my:ui/button",e.itemModel());});
    }
    private static GuiSessionStore.State opened(GuiSessionStore s) {return s.open(VIEWER,1,GuiContext.initial(0),limits(2,60,5)).orElseThrow();}
    private static void sessionTests() {
        test("session.one-pending-action",()->{var s=new GuiSessionStore(()->0);var a=opened(s);var b=s.reserve(a.handle()).orElseThrow();check(b.busy());eq(1L,b.handle().sequence());check(s.reserve(a.handle()).isEmpty());check(s.reserve(b.handle()).isEmpty());});
        test("session.page-replace-ignores-old-close",()->{var s=new GuiSessionStore(()->0);var a=opened(s);var busy=s.reserve(a.handle()).orElseThrow();var page=s.transition(busy.handle(),busy.context().screen(GuiContext.Screen.CATALOG)).orElseThrow();check(!a.handle().view().equals(page.handle().view()));check(!s.close(VIEWER,a.handle().session(),a.handle().view()));check(s.current(page.handle()));});
        test("session.same-screen-page-change-new-view",()->{var s=new GuiSessionStore(()->0);var b=s.reserve(opened(s).handle()).orElseThrow();var page=s.transition(b.handle(),b.context().screen(GuiContext.Screen.CATALOG).page(2)).orElseThrow();var old=page.handle();var next=s.transition(old,page.context().page(3)).orElseThrow();check(!old.view().equals(next.handle().view()));check(s.complete(old,page.context()).isEmpty());});
        test("session.sort-category-new-view",()->{for(boolean category:List.of(true,false)){var s=new GuiSessionStore(()->0);var b=s.reserve(opened(s).handle()).orElseThrow();var c=category?b.context().category("gems"):b.context().sort(CatalogQuery.Sort.ID);var next=s.transition(b.handle(),c).orElseThrow();check(!b.handle().view().equals(next.handle().view()));}});
        test("session.selection-change-keeps-container",()->{var s=new GuiSessionStore(()->0);var b=s.reserve(opened(s).handle()).orElseThrow();var next=s.transition(b.handle(),b.context().target("diamond")).orElseThrow();eq(b.handle().view(),next.handle().view());check(s.complete(b.handle(),b.context()).isEmpty());});
        test("session.blank-only-auto-recommendation",()->{var s=new GuiSessionStore(()->0);var b=s.reserve(opened(s).handle()).orElseThrow();var done=s.complete(b.handle(),b.context().target("gold")).orElseThrow();eq("gold",done.context().targetId());check(!done.busy());});
        test("session.no-silent-profile-source-target-change",()->{for(var effective:List.of(GuiContext.initial(1),GuiContext.initial(0).profile("safe"),GuiContext.initial(0).page(2),GuiContext.initial(0).boosts(List.of("shard")))){var s=new GuiSessionStore(()->0);var b=s.reserve(opened(s).handle()).orElseThrow();check(s.complete(b.handle(),effective).isEmpty());check(s.get(VIEWER).orElseThrow().busy());}});
        test("session.explicit-target-not-overwritten",()->{var s=new GuiSessionStore(()->0);var a=s.open(VIEWER,1,GuiContext.initial(0).target("diamond"),limits(2,60,5)).orElseThrow();var b=s.reserve(a.handle()).orElseThrow();check(s.complete(b.handle(),b.context().target("gold")).isEmpty());});
        test("session.close-during-request-never-reopens",()->{var s=new GuiSessionStore(()->0);var a=opened(s);var b=s.reserve(a.handle()).orElseThrow();check(s.close(VIEWER,a.handle().session(),a.handle().view()));check(s.complete(b.handle(),b.context()).isEmpty());eq(0,s.size());});
        test("session.reopen-keeps-new-session-on-old-callback",()->{var s=new GuiSessionStore(()->0);var old=s.reserve(opened(s).handle()).orElseThrow();var next=opened(s);check(!old.handle().session().equals(next.handle().session()));check(!s.close(VIEWER,old.handle().session(),old.handle().view()));check(s.complete(old.handle(),old.context()).isEmpty());check(s.current(next.handle()));});
        test("session.viewer-binding",()->{var s=new GuiSessionStore(()->0);var a=opened(s);var h=a.handle();check(!s.current(new GuiSessionStore.Handle(new UUID(99,1),h.session(),h.view(),h.revision(),h.sequence())));check(!s.close(new UUID(99,1),h.session(),h.view()));});
        test("session.cooldown-exact-boundary",()->{var time=new AtomicLong();var s=new GuiSessionStore(time::get);var b=s.reserve(opened(s).handle()).orElseThrow();var d=s.complete(b.handle(),b.context()).orElseThrow();time.set(149_999_999);check(s.reserve(d.handle()).isEmpty());time.incrementAndGet();check(s.reserve(d.handle()).isPresent());});
        test("session.request-timeout-does-not-renew-on-transition",()->{var t=new AtomicLong();var s=new GuiSessionStore(t::get);var b=s.reserve(opened(s).handle()).orElseThrow();t.set(4_999_999_999L);var next=s.transition(b.handle(),b.context().screen(GuiContext.Screen.CATALOG)).orElseThrow();t.incrementAndGet();check(s.complete(next.handle(),next.context()).isEmpty());eq(1,s.sweep(1).size());eq(0,s.size());});
        test("session.idle-expiry-and-revision-sweep",()->{var t=new AtomicLong();var s=new GuiSessionStore(t::get);opened(s);t.set(59_999_999_999L);check(s.get(VIEWER).isPresent());t.incrementAndGet();check(s.get(VIEWER).isEmpty());eq(1,s.sweep(1).size());opened(s);eq(1,s.sweep(2).size());});
        test("session.capacity-replacing-self-allowed",()->{var s=new GuiSessionStore(()->0);var d=limits(1,60,5);var a=s.open(VIEWER,1,GuiContext.initial(0),d).orElseThrow();check(s.open(new UUID(9,9),1,GuiContext.initial(0),d).isEmpty());check(s.open(VIEWER,1,GuiContext.initial(0),d).isPresent());eq(1,s.size());check(!s.current(a.handle()));});
        test("session.disabled-does-not-open",()->{var d=GuiSettings.defaults();var s=new GuiSessionStore(()->0);check(s.open(VIEWER,1,GuiContext.initial(0),new GuiSettings(false,d.maximumSessions(),d.idleTimeout(),d.requestTimeout(),d.clickCooldown(),d.openCooldown(),d.maximumIconBytes(),d.sounds())).isEmpty());});
        test("session.stop-rejects-new-and-late",()->{var s=new GuiSessionStore(()->0);var b=s.reserve(opened(s).handle()).orElseThrow();eq(1,s.stop().size());eq(0,s.size());check(s.complete(b.handle(),b.context()).isEmpty());check(s.open(VIEWER,1,GuiContext.initial(0),GuiSettings.defaults()).isEmpty());});
        test("session.nano-wrap-timeout",()->{var t=new AtomicLong(Long.MAX_VALUE-1_000_000_000L);var s=new GuiSessionStore(t::get);var b=s.reserve(opened(s).handle()).orElseThrow();t.addAndGet(5_000_000_000L);check(!s.current(b.handle()));eq(1,s.sweep(1).size());});
        test("session.concurrent-32-clicks-single-reservation",()->{var s=new GuiSessionStore(()->0);var a=opened(s);ExecutorService pool=Executors.newFixedThreadPool(4);try{var tasks=new ArrayList<Future<Boolean>>();for(int i=0;i<32;i++)tasks.add(pool.submit(()->s.reserve(a.handle()).isPresent()));int winners=0;for(var task:tasks)if(task.get(5,TimeUnit.SECONDS))winners++;eq(1,winners);}finally{pool.shutdownNow();check(pool.awaitTermination(5,TimeUnit.SECONDS));}});
    }
    private static GuiClickPolicy.Input input(int raw,int storage,GuiClickPolicy.Click click,GuiClickPolicy.Effect effect) {return new GuiClickPolicy.Input(true,true,false,false,true,false,54,raw,storage,click,effect);}
    private static void clickTests() {
        var policy=new GuiClickPolicy();
        test("click.top-left-cancelled-and-routed",()->{var d=policy.decide(input(10,-1,GuiClickPolicy.Click.LEFT,GuiClickPolicy.Effect.PICKUP_ALL));check(d.cancel());eq(GuiClickPolicy.Route.TOP,d.route());eq(10,d.slot());});
        test("click.bottom-left-is-reference-not-movement",()->{var d=policy.decide(input(54,9,GuiClickPolicy.Click.LEFT,GuiClickPolicy.Effect.PICKUP_ALL));check(d.cancel());eq(GuiClickPolicy.Route.SOURCE_REFERENCE,d.route());eq(9,d.slot());});
        test("click.right-bottom-does-not-split-stack",()->eq(GuiClickPolicy.Route.IGNORE,policy.decide(input(54,9,GuiClickPolicy.Click.RIGHT,GuiClickPolicy.Effect.PICKUP_HALF)).route()));
        test("click.block-all-shift-number-double-swap-drop-creative",()->{for(var c:GuiClickPolicy.Click.values())if(c!=GuiClickPolicy.Click.LEFT&&c!=GuiClickPolicy.Click.RIGHT)for(int raw:List.of(10,54,89)){var d=policy.decide(input(raw,9,c,GuiClickPolicy.Effect.NOTHING));check(d.cancel());eq(GuiClickPolicy.Route.IGNORE,d.route());}});
        test("click.mismatched-action-does-not-trust-click-alone",()->{for(var effect:GuiClickPolicy.Effect.values()){var left=policy.decide(input(10,-1,GuiClickPolicy.Click.LEFT,effect));eq(effect==GuiClickPolicy.Effect.PICKUP_ALL||effect==GuiClickPolicy.Effect.NOTHING?GuiClickPolicy.Route.TOP:GuiClickPolicy.Route.IGNORE,left.route());var right=policy.decide(input(10,-1,GuiClickPolicy.Click.RIGHT,effect));eq(effect==GuiClickPolicy.Effect.PICKUP_HALF||effect==GuiClickPolicy.Effect.NOTHING?GuiClickPolicy.Route.TOP:GuiClickPolicy.Route.IGNORE,right.route());}});
        test("click.all-guard-combinations",()->{for(int flags=0;flags<32;flags++){boolean current=(flags&1)==0,busy=(flags&2)!=0,cancelled=(flags&4)!=0,empty=(flags&8)==0,creative=(flags&16)!=0;var d=policy.decide(new GuiClickPolicy.Input(true,current,busy,cancelled,empty,creative,54,10,-1,GuiClickPolicy.Click.LEFT,GuiClickPolicy.Effect.NOTHING));check(d.cancel());eq(flags==0?GuiClickPolicy.Route.TOP:GuiClickPolicy.Route.IGNORE,d.route());}});
        test("click.unowned-inventory-not-hijacked",()->{var d=policy.decide(new GuiClickPolicy.Input(false,true,false,false,true,false,54,10,-1,GuiClickPolicy.Click.LEFT,GuiClickPolicy.Effect.PICKUP_ALL));check(!d.cancel());eq(GuiClickPolicy.Route.IGNORE,d.route());});
        test("click.outside-and-armor-offhand-slots-blocked",()->{for(int raw:List.of(-999,-1,90,100,Integer.MAX_VALUE))eq(GuiClickPolicy.Route.IGNORE,policy.decide(input(raw,0,GuiClickPolicy.Click.LEFT,GuiClickPolicy.Effect.NOTHING)).route());for(int slot:List.of(-1,36,40,100))eq(GuiClickPolicy.Route.IGNORE,policy.decide(input(54,slot,GuiClickPolicy.Click.LEFT,GuiClickPolicy.Effect.NOTHING)).route());});
        test("click.all-chest-sizes-and-raw-boundaries",()->{for(int size=9;size<=54;size+=9)for(int raw=-1;raw<=size+36;raw++){var d=policy.decide(new GuiClickPolicy.Input(true,true,false,false,true,false,size,raw,raw-size,GuiClickPolicy.Click.LEFT,GuiClickPolicy.Effect.NOTHING));check(d.cancel());eq(raw<0||raw>=size+36?GuiClickPolicy.Route.IGNORE:raw<size?GuiClickPolicy.Route.TOP:GuiClickPolicy.Route.SOURCE_REFERENCE,d.route());}});
        test("click.invalid-top-size-rejected",()->{for(int size:List.of(0,8,10,55,63))eq(GuiClickPolicy.Route.IGNORE,policy.decide(new GuiClickPolicy.Input(true,true,false,false,true,false,size,1,0,GuiClickPolicy.Click.LEFT,GuiClickPolicy.Effect.NOTHING)).route());});
        test("drag.owned-all-slots-cancelled",()->{check(policy.cancelDrag(true));check(!policy.cancelDrag(false));});
    }
    private static Map<Integer,String> frame(String fill) {var map=new HashMap<Integer,String>();for(int i=0;i<9;i++)map.put(i,fill);return map;}
    private static void diffTests() {
        test("diff.initial-full-then-unchanged-empty",()->{var full=frame("filler");eq(9,SlotDiff.changed(Map.of(),full,9).size());eq(Map.of(),SlotDiff.changed(full,full,9));});
        test("diff.changed-only-not-entire-inventory",()->{var before=frame("filler");var after=frame("filler");after.put(3,"chance=9%");eq(Map.of(3,"chance=9%"),SlotDiff.changed(before,after,9));});
        test("diff.last-page-clears-old-target-and-binding-projection",()->{var before=frame("filler");before.put(1,"diamond");before.put(2,"emerald");var after=frame("filler");after.put(1,"gold");eq(Map.of(1,"gold",2,"filler"),SlotDiff.changed(before,after,9));});
        test("diff.requires-complete-frame-and-valid-size",()->{var broken=frame("filler");broken.remove(3);rejects(()->SlotDiff.changed(Map.of(),broken,9));rejects(()->SlotDiff.changed(Map.of(),frame("filler"),8));rejects(()->SlotDiff.changed(Map.of(10,"outside"),frame("filler"),9));});
        test("diff.defensive-return-unchanged-after-mutation",()->{var source=frame("filler");var result=SlotDiff.changed(Map.of(),source,9);source.put(1,"changed");eq("filler",result.get(1));rejects(()->result.put(0,"bad"));});
    }
    private static GuiPreviewService.Preview render(GuiDemo.Fixture f,GuiContext c,int size) {return GuiDemo.preview(f,c,size);}
    private static List<String> ids(GuiPreviewService.Preview p) {return p.entries().stream().map(GuiPreviewService.Entry::id).toList();}
    private static GuiDemo.Fixture access(GuiDemo.Fixture f,Set<String> perms,Set<String> conditions) {return new GuiDemo.Fixture(f.index(),f.rules(),f.source(),new CatalogAccess(VIEWER,perms,conditions),f.resources());}
    private static void previewTests() {
        test("preview.blank-recommends-gold-with-real-chance-service",()->{var p=render(fixture(),GuiContext.initial(0),2);eq("gold",p.context().targetId());eq("preview",p.status());eq(0,p.context().sourceSlot());eq("45",p.quote().orElseThrow().quote().orElseThrow().chance().probability().percent().stripTrailingZeros().toPlainString());});
        test("preview.explicit-diamond-nine-percent",()->{var p=render(fixture(),GuiContext.initial(0).target("diamond"),2);eq("diamond",p.context().targetId());eq("9",p.quote().orElseThrow().quote().orElseThrow().chance().probability().percent().stripTrailingZeros().toPlainString());});
        test("preview.invalid-target-does-not-fallback",()->{var p=render(fixture(),GuiContext.initial(0).target("missing"),2);eq("missing",p.context().targetId());eq("quote-denied",p.status());check(p.target().isEmpty());check(p.quote().orElseThrow().quote().isEmpty());});
        test("preview.no-source-no-quote-or-entries",()->{var f=fixture();var p=new GuiPreviewService().calculate(SESSION,GuiContext.initial(-1).screen(GuiContext.Screen.CATALOG),Optional.empty(),f.index(),f.access(),f.rules(),f.resources(),2,NOW);eq("need-source",p.status());check(p.quote().isEmpty());eq(List.of(),p.entries());});
        test("preview.cleared-reference-does-not-use-old-snapshot",()->{var p=render(fixture(),GuiContext.initial(-1),2);eq("need-source",p.status());check(p.source().isEmpty());});
        test("preview.catalog-paging-keeps-selection",()->{var c=GuiContext.initial(0).target("diamond").screen(GuiContext.Screen.CATALOG);var a=render(fixture(),c,2);var b=render(fixture(),c.page(2),2);eq(List.of("gold","emerald"),ids(a));eq(List.of("diamond"),ids(b));eq(2,a.pages());eq(3,a.total());eq("diamond",b.context().targetId());});
        test("preview.catalog-sorts-by-ID",()->{var p=render(fixture(),GuiContext.initial(0).screen(GuiContext.Screen.CATALOG).sort(CatalogQuery.Sort.ID),3);eq(List.of("diamond","emerald","gold"),ids(p));});
        test("preview.category-before-pagination",()->{var p=render(fixture(),GuiContext.initial(0).screen(GuiContext.Screen.CATALOG).category("gems"),1);eq(List.of("emerald"),ids(p));eq(2,p.total());eq(2,p.pages());});
        test("preview.page-out-of-range-never-clamps-or-overflows",()->{var p=render(fixture(),GuiContext.initial(0).screen(GuiContext.Screen.CATALOG).page(10000),1);eq("catalog-page-out-of-range",p.status());eq(List.of(),p.entries());eq(10000,p.context().page());});
        test("preview.profile-access-filter-before-page",()->{var f=fixture();var c=GuiContext.initial(0).screen(GuiContext.Screen.PROFILES);var p=render(f,c,1);eq(2,p.total());eq(List.of("safe"),ids(p));eq(List.of("standard"),ids(render(f,c.page(2),1)));eq(3,render(access(f,Set.of("gui.vip"),Set.of()),c,1).total());});
        test("preview.boosts-filter-by-profile-and-access",()->{var f=fixture();var c=GuiContext.initial(0).screen(GuiContext.Screen.BOOSTS);eq(List.of("lucky_shard"),ids(render(f,c,3)));eq(List.of("lucky_shard","vip_shard"),ids(render(access(f,Set.of("gui.vip"),Set.of()),c,3)));eq("empty-list",render(f,c.profile("safe"),3).status());});
        test("preview.disabled-boost-never-selectable",()->{var p=render(fixture(),GuiContext.initial(0).boosts(List.of("disabled")),2);eq("quote-denied",p.status());check(p.quote().orElseThrow().quote().isEmpty());eq(List.of("disabled"),p.context().boosts());});
        test("preview.missing-permission-does-not-drop-selected-boost",()->{var p=render(fixture(),GuiContext.initial(0).boosts(List.of("vip_shard")),2);eq("quote-denied",p.status());eq(List.of("vip_shard"),p.context().boosts());});
        test("preview.real-boost-fourteen-percent-fee",()->{var p=render(fixture(),GuiContext.initial(0).target("diamond").boosts(List.of("lucky_shard")),2);var q=p.quote().orElseThrow().quote().orElseThrow();eq("14",q.chance().probability().percent().stripTrailingZeros().toPlainString());eq("2",q.terms().costs().lines().getFirst().reserve().stripTrailingZeros().toPlainString());});
        test("preview.missing-resources-keeps-chance-not-authorized",()->{var f=fixture();var empty=new GuiDemo.Fixture(f.index(),f.rules(),f.source(),f.access(),ResourceSnapshot.empty(VIEWER,0));var p=render(empty,GuiContext.initial(0).target("diamond").profile("safe"),2);eq("resources-missing",p.status());check(!p.quote().orElseThrow().quote().orElseThrow().resources().available());eq("5.4",p.quote().orElseThrow().quote().orElseThrow().chance().probability().percent().stripTrailingZeros().toPlainString());});
        test("preview.source-slot-excluded-from-fee",()->{var f=fixture();var wrong=new GuiDemo.Fixture(f.index(),f.rules(),f.source(),f.access(),new ResourceSnapshot(VIEWER,Map.of(),Set.of(EMERALD),List.of(new ResourceSnapshot.ItemSupply(0,EMERALD,64,item(EMERALD,64).fingerprint())),Set.of(0)));var p=render(wrong,GuiContext.initial(0).boosts(List.of("lucky_shard")),2);eq("resources-missing",p.status());});
        test("preview.denied-priority-path-does-not-fallback",()->{var high=new UpgradePath("vip",Set.of(IRON),10,UpgradePath.Mode.LOCKED,List.of("diamond"),true,"gui.vip",Set.of());var low=new UpgradePath("open",Set.of(IRON),0,UpgradePath.Mode.OPEN,List.of("gold"),true,"",Set.of());var f=fixture(targets(),List.of(high,low),List.of());var p=render(f,GuiContext.initial(0).screen(GuiContext.Screen.CATALOG),3);eq("catalog-path-denied",p.status());eq(List.of(),p.entries());check(p.quote().isEmpty());});
        test("preview.locked-path-targets-only",()->{var path=new UpgradePath("locked",Set.of(IRON),1,UpgradePath.Mode.LOCKED,List.of("diamond"),true,"",Set.of());var p=render(fixture(targets(),List.of(path),List.of()),GuiContext.initial(0).screen(GuiContext.Screen.CATALOG),3);eq(List.of("diamond"),ids(p));eq("diamond",p.context().targetId());});
        test("preview.permission-target-filter-before-pagination",()->{var t=new ArrayList<>(targets());t.set(0,target("gold",GOLD,"metals","gui.vip",Set.of()));var f=fixture(t,List.of(),List.of());var p=render(f,GuiContext.initial(0).screen(GuiContext.Screen.CATALOG),1);eq(2,p.total());eq(List.of("emerald"),ids(p));eq(3,render(access(f,Set.of("gui.vip"),Set.of()),GuiContext.initial(0).screen(GuiContext.Screen.CATALOG),1).total());});
        test("preview.typed-condition-missing-denies-target",()->{
            var condition=new ConditionDefinition("level","%player_level%",ConditionDefinition.Type.NUMBER,ConditionDefinition.Operator.GE,"50");
            var t=new ArrayList<>(targets());t.set(0,target("gold",GOLD,"metals","",Set.of("level")));
            var f=fixture(t,List.of(),List.of(condition));var c=GuiContext.initial(0).screen(GuiContext.Screen.CATALOG);
            eq(2,render(f,c,3).total());eq(3,render(access(f,Set.of(),Set.of("level")),c,3).total());
        });
        test("preview.explicit-profile-permission-denied",()->{var p=render(fixture(),GuiContext.initial(0).profile("vip"),2);eq("quote-denied",p.status());eq("vip",p.context().profileId());});
        test("preview.empty-filter-keeps-explicit-selected-target",()->{var p=render(fixture(),GuiContext.initial(0).target("diamond").screen(GuiContext.Screen.CATALOG).category("missing"),2);eq(List.of(),p.entries());eq("diamond",p.context().targetId());check(p.quote().orElseThrow().quote().isPresent());});
        test("preview.reject-resource-owner-mismatch",()->{var f=fixture();var wrong=new GuiDemo.Fixture(f.index(),f.rules(),f.source(),f.access(),ResourceSnapshot.empty(new UUID(8,8),0));rejects(()->render(wrong,GuiContext.initial(0),2));});
        test("preview.reject-other-runtime-instance",()->{var a=fixture();var b=fixture();rejects(()->new GuiPreviewService().calculate(SESSION,GuiContext.initial(0),Optional.of(a.source()),a.index(),a.access(),b.rules(),a.resources(),2,NOW));});
        test("preview.page-size-limit",()->{for(int n:List.of(0,46,Integer.MAX_VALUE))rejects(()->render(fixture(),GuiContext.initial(0),n));});
        test("preview.snapshot-remains-identical-through-navigation",()->{var f=fixture();String before=f.source().fingerprint();for(var screen:GuiContext.Screen.values())render(f,GuiContext.initial(0).screen(screen),2);eq(before,f.source().fingerprint());eq(1,f.source().facts().amount());});
        test("preview.late-result-from-closed-page-cannot-apply",()->{var f=fixture();var s=new GuiSessionStore(()->0);var b=s.reserve(opened(s).handle()).orElseThrow();var result=render(f,b.context(),2);var changed=s.transition(b.handle(),b.context().screen(GuiContext.Screen.CATALOG)).orElseThrow();check(s.complete(b.handle(),result.context()).isEmpty());check(s.current(changed.handle()));var newResult=render(f,changed.context(),2);check(s.complete(changed.handle(),newResult.context()).isPresent());});
    }
}
