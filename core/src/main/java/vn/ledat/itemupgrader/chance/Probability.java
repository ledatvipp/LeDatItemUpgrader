package vn.ledat.itemupgrader.chance;

import java.math.BigDecimal;

/** Integer threshold contract for Phase 4: sample must be unbiased in [0, DENOMINATOR). No RNG here. */
public record Probability(long winningTickets) {
    public static final long DENOMINATOR = 1_000_000_000L;
    public Probability {
        if (winningTickets < 0 || winningTickets > DENOMINATOR) throw new IllegalArgumentException("probability outside [0,1]");
    }
    public BigDecimal fraction() { return BigDecimal.valueOf(winningTickets, 9).stripTrailingZeros(); }
    public BigDecimal percent() { return BigDecimal.valueOf(winningTickets, 7).stripTrailingZeros(); }
    public boolean succeeds(long sample) {
        if (sample < 0 || sample >= DENOMINATOR) throw new IllegalArgumentException("sample outside [0,DENOMINATOR)");
        return sample < winningTickets;
    }
}
