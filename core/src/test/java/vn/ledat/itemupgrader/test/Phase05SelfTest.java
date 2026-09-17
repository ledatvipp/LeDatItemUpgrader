package vn.ledat.itemupgrader.test;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;
import vn.ledat.itemupgrader.demo.OutputDemo;
import vn.ledat.itemupgrader.demo.support.*;
import vn.ledat.itemupgrader.transfer.*;
import vn.ledat.itemupgrader.failure.*;
import vn.ledat.itemupgrader.output.*;
import vn.ledat.itemupgrader.output.storage.*;
import vn.ledat.itemupgrader.item.*;
import vn.ledat.itemupgrader.quote.*;
import vn.ledat.itemupgrader.profile.*;
import vn.ledat.itemupgrader.value.*;
import vn.ledat.itemupgrader.transaction.*;
import vn.ledat.itemupgrader.transaction.model.*;
import vn.ledat.itemupgrader.transaction.storage.*;
import static vn.ledat.itemupgrader.demo.OutputDemo.CLOCK;
import static vn.ledat.itemupgrader.demo.OutputDemo.NOW;

/** Actual dependency-free Java tests. Synthetic items/effects, not Bukkit or provider integration. */
public final class Phase05SelfTest {
    @FunctionalInterface interface Checked { void run() throws Exception; }
    private static final Map<String,Checked> TESTS=new LinkedHashMap<>();
    private static int assertions;
    private static final List<Throwable> DIAGNOSTICS=new CopyOnWriteArrayList<>();
    private Phase05SelfTest(){}
    public static void main(String[] args)throws Exception{
        policyTests();transferTests();failureTests();codecTests();materializerTests();storeTests();integrationTests();propertyTests();JdbcOutputContractTests.register();
        int failures=0;StringBuilder xml=new StringBuilder();
        for(var entry:TESTS.entrySet()){
            long started=System.nanoTime();String failure=null;
            try{entry.getValue().run();}catch(Exception|AssertionError error){failure=error.toString();failures++;error.printStackTrace(System.err);}
            System.out.println((failure==null?"PASS ":"FAIL ")+entry.getKey());
            xml.append("  <testcase classname=\"Phase05\" name=\"").append(escape(entry.getKey())).append("\" time=\"").append(String.format(Locale.ROOT,"%.6f",(System.nanoTime()-started)/1e9)).append("\">");
            if(failure!=null)xml.append("<failure message=\"").append(escape(failure)).append("\"/>");xml.append("</testcase>\n");
        }
        Path path=Path.of(args.length>0?args[0]:"core/build/reports/phase05-self-test.xml");Files.createDirectories(path.toAbsolutePath().getParent());
        Files.writeString(path,"<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuite name=\"ItemUpgraderPhase05\" tests=\""+TESTS.size()+"\" failures=\""+failures+"\" errors=\"0\">\n"+xml+"<system-out>assertions="+assertions+"; synthetic item/effect core tests only</system-out>\n</testsuite>\n",StandardCharsets.UTF_8);
        System.out.println("RESULT tests="+TESTS.size()+" assertions="+assertions+" failures="+failures);
        if(failures>0)throw new AssertionError(failures+" Phase5 tests failed");
    }
    static void test(String name,Checked work){if(TESTS.put(name,work)!=null)throw new IllegalStateException("duplicate test");}
    static void check(boolean ok){assertions++;if(!ok)throw new AssertionError("condition failed");}
    static void eq(Object a,Object b){assertions++;if(!Objects.equals(a,b))throw new AssertionError("expected "+a+", got "+b);}
    static void rejects(Checked work)throws Exception{assertions++;try{work.run();}catch(IllegalArgumentException|IllegalStateException expected){return;}throw new AssertionError("expected rejection");}
    static <T>T get(CompletionStage<T> stage)throws Exception{return stage.toCompletableFuture().get(5,TimeUnit.SECONDS);}
    static String escape(String text){return text.replace("&","&amp;").replace("<","&lt;").replace("\"","&quot;");}
    static TransferPolicy selective(){return new TransferPolicy("selected",true,true,TransferPolicy.Durability.TARGET_DEFAULT,Map.of("minecraft:unbreaking",3),Map.of("myplugin:soulbound_owner",PdcValue.Type.STRING));}
    static ItemDocument source(){return SyntheticItems.vanilla("minecraft:iron_sword",1,40,250);}
    static ItemDocument target(){return SyntheticItems.vanilla("minecraft:diamond_sword",1,0,1561);}
    static ItemDocument richSource(){var f=source().snapshot().facts();return SyntheticItems.document(new ItemFacts(f.key(),1,40,250,Map.of("minecraft:unbreaking",3),"",Set.of()),Optional.of("<red>literal</red>"),9,false,Map.of("myplugin:soulbound_owner",PdcValue.text("owner-123"),"myplugin:weapon_uuid",PdcValue.text("never-copy")),Set.of(),source().capabilities());}
    static AttemptPlan plan(FailurePolicy policy){return OutputDemo.plan(policy,TransferPolicy.clean());}
    static AttemptPlan change(AttemptPlan p,UUID quote,ItemSnapshot source,ItemSnapshot target,OutputSpec spec){
        var t=p.terms();return new AttemptPlan(p.playerId(),p.sessionId(),quote,p.configRevision(),p.catalogGeneration(),p.sourceSlot(),source,p.targetId(),target,p.sourceTotal(),p.targetTotal(),
                new UpgradeQuote.Terms(t.profileId(),t.boosts(),t.permissionBonuses(),t.probability(),spec.failure().mode(),t.costs(),Optional.of(spec)),p.feeItems(),p.expiresAt());
    }
    static OutputMaterializer materializer(SyntheticItems items){return new OutputMaterializer(items,OutputDemo.values(),7,Runnable::run,CLOCK);}
    static PreparedOutput prepared(FailurePolicy policy)throws Exception{return get(materializer(OutputDemo.items()).materialize(plan(policy)));}
    static OutputPreparationService service(OutputStore store,Function<AttemptPlan,CompletionStage<PreparedOutput>> factory){return new OutputPreparationService(store,factory,Runnable::run,CLOCK,(operation,error)->DIAGNOSTICS.add(error));}
    static OutputStore.Row ready(OutputStore.Row pending,PreparedOutput p){return new OutputStore.Row(pending.attemptId(),pending.playerId(),pending.planDigest(),OutputStore.State.READY,Optional.of(p),"",pending.createdAt());}
    private static void policyTests(){
        test("policy.clean-is-no-source-transfer",()->check(TransferPolicy.clean().empty()));
        for(String namespace:List.of("minecraft","mmoitems","mythiclib","itemsadder","oraxen","nexo","ledatitemupgrader"))
            test("policy.reject-protected-namespace-"+namespace,()->rejects(()->new TransferPolicy("bad",false,false,TransferPolicy.Durability.TARGET_DEFAULT,Map.of(),Map.of(namespace+":identity",PdcValue.Type.STRING))));
        test("policy.reject-wildcard-and-invalid-pdc-type-size",()->{
            rejects(()->new TransferPolicy("bad",false,false,TransferPolicy.Durability.TARGET_DEFAULT,Map.of(),Map.of("plugin:*",PdcValue.Type.STRING)));
            rejects(()->new PdcValue(PdcValue.Type.INTEGER,new byte[5]));rejects(()->new PdcValue(PdcValue.Type.LONG,new byte[4]));
            rejects(()->new PdcValue(PdcValue.Type.BYTE_ARRAY,new byte[4097]));rejects(()->new PdcValue(PdcValue.Type.STRING,new byte[]{(byte)0xff}));
        });
        test("policy.pdc-immutable-and-content-equality",()->{
            byte[] raw={1,2,3};var value=new PdcValue(PdcValue.Type.BYTE_ARRAY,raw);raw[0]=9;value.bytes()[0]=8;
            eq(new PdcValue(PdcValue.Type.BYTE_ARRAY,new byte[]{1,2,3}),value);eq(PdcValue.integer(12),PdcValue.integer(12));eq(PdcValue.longValue(123),PdcValue.longValue(123));
        });
        test("policy.names-plain-bounded-no-control-characters",()->{
            eq("<red>literal</red>",MetadataView.name("<red>literal</red>"));rejects(()->MetadataView.name("abc\nxyz"));rejects(()->MetadataView.name("x".repeat(257)));
        });
        test("policy.protected-adapter-keys-cannot-be-writable",()->{
            var c=source().capabilities();rejects(()->new MutationCapabilities(c.provider(),true,true,true,1,false,c.applicableEnchants(),c.conflicts(),Map.of("myplugin:weapon_uuid",PdcValue.Type.STRING),Set.of("myplugin:weapon_uuid")));
        });
        test("policy.damage-range-and-downgrade-quantity",()->{rejects(()->new FailurePolicy.Damage(0,FailurePolicy.BreakBehavior.DESTROY));rejects(()->new FailurePolicy.Damage(10001,FailurePolicy.BreakBehavior.DESTROY));rejects(()->new FailurePolicy.Downgrade(ItemKey.of("minecraft:stone_sword"),0,TransferPolicy.clean()));});
        test("policy.new-modes-require-full-pinned-spec",()->{var p=plan(new FailurePolicy.Destroy());var t=p.terms();rejects(()->new UpgradeQuote.Terms(t.profileId(),t.boosts(),t.permissionBonuses(),t.probability(),RiskProfile.FailureMode.DAMAGE,t.costs()));});
        test("policy.terms-mode-cannot-disagree-with-spec",()->{var p=plan(new FailurePolicy.Destroy());var t=p.terms();rejects(()->new UpgradeQuote.Terms(t.profileId(),t.boosts(),t.permissionBonuses(),t.probability(),RiskProfile.FailureMode.KEEP,t.costs(),t.output()));});
    }
    private static void transferTests(){
        test("transfer.clean-retains-target-enchant-and-meta",()->eq(MetadataPatch.none(),new TransferPlanner().plan(richSource(),target(),TransferPolicy.clean())));
        test("transfer.allow-list-only-and-no-identity-pdc",()->{
            var patch=new TransferPlanner().plan(richSource(),target(),selective());eq("<red>literal</red>",patch.customName().orElseThrow());eq(9,patch.repairCost().getAsInt());
            eq(Map.of("minecraft:unbreaking",3),patch.enchantments().orElseThrow());eq(Set.of("myplugin:soulbound_owner"),patch.pdc().keySet());
        });
        test("transfer.apply-preserves-source-and-verifies-native-result",()->{
            var src=richSource();var tar=target();var before=src.snapshot().fingerprint();var patch=new TransferPlanner().plan(src,tar,selective());var items=new SyntheticItems();
            var result=get(items.apply(new ItemMutationPort.Context(new UUID(0,1),new UUID(0,2),SyntheticItems.hash("p")),tar,patch));TransferPlanner.verify(tar,result,patch);eq(before,src.snapshot().fingerprint());
            eq(src.metadata().pdc().get("myplugin:soulbound_owner"),result.metadata().pdc().get("myplugin:soulbound_owner"));
        });
        test("transfer.reject-unattested-pdc-even-admin-allowlisted",()->{
            var policy=new TransferPolicy("bad",false,false,TransferPolicy.Durability.TARGET_DEFAULT,Map.of(),Map.of("myplugin:weapon_uuid",PdcValue.Type.STRING));
            rejects(()->new TransferPlanner().plan(richSource(),target(),policy));
        });
        test("transfer.reject-pdc-type-mismatch",()->{
            var src=richSource();var data=new TreeMap<>(src.metadata().pdc());data.put("myplugin:soulbound_owner",PdcValue.integer(7));
            var wrong=SyntheticItems.document(src.snapshot().facts(),src.metadata().customName(),9,false,data,Set.of(),src.capabilities());rejects(()->new TransferPlanner().plan(wrong,target(),selective()));
        });
        test("transfer.cap-is-not-silent-enchant-downgrade",()->{
            var policy=new TransferPolicy("strict",false,false,TransferPolicy.Durability.TARGET_DEFAULT,Map.of("minecraft:unbreaking",2),Map.of());rejects(()->new TransferPlanner().plan(richSource(),target(),policy));
        });
        test("transfer.enchant-merge-max-does-not-add-levels",()->{
            var f=target().snapshot().facts();var tar=SyntheticItems.document(new ItemFacts(f.key(),1,0,1561,Map.of("minecraft:unbreaking",2),"",Set.of()),Optional.empty(),0,false,Map.of(),Set.of(),target().capabilities());
            eq(3,new TransferPlanner().plan(richSource(),tar,selective()).enchantments().orElseThrow().get("minecraft:unbreaking"));
        });
        test("transfer.reject-conflicting-enchantments",()->{
            var f=source().snapshot().facts();var src=SyntheticItems.document(new ItemFacts(f.key(),1,40,250,Map.of("minecraft:sharpness",5,"minecraft:smite",5),"",Set.of()),Optional.empty(),0,false,Map.of(),Set.of(),source().capabilities());
            rejects(()->new TransferPlanner().plan(src,target(),new TransferPolicy("conflict",false,false,TransferPolicy.Durability.TARGET_DEFAULT,Map.of("minecraft:sharpness",5,"minecraft:smite",5),Map.of())));
        });
        test("transfer.durability-ratio-rounds-damage-up",()->{
            var patch=new TransferPlanner().plan(source(),target(),new TransferPolicy("ratio",false,false,TransferPolicy.Durability.DAMAGE_RATIO_CEIL,Map.of(),Map.of()));eq(250,patch.damage().getAsInt());
        });
        test("transfer.noop-cannot-mutate-payload",()->rejects(()->TransferPlanner.verify(target(),source(),MetadataPatch.none())));
        test("transfer.native-extra-pdc-or-identity-write-rejected",()->{
            var tar=target();var m=tar.metadata();var bad=SyntheticItems.document(tar.snapshot().facts(),m.customName(),0,false,Map.of("myplugin:soulbound_owner",PdcValue.text("forged")),Set.of(),tar.capabilities());
            rejects(()->TransferPlanner.verify(tar,bad,MetadataPatch.none()));
            var unique=SyntheticItems.document(tar.snapshot().facts(),m.customName(),0,false,Map.of(),Set.of(SyntheticItems.hash("identity")),tar.capabilities());rejects(()->TransferPlanner.verify(tar,unique,MetadataPatch.none()));
        });
        test("transfer.broken-target-is-rejected-not-repaired",()->{
            var broken=SyntheticItems.vanilla("minecraft:iron_sword",1,249,250);var tiny=SyntheticItems.vanilla("minecraft:diamond_sword",1,0,1);
            rejects(()->new TransferPlanner().plan(broken,tiny,new TransferPolicy("ratio",false,false,TransferPolicy.Durability.DAMAGE_RATIO_CEIL,Map.of(),Map.of())));
        });
    }
    private static void failureTests(){
        test("failure.damage-20-percent-of-maximum",()->{var r=new FailurePlanner().damage(source(),new FailurePolicy.Damage(2000,FailurePolicy.BreakBehavior.DESTROY));check(!r.destroyed());eq(90,r.damage().getAsInt());});
        test("failure.damage-round-up-minimum-one",()->eq(1,new FailurePlanner().damage(SyntheticItems.vanilla("minecraft:stone_sword",1,0,3),new FailurePolicy.Damage(1,FailurePolicy.BreakBehavior.DESTROY)).damage().getAsInt()));
        test("failure.damage-destroy-at-break",()->check(new FailurePlanner().damage(source(),new FailurePolicy.Damage(10000,FailurePolicy.BreakBehavior.DESTROY)).destroyed()));
        test("failure.damage-clamp-to-one",()->eq(249,new FailurePlanner().damage(source(),new FailurePolicy.Damage(10000,FailurePolicy.BreakBehavior.CLAMP_ONE)).damage().getAsInt()));
        test("failure.clamp-no-loss-loop-rejected",()->rejects(()->new FailurePlanner().damage(SyntheticItems.vanilla("minecraft:iron_sword",1,249,250),new FailurePolicy.Damage(2000,FailurePolicy.BreakBehavior.CLAMP_ONE))));
        test("failure.non-damageable-or-unbreakable-rejected",()->{
            rejects(()->new FailurePlanner().damage(SyntheticItems.vanilla("minecraft:diamond",1,0,0),new FailurePolicy.Damage(2000,FailurePolicy.BreakBehavior.DESTROY)));
            var s=source();var unbreakable=SyntheticItems.document(s.snapshot().facts(),Optional.empty(),0,true,Map.of(),Set.of(),s.capabilities());rejects(()->new FailurePlanner().damage(unbreakable,new FailurePolicy.Damage(2000,FailurePolicy.BreakBehavior.DESTROY)));
        });
        test("failure.keep-exact-bytes-and-quantity",()->{var output=prepared(new FailurePolicy.Keep());eq(plan(new FailurePolicy.Keep()).source().fingerprint(),output.failure().orElseThrow().fingerprint());eq(1,output.failure().orElseThrow().facts().amount());});
        test("failure.destroy-no-hidden-source-return",()->check(prepared(new FailurePolicy.Destroy()).failure().isEmpty()));
        test("failure.downgrade-explicit-lower-value-replacement",()->{var out=prepared(new FailurePolicy.Downgrade(ItemKey.of("minecraft:stone_sword"),1,TransferPolicy.clean()));eq(ItemKey.of("minecraft:stone_sword"),out.failure().orElseThrow().facts().key());});
    }
    private static void codecTests(){
        for(FailurePolicy policy:List.of(new FailurePolicy.Destroy(),new FailurePolicy.Keep(),new FailurePolicy.Damage(2000,FailurePolicy.BreakBehavior.DESTROY),new FailurePolicy.Downgrade(ItemKey.of("minecraft:stone_sword"),1,selective())))
            test("codec.v2-plan-roundtrip-"+policy.mode(),()->{
                var p=OutputDemo.plan(policy,selective());byte[] bytes=JournalCodec.encodePlan(p);eq(2,java.nio.ByteBuffer.wrap(bytes).getInt(4));
                var decoded=JournalCodec.decodePlan(bytes);eq(p.terms(),decoded.terms());check(Arrays.equals(bytes,JournalCodec.encodePlan(decoded)));
                var record=AttemptRecord.initial(p,NOW);eq(JournalCodec.planDigest(p),JournalCodec.planDigest(JournalCodec.decodeRecord(JournalCodec.encodeRecord(record)).plan()));
                eq(JournalCodec.planDigest(p),JournalCodec.planDigest(JournalCodec.decodeState(p,JournalCodec.encodeState(record)).plan()));
            });
        test("codec.legacy-v1-plan-and-state-digests-unchanged",()->{
            String fixture=Files.readString(Path.of("verification/phase04-fixtures/journal-contract-v1.json"));
            var matcher=java.util.regex.Pattern.compile("\\\"planPayload\\\":\\\"([^\\\"]+)\\\"").matcher(fixture);int count=0;
            while(matcher.find()){byte[] bytes=Base64.getDecoder().decode(matcher.group(1));var p=JournalCodec.decodePlan(bytes);check(p.terms().output().isEmpty());check(Arrays.equals(bytes,JournalCodec.encodePlan(p)));count++;}check(count>0);
        });
        test("codec.policy-content-not-just-id-changes-digest",()->{
            var p=plan(new FailurePolicy.Destroy());var spec=new OutputSpec(new TransferPolicy("clean",true,false,TransferPolicy.Durability.TARGET_DEFAULT,Map.of(),Map.of()),new FailurePolicy.Destroy());
            var other=change(p,p.quoteId(),p.source(),p.target(),spec);eq(p.attemptId(),other.attemptId());check(!JournalCodec.planDigest(p).equals(JournalCodec.planDigest(other)));
        });
        test("codec.output-roundtrip-immutable-bound-to-plan",()->{
            var output=prepared(new FailurePolicy.Damage(2000,FailurePolicy.BreakBehavior.DESTROY));byte[] bytes=OutputCodec.encode(output);var decoded=OutputCodec.decode(bytes);eq(OutputCodec.digest(output),OutputCodec.digest(decoded));Arrays.fill(bytes,(byte)0);eq(90,decoded.failure().orElseThrow().facts().damage());
        });
        test("codec.output-truncated-trailing-oversize-rejected",()->{
            var bytes=OutputCodec.encode(prepared(new FailurePolicy.Destroy()));rejects(()->OutputCodec.decode(Arrays.copyOf(bytes,bytes.length-1)));rejects(()->OutputCodec.decode(Arrays.copyOf(bytes,bytes.length+1)));rejects(()->OutputCodec.decode(new byte[OutputCodec.MAX_BYTES+1]));
        });
        test("codec.output-cannot-bind-to-different-attempt",()->{var output=prepared(new FailurePolicy.Destroy());var p=plan(new FailurePolicy.Destroy());rejects(()->output.checkPlan(change(p,new UUID(0,999),p.source(),p.target(),p.terms().output().orElseThrow())));});
        test("codec.item-envelope-roundtrip-binds-all-facts",()->{var s=source().snapshot();var out=JournalCodec.decodeItem(JournalCodec.encodeItem(s));eq(s.facts(),out.facts());eq(s.fingerprint(),out.fingerprint());});
        test("codec.version2-policy-enchant-and-pdc-order-canonical",()->{
            Map<String,Integer> a=new LinkedHashMap<>();a.put("minecraft:sharpness",5);a.put("minecraft:unbreaking",3);var b=new LinkedHashMap<String,Integer>();b.put("minecraft:unbreaking",3);b.put("minecraft:sharpness",5);
            var p=OutputDemo.plan(new FailurePolicy.Destroy(),new TransferPolicy("order",false,false,TransferPolicy.Durability.TARGET_DEFAULT,a,Map.of()));var q=OutputDemo.plan(new FailurePolicy.Destroy(),new TransferPolicy("order",false,false,TransferPolicy.Durability.TARGET_DEFAULT,b,Map.of()));eq(JournalCodec.planDigest(p),JournalCodec.planDigest(q));
        });
    }
    private static void materializerTests(){
        test("materializer.fresh-custom-identity-not-catalog-identity",()->{
            var p=plan(new FailurePolicy.Destroy());var facts=new ItemFacts(ItemKey.of("mmoitems:SWORD:DRAGON"),1,0,1561,Map.of(),"",Set.of());
            var template=SyntheticItems.document(facts,Optional.empty(),0,false,Map.of(),Set.of(SyntheticItems.hash("catalog-token")),SyntheticItems.caps("mmoitems",1,true));
            var items=OutputDemo.items();items.template(template);var freshPlan=change(p,p.quoteId(),p.source(),template.snapshot(),p.terms().output().orElseThrow());
            var values=ValueDefinitions.manualOnly(Map.of(facts.key(),new BigDecimal("2000")));
            var out=get(new OutputMaterializer(items,values,7,Runnable::run,CLOCK).materialize(freshPlan));
            eq(1,out.successIdentities().size());check(Collections.disjoint(out.successIdentities(),template.metadata().uniqueTokens()));eq(facts.key(),out.success().facts().key());
        });
        test("materializer.reject-provider-cloning-catalog-uuid",()->{
            var p=plan(new FailurePolicy.Destroy());var facts=new ItemFacts(ItemKey.of("mmoitems:SWORD:DRAGON"),1,0,1561,Map.of(),"",Set.of());
            var template=SyntheticItems.document(facts,Optional.empty(),0,false,Map.of(),Set.of(SyntheticItems.hash("catalog-token")),SyntheticItems.caps("mmoitems",1,true));
            var items=new SyntheticItems(){@Override public CompletionStage<ItemDocument> createFresh(ItemMutationPort.Context context,ItemKey key,int amount){return CompletableFuture.completedFuture(template);}};
            items.remember(source());items.template(template);var freshPlan=change(p,p.quoteId(),p.source(),template.snapshot(),p.terms().output().orElseThrow());
            var values=ValueDefinitions.manualOnly(Map.of(facts.key(),new BigDecimal("2000")));
            eq("REUSED_IDENTITY",get(service(new SimulationOutputStore(),new OutputMaterializer(items,values,7,Runnable::run,CLOCK)::materialize).prepare(freshPlan)).reason());
        });
        test("materializer.no-pure-creation-capability-no-factory",()->{
            var c=target().capabilities();var unsafeCaps=new MutationCapabilities(c.provider(),false,c.vanillaTransfer(),c.durabilityMutation(),c.maxStackSize(),c.uniqueIdentityRequired(),c.applicableEnchants(),c.conflicts(),c.writablePdc(),c.protectedPdc());
            var doc=SyntheticItems.document(target().snapshot().facts(),Optional.empty(),0,false,Map.of(),Set.of(),unsafeCaps);var items=OutputDemo.items();items.template(doc);
            eq("CAPABILITY_UNAVAILABLE",get(service(new SimulationOutputStore(),materializer(items)::materialize).prepare(plan(new FailurePolicy.Destroy()))).reason());eq(0,items.creations.get());
        });

        test("materializer.clean-fresh-output-one-create-not-catalog-delivery",()->{var items=OutputDemo.items();var p=plan(new FailurePolicy.Destroy());var out=get(materializer(items).materialize(p));eq(1,items.creations.get());out.checkPlan(p);eq(p.target().facts().key(),out.success().facts().key());});
        test("materializer.selective-name-enchant-pdc-with-value-preserving-policy",()->{
            var items=OutputDemo.items();var src=richSource();items.remember(src);var p=plan(new FailurePolicy.Destroy());p=change(p,p.quoteId(),src.snapshot(),p.target(),new OutputSpec(selective(),new FailurePolicy.Destroy()));
            var out=get(materializer(items).materialize(p));var document=get(items.inspect(new ItemMutationPort.Context(p.playerId(),p.attemptId(),JournalCodec.planDigest(p)),out.success()));eq(src.metadata().customName(),document.metadata().customName());eq(3,out.success().facts().enchantments().get("minecraft:unbreaking"));check(!document.metadata().pdc().containsKey("myplugin:weapon_uuid"));
        });
        test("materializer.fresh-template-change-requires-confirmation",()->{
            var items=OutputDemo.items();items.template(SyntheticItems.vanilla("minecraft:diamond_sword",1,5,1561)); // old pinned template stays in snapshot map
            var p=plan(new FailurePolicy.Destroy());var service=service(new SimulationOutputStore(),materializer(items)::materialize);eq("RECONFIRM_REQUIRED",get(service.prepare(p)).reason());
        });
        test("materializer.changed-price-refuses-before-source",()->{
            var items=OutputDemo.items();var values=ValueDefinitions.manualOnly(Map.of(ItemKey.of("minecraft:diamond_sword"),new BigDecimal("3000")));
            var m=new OutputMaterializer(items,values,7,Runnable::run,CLOCK);eq("RECONFIRM_REQUIRED",get(service(new SimulationOutputStore(),m::materialize).prepare(plan(new FailurePolicy.Destroy()))).reason());
        });
        test("materializer.unknown-output-price-is-not-zero",()->{
            var m=new OutputMaterializer(OutputDemo.items(),ValueDefinitions.manualOnly(Map.of()),7,Runnable::run,CLOCK);eq("UNKNOWN_VALUE",get(service(new SimulationOutputStore(),m::materialize).prepare(plan(new FailurePolicy.Destroy()))).reason());
        });
        test("materializer.downgrade-must-be-different-source-key",()->eq("INVALID_FAILURE",get(service(new SimulationOutputStore(),materializer(OutputDemo.items())::materialize).prepare(plan(new FailurePolicy.Downgrade(ItemKey.of("minecraft:iron_sword"),1,TransferPolicy.clean())))).reason()));
        test("materializer.downgrade-must-be-strictly-lower-total",()->eq("INVALID_FAILURE",get(service(new SimulationOutputStore(),materializer(OutputDemo.items())::materialize).prepare(plan(new FailurePolicy.Downgrade(ItemKey.of("minecraft:diamond_sword"),1,TransferPolicy.clean())))).reason()));
        test("materializer.native-facts-mismatch-is-definite-rejection",()->{
            var p=plan(new FailurePolicy.Destroy());var f=p.source().facts();var forged=new ItemSnapshot(new ItemFacts(f.key(),1,99,250,Map.of(),"",Set.of()),p.source().bytes());var bad=change(p,p.quoteId(),forged,p.target(),p.terms().output().orElseThrow());
            eq("INVALID_SNAPSHOT",get(service(new SimulationOutputStore(),materializer(OutputDemo.items())::materialize).prepare(bad)).reason());
        });
        test("materializer.revision-mismatch-rejected",()->{var m=new OutputMaterializer(OutputDemo.items(),OutputDemo.values(),8,Runnable::run,CLOCK);eq("RECONFIRM_REQUIRED",get(service(new SimulationOutputStore(),m::materialize).prepare(plan(new FailurePolicy.Destroy()))).reason());});
        test("materializer.keep-never-recreates-source",()->{var items=OutputDemo.items();get(materializer(items).materialize(plan(new FailurePolicy.Keep())));eq(1,items.creations.get());eq(0,items.mutations.get());});
        test("materializer.damage-mutates-copy-only-once-before-outcome",()->{var items=OutputDemo.items();var p=plan(new FailurePolicy.Damage(2000,FailurePolicy.BreakBehavior.DESTROY));var before=p.source().fingerprint();var out=get(materializer(items).materialize(p));eq(1,items.creations.get());eq(1,items.mutations.get());eq(before,p.source().fingerprint());eq(90,out.failure().orElseThrow().facts().damage());});
        test("materializer.destroy-at-break-has-no-failure-item",()->check(get(materializer(OutputDemo.items()).materialize(plan(new FailurePolicy.Damage(10000,FailurePolicy.BreakBehavior.DESTROY)))).failure().isEmpty()));
    }
    private static void storeTests(){
        test("store.unknown-provider-failure-emits-diagnostic",()->{
            var recorded=new ArrayList<Throwable>();var error=new IllegalStateException("provider acknowledgement uncertain");
            var svc=new OutputPreparationService(new SimulationOutputStore(),p->{throw error;},Runnable::run,CLOCK,(operation,cause)->recorded.add(cause));
            eq(OutputPreparationService.Status.AMBIGUOUS,get(svc.prepare(plan(new FailurePolicy.Destroy()))).status());eq(List.of(error),recorded);
        });
        test("store.created-claim-must-match-before-factory",()->{
            var p=plan(new FailurePolicy.Destroy());var called=new AtomicInteger();
            OutputStore malicious=new OutputStore(){
                public CompletionStage<Claim> claim(Row pending){return CompletableFuture.completedFuture(new Claim(true,Row.pending(new UUID(0,9999),pending.playerId(),pending.planDigest(),pending.createdAt())));}
                public CompletionStage<Optional<Row>> find(UUID id){throw new AssertionError("not queried");}
                public CompletionStage<Boolean> finish(Row a,Row b){throw new AssertionError("not finished");}
            };
            var svc=service(malicious,q->{called.incrementAndGet();throw new AssertionError("must not create");});
            try{get(svc.prepare(p));throw new AssertionError("expected contract failure");}catch(ExecutionException expected){check(expected.getCause() instanceof IllegalStateException);}eq(0,called.get());
        });
        test("store.ready-under-wrong-attempt-is-conflict",()->{
            var p=plan(new FailurePolicy.Destroy());OutputStore malicious=new OutputStore(){
                public CompletionStage<Claim> claim(Row row){return CompletableFuture.completedFuture(new Claim(false,Row.pending(new UUID(0,9999),row.playerId(),row.planDigest(),row.createdAt())));}
                public CompletionStage<Optional<Row>> find(UUID id){return CompletableFuture.completedFuture(Optional.empty());}
                public CompletionStage<Boolean> finish(Row a,Row b){throw new AssertionError("not finished");}
            };eq(OutputPreparationService.Status.CONFLICT,get(service(malicious,q->{throw new AssertionError("no factory");}).prepare(p)).status());
        });
        test("store.lost-ready-commit-ack-does-not-recreate",()->{
            var actual=new SimulationOutputStore();var items=OutputDemo.items();
            OutputStore uncertain=new OutputStore(){
                public CompletionStage<Claim> claim(Row row){return actual.claim(row);}
                public CompletionStage<Optional<Row>> find(UUID id){return actual.find(id);}
                public CompletionStage<Boolean> finish(Row a,Row b){return actual.finish(a,b).thenCompose(saved->CompletableFuture.failedFuture(new IllegalStateException("commit succeeded, acknowledgement lost")));}
            };
            var svc=service(uncertain,materializer(items)::materialize);var p=plan(new FailurePolicy.Destroy());
            try{get(svc.prepare(p));throw new AssertionError("expected uncertain commit");}catch(ExecutionException expected){check(expected.getCause() instanceof IllegalStateException);}
            eq(OutputPreparationService.Status.READY,get(svc.prepare(p)).status());eq(1,items.creations.get());
        });
        test("rules.protection-replaces-full-damage-policy",()->{
            var rules=new OutputRules(TransferPolicy.clean(),Map.of(),Map.of("standard",new FailurePolicy.Damage(2000,FailurePolicy.BreakBehavior.DESTROY)));
            check(rules.spec(Optional.empty(),"standard",RiskProfile.FailureMode.KEEP).failure() instanceof FailurePolicy.Keep);
            eq(2000,((FailurePolicy.Damage)rules.spec(Optional.empty(),"standard",RiskProfile.FailureMode.DAMAGE).failure()).basisPoints());
        });
        test("rules.damage-needs-explicit-parameters",()->rejects(()->OutputRules.defaults().spec(Optional.empty(),"standard",RiskProfile.FailureMode.DAMAGE)));

        test("store.claim-before-create-and-ready-after-persist",()->{
            var store=new SimulationOutputStore();var m=materializer(OutputDemo.items());var p=plan(new FailurePolicy.Destroy());
            var svc=service(store,q->{try{eq(OutputStore.State.PREPARING,get(store.find(p.attemptId())).orElseThrow().state());}catch(Exception e){throw new IllegalStateException(e);}return m.materialize(q);});
            eq(OutputPreparationService.Status.READY,get(svc.prepare(p)).status());eq(OutputStore.State.READY,get(store.find(p.attemptId())).orElseThrow().state());
        });
        test("store.duplicate-ready-never-recreates",()->{var items=OutputDemo.items();var svc=service(new SimulationOutputStore(),materializer(items)::materialize);var p=plan(new FailurePolicy.Destroy());var a=get(svc.prepare(p));var b=get(svc.prepare(p));eq(OutputCodec.digest(a.output().orElseThrow()),OutputCodec.digest(b.output().orElseThrow()));eq(1,items.creations.get());});
        test("store.pending-after-crash-never-recreates",()->{var store=new SimulationOutputStore();var p=plan(new FailurePolicy.Destroy());get(store.claim(OutputStore.Row.pending(p.attemptId(),p.playerId(),JournalCodec.planDigest(p),NOW)));var calls=new AtomicInteger();var svc=service(store,q->{calls.incrementAndGet();throw new IllegalStateException();});eq(OutputPreparationService.Status.AMBIGUOUS,get(svc.prepare(p)).status());eq(0,calls.get());});
        test("store.same-attempt-different-policy-conflicts",()->{var store=new SimulationOutputStore();var svc=service(store,materializer(OutputDemo.items())::materialize);var p=plan(new FailurePolicy.Destroy());get(svc.prepare(p));eq(OutputPreparationService.Status.CONFLICT,get(svc.prepare(plan(new FailurePolicy.Keep()))).status());});
        test("store.definite-rejection-is-terminal-no-retry",()->{var calls=new AtomicInteger();var svc=service(new SimulationOutputStore(),p->{calls.incrementAndGet();throw new OutputRejectedException(OutputRejectedException.Code.CAPABILITY_UNAVAILABLE,"missing provider");});for(int n=0;n<5;n++)eq(OutputPreparationService.Status.REJECTED,get(svc.prepare(plan(new FailurePolicy.Destroy()))).status());eq(1,calls.get());});
        test("store.exception-is-ambiguous-not-refund-proof",()->{var svc=service(new SimulationOutputStore(),p->{throw new IllegalStateException("unexpected provider reply");});eq(OutputPreparationService.Status.AMBIGUOUS,get(svc.prepare(plan(new FailurePolicy.Destroy()))).status());});
        test("store.cancel-view-still-persists-original-creation",()->{
            var store=new SimulationOutputStore();var deferred=new CompletableFuture<PreparedOutput>();var svc=service(store,p->deferred);var p=plan(new FailurePolicy.Destroy());var view=svc.prepare(p).toCompletableFuture();view.cancel(true);deferred.complete(prepared(new FailurePolicy.Destroy()));eq(OutputPreparationService.Status.READY,get(svc.ready(p)).status());
        });
        test("store.stale-finish-does-not-overwrite-ready",()->{var store=new SimulationOutputStore();var p=plan(new FailurePolicy.Destroy());var row=OutputStore.Row.pending(p.attemptId(),p.playerId(),JournalCodec.planDigest(p),NOW);get(store.claim(row));var done=ready(row,prepared(new FailurePolicy.Destroy()));check(get(store.finish(row,done)));check(!get(store.finish(row,done)));});
        test("store.expired-claim-does-not-call-provider",()->{var calls=new AtomicInteger();var p=plan(new FailurePolicy.Destroy());var svc=new OutputPreparationService(new SimulationOutputStore(),q->{calls.incrementAndGet();throw new IllegalStateException();},Runnable::run,Clock.fixed(p.expiresAt(),ZoneOffset.UTC),(operation,error)->DIAGNOSTICS.add(error));eq("EXPIRED",get(svc.prepare(p)).reason());eq(0,calls.get());});
        test("store.ready-read-still-works-after-expiry",()->{var store=new SimulationOutputStore();var svc=service(store,materializer(OutputDemo.items())::materialize);var p=plan(new FailurePolicy.Destroy());get(svc.prepare(p));var later=new OutputPreparationService(store,q->{throw new IllegalStateException("never create again");},Runnable::run,Clock.fixed(p.expiresAt().plusSeconds(99),ZoneOffset.UTC),(operation,error)->DIAGNOSTICS.add(error));eq(OutputPreparationService.Status.READY,get(later.prepare(p)).status());});
        test("store.fresh-identity-collision-is-atomic",()->{
            var store=new SimulationOutputStore();var a=prepared(new FailurePolicy.Destroy());String token=SyntheticItems.hash("duplicate-unique");
            var rowA=OutputStore.Row.pending(a.attemptId(),a.playerId(),a.planDigest(),NOW);get(store.claim(rowA));var one=new PreparedOutput(a.attemptId(),a.playerId(),a.planDigest(),a.success(),a.failure(),Set.of(token),Set.of(),NOW);check(get(store.finish(rowA,ready(rowA,one))));
            var rowB=OutputStore.Row.pending(new UUID(0,999),a.playerId(),a.planDigest(),NOW);get(store.claim(rowB));var two=new PreparedOutput(rowB.attemptId(),a.playerId(),a.planDigest(),a.success(),a.failure(),Set.of(token),Set.of(),NOW);
            try{get(store.finish(rowB,ready(rowB,two)));throw new AssertionError("duplicate accepted");}catch(ExecutionException expected){check(true);}eq(OutputStore.State.PREPARING,get(store.find(rowB.attemptId())).orElseThrow().state());
        });
    }
    private static UpgradeTransactionEngine engine(SimulationJournal journal,OutputPreparationService outputs,EffectPort nativeEffects,List<PreparedDeliveryPort.Delivery> delivered,TicketSource random){
        var port=new OutputBoundEffectPort(outputs,nativeEffects,d->{delivered.add(d);return CompletableFuture.completedFuture(new EffectReceipt(EffectReceipt.Status.APPLIED,"synthetic:delivery"));},Runnable::run);
        return new UpgradeTransactionEngine(AsyncTransactionJournal.offload(journal,Runnable::run),port,random,Runnable::run,CLOCK,16,(name,error)->{});
    }
    private static void integrationTests(){
        for(FailurePolicy failure:List.of(new FailurePolicy.Destroy(),new FailurePolicy.Keep(),new FailurePolicy.Damage(2000,FailurePolicy.BreakBehavior.DESTROY),new FailurePolicy.Downgrade(ItemKey.of("minecraft:stone_sword"),1,TransferPolicy.clean())))for(boolean win:List.of(true,false))
            test("engine.prepared-output-"+failure.mode()+"-"+(win?"win":"loss"),()->{
                var items=OutputDemo.items();var store=new SimulationOutputStore();var service=service(store,materializer(items)::materialize);var journal=new SimulationJournal();var deliveries=new ArrayList<PreparedDeliveryPort.Delivery>();var calls=new ArrayList<Effect.Kind>();var draws=new AtomicInteger();
                EffectPort nativeEffects=c->{calls.add(c.effect().kind());if(c.effect().kind()==Effect.Kind.HOLD_SOURCE){try{eq(OutputStore.State.READY,get(store.find(c.intent().id())).orElseThrow().state());}catch(Exception e){throw new IllegalStateException(e);}}return CompletableFuture.completedFuture(new EffectReceipt(EffectReceipt.Status.APPLIED,"synthetic:"+c.ordinal()));};
                var engine=engine(journal,service,nativeEffects,deliveries,()->{draws.incrementAndGet();return win?0:999_999_999;});var p=plan(failure);var first=get(engine.submit(p));eq(UpgradeTransactionEngine.Status.COMPLETED,first.status());eq(UpgradeTransactionEngine.Status.DUPLICATE,get(engine.submit(p)).status());eq(1,draws.get());
                eq(failure instanceof FailurePolicy.Downgrade?2:1,items.creations.get());check(!calls.contains(Effect.Kind.DELIVER_TARGET));check(!calls.contains(Effect.Kind.DELIVER_FAILURE_OUTPUT));
                int expected=win||failure instanceof FailurePolicy.Damage||failure instanceof FailurePolicy.Downgrade?1:0;eq(expected,deliveries.size());
                if(!win&&failure instanceof FailurePolicy.Keep)check(calls.contains(Effect.Kind.RETURN_SOURCE));
                if(win)eq(ItemKey.of("minecraft:diamond_sword"),deliveries.getFirst().item().facts().key());
                if(!win&&failure instanceof FailurePolicy.Damage)eq(90,deliveries.getFirst().item().facts().damage());
                if(!win&&failure instanceof FailurePolicy.Downgrade)eq(ItemKey.of("minecraft:stone_sword"),deliveries.getFirst().item().facts().key());
            });
        test("engine.output-reject-aborts-before-hold-or-roll",()->{
            var svc=service(new SimulationOutputStore(),p->{throw new OutputRejectedException(OutputRejectedException.Code.RECONFIRM_REQUIRED,"price changed");});var calls=new ArrayList<Effect.Kind>();var draws=new AtomicInteger();
            var engine=engine(new SimulationJournal(),svc,c->{calls.add(c.effect().kind());return CompletableFuture.completedFuture(new EffectReceipt(EffectReceipt.Status.APPLIED,"sim:ok"));},new ArrayList<>(),()->{draws.incrementAndGet();return 0;});eq(UpgradeTransactionEngine.Status.ABORTED,get(engine.submit(plan(new FailurePolicy.Destroy()))).status());eq(List.of(Effect.Kind.LEDGER_CLAIM,Effect.Kind.LEDGER_FAIL),calls);eq(0,draws.get());
        });
        test("engine.creation-uncertainty-keeps-lock-no-consume-no-roll",()->{
            var store=new SimulationOutputStore();var svc=service(store,p->{throw new IllegalStateException("provider timeout");});var journal=new SimulationJournal();var calls=new ArrayList<Effect.Kind>();var engine=engine(journal,svc,c->{calls.add(c.effect().kind());return CompletableFuture.completedFuture(new EffectReceipt(EffectReceipt.Status.APPLIED,"sim:ok"));},new ArrayList<>(),()->{throw new AssertionError("must not draw");});var p=plan(new FailurePolicy.Destroy());eq(UpgradeTransactionEngine.Status.RECONCILIATION_REQUIRED,get(engine.submit(p)).status());eq(List.of(Effect.Kind.LEDGER_CLAIM),calls);eq(Optional.of(p.attemptId()),journal.activeAttempt(p.playerId()));
        });
        test("engine.legacy-plan-refused-before-native-acquisition",()->{
            var modern=plan(new FailurePolicy.Destroy());var t=modern.terms();
            var p=new AttemptPlan(modern.playerId(),modern.sessionId(),modern.quoteId(),modern.configRevision(),modern.catalogGeneration(),modern.sourceSlot(),modern.source(),modern.targetId(),modern.target(),modern.sourceTotal(),modern.targetTotal(),
                    new UpgradeQuote.Terms(t.profileId(),t.boosts(),t.permissionBonuses(),t.probability(),t.failure(),t.costs()),modern.feeItems(),modern.expiresAt());
            var svc=service(new SimulationOutputStore(),q->{throw new AssertionError("not called");});var engine=engine(new SimulationJournal(),svc,c->{throw new AssertionError("legacy native call");},new ArrayList<>(),()->{throw new AssertionError("legacy draw");});eq(UpgradeTransactionEngine.Status.ABORTED,get(engine.submit(p)).status());
        });
        test("engine.preparation-is-after-ledger-before-source",()->{var steps=EffectScript.reservation(plan(new FailurePolicy.Destroy()));eq(List.of(Effect.Kind.LEDGER_CLAIM,Effect.Kind.PREPARE_OUTPUT,Effect.Kind.HOLD_SOURCE),steps.stream().map(Effect::kind).toList());});
        test("engine.unknown-delivery-never-recreates-or-redelivers",()->{
            var items=OutputDemo.items();var svc=service(new SimulationOutputStore(),materializer(items)::materialize);var journal=new SimulationJournal();var count=new AtomicInteger();
            var port=new OutputBoundEffectPort(svc,c->CompletableFuture.completedFuture(new EffectReceipt(EffectReceipt.Status.APPLIED,"sim:ok")),d->{count.incrementAndGet();return CompletableFuture.completedFuture(EffectReceipt.unknown("ack-lost"));},Runnable::run);
            var engine=new UpgradeTransactionEngine(AsyncTransactionJournal.offload(journal,Runnable::run),port,()->0,Runnable::run,CLOCK,8,(n,e)->{});var p=plan(new FailurePolicy.Destroy());eq(UpgradeTransactionEngine.Status.RECONCILIATION_REQUIRED,get(engine.submit(p)).status());eq(UpgradeTransactionEngine.Status.DUPLICATE,get(engine.submit(p)).status());eq(1,count.get());eq(1,items.creations.get());
        });
    }
    private static void propertyTests(){
        test("property.3000-damage-cases-independent-integer-math",()->{
            var random=new Random(509);var planner=new FailurePlanner();
            for(int n=0;n<3000;n++){
                int max=random.nextInt(2,200_000),damage=random.nextInt(max),basis=random.nextInt(1,10001);var doc=SyntheticItems.vanilla("minecraft:iron_sword",1,damage,max);var result=planner.damage(doc,new FailurePolicy.Damage(basis,FailurePolicy.BreakBehavior.DESTROY));
                java.math.BigInteger delta=java.math.BigInteger.valueOf(max).multiply(java.math.BigInteger.valueOf(basis)).add(java.math.BigInteger.valueOf(9999)).divide(java.math.BigInteger.valueOf(10000));
                long expected=damage+delta.longValueExact();eq(expected>=max,result.destroyed());if(expected<max)eq((int)expected,result.damage().getAsInt());
            }
        });
        test("property.500-corrupted-output-payloads-rejected",()->{byte[] original=OutputCodec.encode(prepared(new FailurePolicy.Keep()));var random=new Random(5);for(int n=0;n<500;n++){byte[] bytes=original.clone();int at=random.nextInt(bytes.length);bytes[at]^=(byte)(1<<random.nextInt(8));rejects(()->OutputCodec.decode(bytes));}});
        test("property.64-concurrent-prepare-one-winner",()->{
            var store=new SimulationOutputStore();var calls=new AtomicInteger();var deferred=new CompletableFuture<PreparedOutput>();var p=plan(new FailurePolicy.Destroy());var svc=service(store,q->{calls.incrementAndGet();return deferred;});var futures=new ArrayList<CompletionStage<OutputPreparationService.Result>>();
            ExecutorService workers=Executors.newFixedThreadPool(8);try{
                var jobs=new ArrayList<Future<CompletionStage<OutputPreparationService.Result>>>();for(int n=0;n<64;n++)jobs.add(workers.submit(()->svc.prepare(p)));for(var job:jobs)futures.add(job.get());deferred.complete(prepared(new FailurePolicy.Destroy()));
                int ready=0;for(var stage:futures){var result=get(stage);if(result.status()==OutputPreparationService.Status.READY)ready++;else eq(OutputPreparationService.Status.AMBIGUOUS,result.status());}eq(1,ready);eq(1,calls.get());
            }finally{workers.shutdownNow();check(workers.awaitTermination(5,TimeUnit.SECONDS));}
        });
    }
}
