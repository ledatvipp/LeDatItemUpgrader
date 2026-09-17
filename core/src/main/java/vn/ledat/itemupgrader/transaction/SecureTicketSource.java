package vn.ledat.itemupgrader.transaction;

import java.security.SecureRandom;
import vn.ledat.itemupgrader.chance.Probability;

/** No player/timing seed, modulo mapping, Math.random or floating comparison. Worker-owned instance. */
public final class SecureTicketSource implements TicketSource {
    private final SecureRandom random = new SecureRandom();
    @Override public long draw() { return random.nextInt(Math.toIntExact(Probability.DENOMINATOR)); }
}
