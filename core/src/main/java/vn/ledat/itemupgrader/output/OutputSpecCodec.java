package vn.ledat.itemupgrader.output;

import java.io.*;
import java.util.*;
import vn.ledat.itemupgrader.transfer.*;
import vn.ledat.itemupgrader.failure.FailurePolicy;
import vn.ledat.itemupgrader.item.ItemKey;

/** Embedded in journal v2. No live config is consulted to decode old output terms. */
public final class OutputSpecCodec {
    private OutputSpecCodec() {}
    public static void write(DataOutputStream out, OutputSpec spec) throws IOException {
        policy(out, spec.transfer()); out.writeUTF(spec.failure().mode().name());
        switch (spec.failure()) {
            case FailurePolicy.Destroy ignored -> { }
            case FailurePolicy.Keep ignored -> { }
            case FailurePolicy.Damage p -> { out.writeInt(p.basisPoints()); out.writeUTF(p.breakBehavior().name()); }
            case FailurePolicy.Downgrade p -> { out.writeUTF(p.key().value()); out.writeInt(p.amount()); policy(out, p.transfer()); }
        }
    }
    public static OutputSpec read(DataInputStream in) throws IOException {
        var transfer = policy(in);
        FailurePolicy failure = switch (in.readUTF()) {
            case "DESTROY" -> new FailurePolicy.Destroy();
            case "KEEP" -> new FailurePolicy.Keep();
            case "DAMAGE" -> new FailurePolicy.Damage(in.readInt(), FailurePolicy.BreakBehavior.valueOf(in.readUTF()));
            case "DOWNGRADE" -> new FailurePolicy.Downgrade(ItemKey.of(in.readUTF()), in.readInt(), policy(in));
            default -> throw new IOException("unsupported failure type");
        };
        return new OutputSpec(transfer, failure);
    }
    private static void policy(DataOutputStream out, TransferPolicy p) throws IOException {
        out.writeUTF(p.id()); out.writeBoolean(p.customName()); out.writeBoolean(p.repairCost()); out.writeUTF(p.durability().name());
        out.writeInt(p.enchantments().size());
        for (var entry : new TreeMap<>(p.enchantments()).entrySet()) { out.writeUTF(entry.getKey()); out.writeInt(entry.getValue()); }
        out.writeInt(p.pdc().size());
        for (var entry : new TreeMap<>(p.pdc()).entrySet()) { out.writeUTF(entry.getKey()); out.writeUTF(entry.getValue().name()); }
    }
    private static TransferPolicy policy(DataInputStream in) throws IOException {
        String id = in.readUTF(); boolean name = in.readBoolean(), repair = in.readBoolean();
        var durability = TransferPolicy.Durability.valueOf(in.readUTF());
        Map<String,Integer> enchants = new TreeMap<>();
        for (int n = count(in,64); n > 0; n--) if (enchants.put(in.readUTF(), in.readInt()) != null) throw new IOException("duplicate enchant allow-list key");
        Map<String,PdcValue.Type> pdc = new TreeMap<>();
        for (int n = count(in,32); n > 0; n--) if (pdc.put(in.readUTF(), PdcValue.Type.valueOf(in.readUTF())) != null) throw new IOException("duplicate PDC allow-list key");
        return new TransferPolicy(id, name, repair, durability, enchants, pdc);
    }
    private static int count(DataInputStream in,int maximum) throws IOException {
        int n=in.readInt(); if(n<0 || n>maximum)throw new IOException("output policy collection budget exceeded"); return n;
    }
}
