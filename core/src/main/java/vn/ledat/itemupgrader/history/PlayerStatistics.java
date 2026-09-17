package vn.ledat.itemupgrader.history;
import java.util.*;
import java.math.*;
import vn.ledat.itemupgrader.pity.PitySnapshot;
/** Only COMPLETED transactions contribute. An unlucky roll pending settlement is not a completed loss. */
public record PlayerStatistics(UUID playerId,long completed,long wins,long losses) {
    public PlayerStatistics {
        Objects.requireNonNull(playerId);
        if(completed<0||completed>PitySnapshot.MAX_COUNTER||wins<0||losses<0||wins>completed||losses>completed||wins+losses!=completed)
            throw new IllegalArgumentException("invalid statistics totals");
    }
    public static PlayerStatistics empty(UUID player){return new PlayerStatistics(player,0,0,0);}
    public PlayerStatistics complete(boolean success) {
        return new PlayerStatistics(playerId,Math.addExact(completed,1),success?Math.addExact(wins,1):wins,success?losses:Math.addExact(losses,1));
    }
    public BigDecimal winRate(){return completed==0?BigDecimal.ZERO:BigDecimal.valueOf(wins).multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(completed),2,RoundingMode.HALF_UP);}
}
