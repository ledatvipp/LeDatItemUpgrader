package vn.ledat.itemupgrader.output.storage;

import java.io.*;
import java.time.Instant;
import java.util.*;
import java.security.MessageDigest;
import vn.ledat.itemupgrader.output.PreparedOutput;
import vn.ledat.itemupgrader.item.ItemSnapshot;
import vn.ledat.itemupgrader.transaction.storage.JournalCodec;

/** Versioned/checksummed snapshots. Digest detects corruption, not a hostile database administrator. */
public final class OutputCodec {
    public static final int MAX_BYTES=3*1024*1024;
    private static final int MAGIC=0x49554f35;
    private OutputCodec() {}
    public static byte[] encode(PreparedOutput output) {
        try(var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);out.writeInt(1);uuid(out,output.attemptId());uuid(out,output.playerId());out.writeUTF(output.planDigest());
            item(out,output.success());out.writeBoolean(output.failure().isPresent());if(output.failure().isPresent())item(out,output.failure().orElseThrow());
            tokens(out,output.successIdentities());tokens(out,output.failureIdentities());out.writeLong(output.createdAt().getEpochSecond());out.writeInt(output.createdAt().getNano());out.flush();
            byte[] body=bytes.toByteArray();
            if(body.length+32>MAX_BYTES)throw new IllegalArgumentException("prepared output payload budget exceeded");
            bytes.write(java.util.HexFormat.of().parseHex(JournalCodec.digest(body)));return bytes.toByteArray();
        }catch(IOException impossible){throw new IllegalStateException("in-memory output encoding failed",impossible);}
    }
    public static String digest(PreparedOutput output){return JournalCodec.digest(encode(output));}
    public static PreparedOutput decode(byte[] bytes) {
        Objects.requireNonNull(bytes);
        if(bytes.length<100||bytes.length>MAX_BYTES)throw new IllegalArgumentException("invalid output envelope size");
        byte[] body=Arrays.copyOf(bytes,bytes.length-32);
        if(!MessageDigest.isEqual(java.util.HexFormat.of().parseHex(JournalCodec.digest(body)),Arrays.copyOfRange(bytes,body.length,bytes.length)))
            throw new IllegalArgumentException("output checksum mismatch");
        try(var in=new DataInputStream(new ByteArrayInputStream(body))) {
            if(in.readInt()!=MAGIC||in.readInt()!=1)throw new IOException("unsupported output version");
            var attempt=uuid(in);var player=uuid(in);String plan=in.readUTF();var success=item(in);
            Optional<ItemSnapshot> failure=in.readBoolean()?Optional.of(item(in)):Optional.empty();
            var successIds=tokens(in);var failureIds=tokens(in);long seconds=in.readLong();int nanos=in.readInt();
            if(nanos<0||nanos>=1_000_000_000||in.available()!=0)throw new IOException("invalid timestamp/trailing output data");
            return new PreparedOutput(attempt,player,plan,success,failure,successIds,failureIds,Instant.ofEpochSecond(seconds,nanos));
        }catch(IOException|RuntimeException error){throw new IllegalArgumentException("invalid/corrupt output payload",error);}
    }
    private static void item(DataOutputStream out,ItemSnapshot item)throws IOException{byte[] data=JournalCodec.encodeItem(item);out.writeInt(data.length);out.write(data);}
    private static ItemSnapshot item(DataInputStream in)throws IOException{
        int n=in.readInt();if(n<1||n>ItemSnapshot.HARD_MAX_BYTES+65536||n>in.available())throw new IOException("invalid output item length");return JournalCodec.decodeItem(in.readNBytes(n));
    }
    private static void tokens(DataOutputStream out,Set<String> values)throws IOException{out.writeInt(values.size());for(String value:new TreeSet<>(values))out.writeUTF(value);}
    private static Set<String> tokens(DataInputStream in)throws IOException{
        int n=in.readInt();if(n<0||n>16)throw new IOException("identity count limit");Set<String> result=new TreeSet<>();
        for(int j=0;j<n;j++)if(!result.add(in.readUTF()))throw new IOException("duplicate output identity");return result;
    }
    private static void uuid(DataOutputStream out,UUID value)throws IOException{out.writeLong(value.getMostSignificantBits());out.writeLong(value.getLeastSignificantBits());}
    private static UUID uuid(DataInputStream in)throws IOException{return new UUID(in.readLong(),in.readLong());}
}
