package vn.ledat.itemupgrader.transaction.storage;

import java.io.*;
import java.math.BigDecimal;
import java.security.*;
import java.time.Instant;
import java.util.*;
import vn.ledat.itemupgrader.item.*;
import vn.ledat.itemupgrader.cost.*;
import vn.ledat.itemupgrader.chance.Probability;
import vn.ledat.itemupgrader.profile.RiskProfile;
import vn.ledat.itemupgrader.quote.UpgradeQuote;
import vn.ledat.itemupgrader.transaction.TransactionMachine;
import vn.ledat.itemupgrader.transaction.model.*;

/** Bounded, versioned binary encoding, NOT Java ObjectInputStream. Checksums detect damage, not malicious DB edits. */
public final class JournalCodec {
    public static final int VERSION = 3;
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    public static final int MAX_STATE_BYTES = 64 * 1024;
    private static final int MAGIC = 0x49555034;
    @FunctionalInterface private interface Writer { void write(DataOutputStream out) throws IOException; }
    private JournalCodec() {}
    public static byte[] encodePlan(AttemptPlan plan) { return encode(1, plan.terms().pity().isPresent() ? 3 : plan.terms().output().isPresent() ? 2 : 1, out -> writePlan(out, plan)); }
    public static String planDigest(AttemptPlan plan) { return digest(encodePlan(plan)); }
    public static byte[] encodeRecord(AttemptRecord record) {
        TransactionMachine.validate(record);
        return encode(2, record.plan().terms().pity().isPresent() ? 3 : record.plan().terms().output().isPresent() ? 2 : 1, out -> { writePlan(out, record.plan()); writeState(out, record); });
    }
    /** SQL stores the immutable plan once; state transitions rewrite only this small envelope. */
    public static byte[] encodeState(AttemptRecord record) {
        TransactionMachine.validate(record);
        byte[] bytes=encode(3, 1, out -> { out.writeUTF(planDigest(record.plan())); uuid(out,record.id()); writeState(out,record); });
        if(bytes.length>MAX_STATE_BYTES)throw new IllegalArgumentException("state envelope budget exceeded");
        return bytes;
    }
    public static AttemptRecord decodeState(AttemptPlan plan,byte[] bytes) {
        if(bytes.length>MAX_STATE_BYTES)throw new IllegalArgumentException("state envelope budget exceeded");
        try(DataInputStream in=input(bytes,3)) {
            if(!in.readUTF().equals(planDigest(plan)) || !uuid(in).equals(plan.attemptId()))throw new IOException("state/plan binding mismatch");
            var record=readState(in,plan);end(in);TransactionMachine.validate(record);return record;
        } catch(IOException|RuntimeException e){throw corrupt(e);}
    }
    private static void writeState(DataOutputStream out,AttemptRecord record)throws IOException {
        out.writeUTF(record.state().name());out.writeLong(record.version());out.writeInt(record.steps().size());
        for(var step:record.steps()) {
            out.writeUTF(step.effect().kind().name());out.writeInt(step.effect().reference());decimal(out,step.effect().amount());
            out.writeUTF(step.receipt().status().name());out.writeUTF(step.receipt().evidence());
        }
        out.writeBoolean(record.sample()!=null);if(record.sample()!=null)out.writeLong(record.sample());
        instant(out,record.createdAt());instant(out,record.updatedAt());out.writeUTF(record.reason());
    }
    private static AttemptRecord readState(DataInputStream in,AttemptPlan plan)throws IOException {
        var state=AttemptRecord.State.valueOf(in.readUTF());long version=in.readLong();int count=count(in,AttemptRecord.MAX_STEPS);
        List<AttemptRecord.Step> steps=new ArrayList<>();
        for(int i=0;i<count;i++)steps.add(new AttemptRecord.Step(new Effect(Effect.Kind.valueOf(in.readUTF()),in.readInt(),decimal(in)),
                new EffectReceipt(EffectReceipt.Status.valueOf(in.readUTF()),in.readUTF())));
        Long sample=in.readBoolean()?in.readLong():null;
        return new AttemptRecord(plan,state,version,steps,sample,instant(in),instant(in),in.readUTF());
    }
    public static AttemptPlan decodePlan(byte[] bytes) {
        try (DataInputStream in = input(bytes, 1)) { var plan = readPlan(in, java.nio.ByteBuffer.wrap(bytes).getInt(4)); end(in); return plan; }
        catch (IOException | RuntimeException error) { throw corrupt(error); }
    }
    public static AttemptRecord decodeRecord(byte[] bytes) {
        try (DataInputStream in = input(bytes, 2)) {
            var plan = readPlan(in, java.nio.ByteBuffer.wrap(bytes).getInt(4)); var record=readState(in,plan);
            end(in); TransactionMachine.validate(record); return record;
        } catch (IOException | RuntimeException error) { throw corrupt(error); }
    }
    /** Repository write guard. Allows exactly one reducer step; a random syntactically valid row cannot skip effects. */
    public static void checkTransition(AttemptRecord old, AttemptRecord next) {
        if (old.terminal() || next.version() != old.version() + 1 || !old.createdAt().equals(next.createdAt())
                || next.updatedAt().isBefore(old.updatedAt()) || !planDigest(old.plan()).equals(planDigest(next.plan())))
            throw new IllegalArgumentException("invalid transition binding/version");
        var machine = new TransactionMachine(); AttemptRecord expected;
        if (old.sample() == null && next.sample() != null) expected = machine.commitDraw(old, next.sample(), next.updatedAt());
        else if (next.steps().size() == old.steps().size() && TransactionMachine.pending(old)
                && next.steps().getLast().receipt().status() != EffectReceipt.Status.INTENT)
            expected = machine.receipt(old, old.steps().size()-1, next.steps().getLast().receipt(), next.updatedAt());
        else if (next.state() == AttemptRecord.State.RECONCILIATION_REQUIRED)
            expected = machine.quarantine(old, next.updatedAt(), next.reason());
        else {
            var decision = machine.next(old, next.updatedAt());
            if (decision.stopped()) throw new IllegalArgumentException("frozen transaction");
            expected = decision.next();
        }
        if (!Arrays.equals(encodeRecord(expected), encodeRecord(next))) throw new IllegalArgumentException("transition does not match reducer");
    }
    public static String digest(byte[] value) { return HexFormat.of().formatHex(hash(value)); }
    private static byte[] hash(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
    private static byte[] encode(int type, int version, Writer writer) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(buffer)) {
                out.writeInt(MAGIC); out.writeInt(version); out.writeByte(type); writer.write(out);
            }
            if (buffer.size() + 32 > MAX_BYTES) throw new IllegalArgumentException("journal payload limit");
            byte[] body = buffer.toByteArray(); buffer.write(hash(body)); return buffer.toByteArray();
        } catch (IOException e) { throw new IllegalStateException("in-memory encoding failed", e); }
    }
    private static DataInputStream input(byte[] bytes, int type) throws IOException {
        Objects.requireNonNull(bytes);
        if (bytes.length < 41 || bytes.length > MAX_BYTES) throw new IOException("invalid encoded length");
        byte[] body = Arrays.copyOf(bytes, bytes.length - 32);
        if (!MessageDigest.isEqual(hash(body), Arrays.copyOfRange(bytes, body.length, bytes.length))) throw new IOException("checksum mismatch");
        var in = new DataInputStream(new ByteArrayInputStream(body));
        if (in.readInt() != MAGIC) throw new IOException("unsupported payload magic");
        int version = in.readInt();
        if (version < 1 || version > VERSION || in.readUnsignedByte() != type) throw new IOException("unsupported payload version/type");
        return in;
    }
    private static void writePlan(DataOutputStream o, AttemptPlan p) throws IOException {
        uuid(o,p.playerId()); uuid(o,p.sessionId()); uuid(o,p.quoteId()); o.writeLong(p.configRevision()); uuid(o,p.catalogGeneration());
        o.writeInt(p.sourceSlot()); item(o,p.source()); o.writeUTF(p.targetId()); item(o,p.target());
        decimal(o,p.sourceTotal()); decimal(o,p.targetTotal());
        var t=p.terms(); o.writeUTF(t.profileId()); strings(o,t.boosts()); strings(o,t.permissionBonuses());
        o.writeLong(t.probability().winningTickets()); o.writeUTF(t.failure().name()); o.writeInt(t.costs().lines().size());
        for (var line:t.costs().lines()) {
            o.writeUTF(line.resource().kind().name()); o.writeUTF(line.resource().key());
            decimal(o,line.onAttempt()); decimal(o,line.onSuccess()); decimal(o,line.onFailure());
        }
        o.writeInt(p.feeItems().size());
        for (var hold:p.feeItems()) { o.writeInt(hold.slot()); o.writeInt(hold.amount()); item(o,hold.snapshot()); }
        instant(o,p.expiresAt());
        if (p.terms().output().isPresent()) vn.ledat.itemupgrader.output.OutputSpecCodec.write(o, p.terms().output().orElseThrow());
        if (p.terms().pity().isPresent()) p.terms().pity().orElseThrow().write(o);
    }
    private static AttemptPlan readPlan(DataInputStream i, int version) throws IOException {
        UUID player=uuid(i),session=uuid(i),quote=uuid(i); long revision=i.readLong(); UUID gen=uuid(i);
        int sourceSlot=i.readInt(); var source=item(i); String targetId=i.readUTF(); var target=item(i);
        BigDecimal sv=decimal(i),tv=decimal(i); String profile=i.readUTF(); List<String> boosts=strings(i), bonuses=strings(i);
        var probability=new Probability(i.readLong()); var failure=RiskProfile.FailureMode.valueOf(i.readUTF());
        List<CostPlan.Line> lines=new ArrayList<>(); int count=count(i,32);
        for(int n=0;n<count;n++) lines.add(new CostPlan.Line(new CostResource(CostResource.Kind.valueOf(i.readUTF()),i.readUTF()),decimal(i),decimal(i),decimal(i)));
        List<AttemptPlan.ItemHold> holds=new ArrayList<>(); count=count(i,35);
        for(int n=0;n<count;n++) holds.add(new AttemptPlan.ItemHold(i.readInt(),i.readInt(),item(i)));
        var expires = instant(i);
        Optional<vn.ledat.itemupgrader.output.OutputSpec> output = version >= 2
                ? Optional.of(vn.ledat.itemupgrader.output.OutputSpecCodec.read(i)) : Optional.empty();
        Optional<vn.ledat.itemupgrader.pity.PityStamp> pity = version >= 3
                ? Optional.of(vn.ledat.itemupgrader.pity.PityStamp.read(i)) : Optional.empty();
        return new AttemptPlan(player,session,quote,revision,gen,sourceSlot,source,targetId,target,sv,tv,
                new UpgradeQuote.Terms(profile,boosts,bonuses,probability,failure,new CostPlan(lines),output,pity),holds,expires);
    }
    public static byte[] encodeItem(ItemSnapshot item) { return encode(4, 1, out -> item(out, item)); }
    public static ItemSnapshot decodeItem(byte[] bytes) {
        try (DataInputStream in = input(bytes, 4)) { var item = item(in); end(in); return item; }
        catch (IOException | RuntimeException e) { throw corrupt(e); }
    }
    private static void item(DataOutputStream o, ItemSnapshot s) throws IOException {
        var f=s.facts(); o.writeUTF(f.key().value()); o.writeInt(f.amount()); o.writeInt(f.damage()); o.writeInt(f.maximumDamage());
        o.writeInt(f.enchantments().size());
        for(var entry:new TreeMap<>(f.enchantments()).entrySet()) { o.writeUTF(entry.getKey()); o.writeInt(entry.getValue()); }
        o.writeUTF(f.rarity()); o.writeInt(f.risks().size());
        for(var risk:new TreeSet<>(f.risks())) o.writeUTF(risk.name());
        o.writeInt(s.byteSize()); o.write(s.bytes());
    }
    private static ItemSnapshot item(DataInputStream i) throws IOException {
        var key=ItemKey.of(i.readUTF()); int amount=i.readInt(),damage=i.readInt(),max=i.readInt(),count=count(i,128);
        Map<String,Integer> enchants=new TreeMap<>();
        for(int n=0;n<count;n++) if(enchants.put(i.readUTF(),i.readInt())!=null) throw new IOException("duplicate enchant");
        String rarity=i.readUTF(); Set<ItemFacts.Risk> risks=EnumSet.noneOf(ItemFacts.Risk.class); count=count(i,ItemFacts.Risk.values().length);
        for(int n=0;n<count;n++) if(!risks.add(ItemFacts.Risk.valueOf(i.readUTF()))) throw new IOException("duplicate risk");
        int length=count(i,ItemSnapshot.HARD_MAX_BYTES);
        if(length==0 || length>i.available()) throw new IOException("invalid item byte length");
        return new ItemSnapshot(new ItemFacts(key,amount,damage,max,enchants,rarity,risks),i.readNBytes(length));
    }
    private static int count(DataInputStream i,int max) throws IOException {
        int value=i.readInt(); if(value<0||value>max) throw new IOException("collection length exceeds bound"); return value;
    }
    private static void uuid(DataOutputStream o,UUID u)throws IOException{o.writeLong(u.getMostSignificantBits());o.writeLong(u.getLeastSignificantBits());}
    private static UUID uuid(DataInputStream i)throws IOException{return new UUID(i.readLong(),i.readLong());}
    private static void decimal(DataOutputStream o,BigDecimal d)throws IOException{o.writeUTF(d.stripTrailingZeros().toPlainString());}
    private static BigDecimal decimal(DataInputStream i)throws IOException{
        String value=i.readUTF(); if(value.length()>64||!value.matches("-?[0-9]+(?:\\.[0-9]+)?"))throw new IOException("invalid decimal"); return new BigDecimal(value);
    }
    private static void strings(DataOutputStream o,List<String> s)throws IOException{o.writeInt(s.size());for(String v:s)o.writeUTF(v);}
    private static List<String> strings(DataInputStream i)throws IOException{int n=count(i,128);List<String> r=new ArrayList<>();for(int k=0;k<n;k++)r.add(i.readUTF());return r;}
    private static void instant(DataOutputStream o,Instant t)throws IOException{o.writeLong(t.getEpochSecond());o.writeInt(t.getNano());}
    private static Instant instant(DataInputStream i)throws IOException{long seconds=i.readLong();int nanos=i.readInt();if(nanos<0||nanos>=1_000_000_000)throw new IOException("invalid nanos");return Instant.ofEpochSecond(seconds,nanos);}
    private static void end(DataInputStream i)throws IOException{if(i.available()!=0)throw new IOException("trailing payload data");}
    private static IllegalArgumentException corrupt(Throwable e){return new IllegalArgumentException("invalid/corrupt transaction payload",e);}
}
