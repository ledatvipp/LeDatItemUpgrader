package vn.ledat.itemupgrader.pity;
import java.util.Objects;
import java.util.UUID;
/** Authoritative read evidence. A database error is NOT an empty row. Never construct from placeholder/cache text. */
public record PitySnapshot(UUID playerId, String scope, long version, long failures) {
    public static final long MAX_COUNTER = 1_000_000_000_000L;
    public PitySnapshot {
        Objects.requireNonNull(playerId); validateScope(scope);
        if(version<0 || version>MAX_COUNTER || failures<0 || failures>version)
            throw new IllegalArgumentException("invalid pity counter/version");
    }
    public static String validateScope(String scope) {
        if(scope==null || !scope.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("invalid pity scope hash");
        return scope;
    }
    public static PitySnapshot empty(UUID player,String scope) { return new PitySnapshot(player,scope,0,0); }
    public PitySnapshot complete(boolean success) {
        return new PitySnapshot(playerId,scope,Math.addExact(version,1),success?0:Math.addExact(failures,1));
    }
}
