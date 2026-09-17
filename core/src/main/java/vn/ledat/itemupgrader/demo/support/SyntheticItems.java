package vn.ledat.itemupgrader.demo.support;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import vn.ledat.itemupgrader.item.*;
import vn.ledat.itemupgrader.output.*;
import vn.ledat.itemupgrader.transfer.*;
import vn.ledat.itemupgrader.transaction.storage.JournalCodec;

/** SYNTHETIC, NOT NBT. Only a deterministic fixture for offline tests/demo, excluded from plugin and core jars. */
public class SyntheticItems implements ItemMutationPort {
    private final Map<String,ItemDocument> snapshots=new ConcurrentHashMap<>();
    private final Map<ItemKey,ItemDocument> templates=new ConcurrentHashMap<>();
    public final AtomicInteger creations=new AtomicInteger(), mutations=new AtomicInteger();
    public void remember(ItemDocument document){snapshots.put(document.snapshot().fingerprint(),document);}
    public void template(ItemDocument document){remember(document);templates.put(document.snapshot().facts().key(),document);}
    @Override public CompletionStage<ItemDocument> inspect(Context context,ItemSnapshot snapshot){
        ItemDocument result=snapshots.get(snapshot.fingerprint());
        if(result==null)return CompletableFuture.failedFuture(new OutputRejectedException(OutputRejectedException.Code.INVALID_SNAPSHOT,"unknown synthetic payload"));
        return CompletableFuture.completedFuture(result);
    }
    @Override public CompletionStage<ItemDocument> createFresh(Context context,ItemKey key,int amount){
        int count=creations.incrementAndGet();ItemDocument original=templates.get(key);
        if(original==null)throw new OutputRejectedException(OutputRejectedException.Code.CAPABILITY_UNAVAILABLE,"no synthetic template");
        var f=original.snapshot().facts();var m=original.metadata();
        var facts=new ItemFacts(key,amount,f.damage(),f.maximumDamage(),f.enchantments(),f.rarity(),Set.of());
        Set<String> ids=m.uniqueTokens().isEmpty()?Set.of():Set.of(hash("fresh:"+key+":"+context.attemptId()+":"+count));
        var result=document(facts,m.customName(),m.repairCost(),m.unbreakable(),m.pdc(),ids,original.capabilities());remember(result);return CompletableFuture.completedFuture(result);
    }
    @Override public CompletionStage<ItemDocument> apply(Context context,ItemDocument before,MetadataPatch patch){
        mutations.incrementAndGet();var f=before.snapshot().facts();var m=before.metadata();
        var facts=new ItemFacts(f.key(),f.amount(),patch.damage().orElse(f.damage()),f.maximumDamage(),patch.enchantments().orElse(f.enchantments()),f.rarity(),f.risks());
        var pdc=new TreeMap<>(m.pdc());pdc.putAll(patch.pdc());
        var result=document(facts,patch.customName().isPresent()?patch.customName():m.customName(),patch.repairCost().orElse(m.repairCost()),m.unbreakable(),pdc,m.uniqueTokens(),before.capabilities());
        remember(result);return CompletableFuture.completedFuture(result);
    }
    public static ItemDocument vanilla(String key,int amount,int damage,int maximum){
        var facts=new ItemFacts(ItemKey.of(key),amount,damage,maximum,Map.of(),"",Set.of());
        return document(facts,Optional.empty(),0,false,Map.of(),Set.of(),caps("minecraft",maximum>0?1:64,false));
    }
    public static MutationCapabilities caps(String provider,int stack,boolean unique){
        return new MutationCapabilities(provider,true,true,true,stack,unique,
                Map.of("minecraft:unbreaking",3,"minecraft:sharpness",5,"minecraft:smite",5),
                Set.of(new MutationCapabilities.Conflict("minecraft:sharpness","minecraft:smite")),
                Map.of("myplugin:soulbound_owner",PdcValue.Type.STRING),Set.of("myplugin:weapon_uuid"));
    }
    public static ItemDocument document(ItemFacts facts,Optional<String> name,int repair,boolean unbreakable,Map<String,PdcValue> pdc,Set<String> tokens,MutationCapabilities caps){
        String values=facts.key()+":"+facts.amount()+":"+facts.damage()+":"+facts.maximumDamage()+":"+new TreeMap<>(facts.enchantments())+":"+facts.rarity()
                +":"+name+":"+repair+":"+unbreakable+":"+pdcString(pdc);
        String protectedData=facts.key()+":"+facts.amount()+":"+facts.maximumDamage()+":"+facts.rarity()+":"+unbreakable+":"+new TreeSet<>(tokens);
        byte[] bytes=("SYNTHETIC-ITEM:"+values+":"+new TreeSet<>(tokens)).getBytes(StandardCharsets.UTF_8);
        return new ItemDocument(new ItemSnapshot(facts,bytes),new MetadataView(name,repair,unbreakable,pdc,hash(protectedData),hash(values),tokens),caps);
    }
    private static String pdcString(Map<String,PdcValue> values){var out=new StringBuilder();new TreeMap<>(values).forEach((k,v)->out.append(k).append(':').append(v.type()).append(':').append(Base64.getEncoder().encodeToString(v.bytes())).append(';'));return out.toString();}
    public static String hash(String value){return JournalCodec.digest(value.getBytes(StandardCharsets.UTF_8));}
}
