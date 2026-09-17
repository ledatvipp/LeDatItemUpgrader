package vn.ledat.itemupgrader.pity;
import java.math.BigDecimal;
import java.util.Objects;
import java.io.*;
import vn.ledat.itemupgrader.transaction.model.AttemptPlan;
/** Persisted into journal v3; all progression rules are pinned, not reread from reloadable config at settlement. */
public record PityStamp(String policyId, int epoch, String scope, long version, long failures,
                        BigDecimal incrementPoints, BigDecimal maximumPoints) {
    public PityStamp {
        AttemptPlan.id(policyId); PitySnapshot.validateScope(scope);
        if(epoch<1 || epoch>1_000_000 || version<0 || version>PitySnapshot.MAX_COUNTER || failures<0 || failures>version)
            throw new IllegalArgumentException("invalid pity stamp");
        incrementPoints=points(incrementPoints); maximumPoints=points(maximumPoints);
        if(incrementPoints.signum()<=0 || incrementPoints.compareTo(maximumPoints)>0)
            throw new IllegalArgumentException("invalid pity increment/cap");
    }
    private static BigDecimal points(BigDecimal p) {
        Objects.requireNonNull(p);
        if(p.scale()>6 || p.scale() < -3 || p.precision()>12 || p.signum()<0 || p.compareTo(BigDecimal.valueOf(100))>0)
            throw new IllegalArgumentException("pity percentage points outside 0..100");
        return p.stripTrailingZeros();
    }
    public BigDecimal bonusPoints() { return incrementPoints.multiply(BigDecimal.valueOf(failures)).min(maximumPoints); }
    public boolean matches(PitySnapshot snapshot) {
        return scope.equals(snapshot.scope()) && version==snapshot.version() && failures==snapshot.failures();
    }
    public void write(DataOutputStream out)throws IOException {
        out.writeUTF(policyId);out.writeInt(epoch);out.writeUTF(scope);out.writeLong(version);out.writeLong(failures);
        out.writeUTF(incrementPoints.toPlainString());out.writeUTF(maximumPoints.toPlainString());
    }
    public static PityStamp read(DataInputStream in)throws IOException {
        return new PityStamp(in.readUTF(),in.readInt(),in.readUTF(),in.readLong(),in.readLong(),decimal(in),decimal(in));
    }
    private static BigDecimal decimal(DataInputStream in)throws IOException {
        String s=in.readUTF();if(s.length()>16 || !s.matches("[0-9]+(?:\\.[0-9]+)?"))throw new IOException("invalid pity decimal");
        return new BigDecimal(s);
    }
}
