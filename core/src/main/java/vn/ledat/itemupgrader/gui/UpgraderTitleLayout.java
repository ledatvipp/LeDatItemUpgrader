package vn.ledat.itemupgrader.gui;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.OptionalInt;
import vn.ledat.itemupgrader.chance.Probability;

/** Bounded resource-pack title layout and deterministic, cosmetic reveal motion. */
public record UpgraderTitleLayout(boolean enabled, int visitsPerTick, int selectedHoldTicks, int barFrames, int barFillTicks,
                                  int arrowStartShift, int arrowEndShift, int arrowDurationTicks,
                                  int arrowSoundIntervalTicks, String emptyTitle, String selectedTitle,
                                  String barTitle, String readyTitle) {
    private static final String BASE = "<white><shift:-8><image:thanhviet:menu/item_upgrade/main><shift:-183>"
            + "%viethud_thanhviet:bossbar/35_80_CENTER_<black>GIÁ TRỊ%<shift:-80>"
            + "%viethud_thanhviet:bossbar/44_80_CENTER_<black>{source_value}%<shift:28>"
            + "%viethud_thanhviet:bossbar/35_80_CENTER_<black>GIÁ TRỊ%<shift:-80>"
            + "%viethud_thanhviet:bossbar/44_80_CENTER_<black>{target_value}%";
    private static final String AMOUNT = "<shift:-63><image:thanhviet:menu/item_upgrade/amount{amount_state}><shift:-36>"
            + "%viethud_thanhviet:bossbar/54_24_CENTER_<white>{amount}%";
    private static final String CHANCE_BAR = "<shift:-89>%viethud_thanhviet:itemupgrade_40_CENTER_<white>{chance}{percent}%"
            + "<shift:-100><image:thanhviet:menu/item_upgrade/bar_full:{bar}:0>";
    public record Frame(int barIndex, OptionalInt arrowShift, boolean pulse, boolean complete) {
        public Frame {
            Objects.requireNonNull(arrowShift);
            if (barIndex < 0) throw new IllegalArgumentException("negative bar index");
        }
    }

    public UpgraderTitleLayout {
        if (visitsPerTick < 1 || visitsPerTick > 128 || selectedHoldTicks < 0 || selectedHoldTicks > 100 || barFrames < 2 || barFrames > 512
                || barFillTicks < 1 || barFillTicks > 200 || arrowDurationTicks < 1 || arrowDurationTicks > 400
                || arrowSoundIntervalTicks < 1 || arrowSoundIntervalTicks > 20
                || arrowEndShift <= arrowStartShift || arrowStartShift < -4096 || arrowEndShift > 4096)
            throw new IllegalArgumentException("invalid upgrader title animation bounds");
        for (String title : new String[]{emptyTitle, selectedTitle, barTitle, readyTitle}) {
            Objects.requireNonNull(title);
            if (title.isBlank() || title.length() > 8192) throw new IllegalArgumentException("invalid upgrader title template");
        }
    }

    public int barIndex(Probability probability) {
        Objects.requireNonNull(probability);
        long numerator = probability.winningTickets() * (barFrames - 1L) + Probability.DENOMINATOR / 2L;
        return (int) (numerator / Probability.DENOMINATOR);
    }
    public int sampleBar(int sample) {
        if(sample<0||sample>=Probability.DENOMINATOR)throw new IllegalArgumentException("sample outside ticket range");
        return (int)((sample*(barFrames-1L)+(Probability.DENOMINATOR-1L)/2L)/(Probability.DENOMINATOR-1L));
    }

    /** Stable-width HUD text; barIndex still uses the unrounded integer-ticket probability. */
    public static String percentText(Probability probability) {
        Objects.requireNonNull(probability);
        return probability.percent().setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    public static int amountState(int selected, int available) {
        if (selected < 1 || available < 0 || selected > Math.max(1, available))
            throw new IllegalArgumentException("invalid selected/available amount");
        if (available <= 1) return 0;
        if (selected == 1) return 1;
        if (selected == available) return 2;
        return 3;
    }

    /** Linear bar fill followed by a cubic ease-out arrow sweep. Arrow is absent during fill. */
    public Frame frame(int targetBarIndex, long elapsedTicks) {
        if (targetBarIndex < 0 || targetBarIndex >= barFrames || elapsedTicks < 0)
            throw new IllegalArgumentException("title frame outside bounds");
        if (elapsedTicks < barFillTicks) {
            int bar = (int) Math.min(targetBarIndex,
                    (targetBarIndex * elapsedTicks + barFillTicks / 2L) / barFillTicks);
            return new Frame(bar, OptionalInt.empty(), false, false);
        }
        long arrowTick = Math.min(arrowDurationTicks, elapsedTicks - barFillTicks);
        long remaining = arrowDurationTicks - arrowTick;
        long denominator = (long) arrowDurationTicks * arrowDurationTicks * arrowDurationTicks;
        long remainingCube = remaining * remaining * remaining;
        int distance = arrowEndShift - arrowStartShift;
        int travelled = (int) ((distance * (denominator - remainingCube) + denominator / 2L) / denominator);
        int shift = arrowStartShift + travelled;
        boolean complete = arrowTick >= arrowDurationTicks;
        boolean pulse = !complete && arrowTick % arrowSoundIntervalTicks == 0;
        return new Frame(targetBarIndex, OptionalInt.of(shift), pulse, complete);
    }

    /**
     * Click-triggered cosmetic roll. The pointer alternates between both halves of the bar,
     * changes direction quickly at first, then spends progressively longer on each leg before
     * easing into the configured chance position. The seed affects presentation only.
     */
    public Frame rollFrame(int targetBarIndex, long elapsedTicks, long seed) {
        return rollFrame(targetBarIndex,targetBarIndex,elapsedTicks,seed);
    }

    public Frame rollFrame(int targetBarIndex, int landingBarIndex, long elapsedTicks, long seed) {
        if (targetBarIndex < 0 || targetBarIndex >= barFrames || landingBarIndex < 0 || landingBarIndex >= barFrames || elapsedTicks < 0)
            throw new IllegalArgumentException("title roll frame outside bounds");
        if (elapsedTicks < barFillTicks) {
            int bar = (int) Math.min(targetBarIndex,
                    (targetBarIndex * elapsedTicks + barFillTicks / 2L) / barFillTicks);
            return new Frame(bar, OptionalInt.empty(), false, false);
        }
        long arrowTick = Math.min(arrowDurationTicks, elapsedTicks - barFillTicks);
        int distance = arrowEndShift - arrowStartShift;
        int landing = arrowStartShift + (int) ((distance * (long) landingBarIndex + (barFrames - 1L) / 2L) / (barFrames - 1L));
        if (arrowTick >= arrowDurationTicks)
            return new Frame(targetBarIndex, OptionalInt.of(landing), false, true);

        int legs = Math.max(4, Math.min(12, arrowDurationTicks / 3));
        long settleTicks = Math.max(2, arrowDurationTicks / 4L);
        long roamTicks = Math.max(1, arrowDurationTicks - settleTicks);
        int shift;
        if (arrowTick < roamTicks) {
            int leg = 0;
            while (leg + 1 < legs && legBoundary(leg + 1, legs, roamTicks) <= arrowTick) leg++;
            long start = legBoundary(leg, legs, roamTicks);
            long end = Math.max(start + 1, legBoundary(leg + 1, legs, roamTicks));
            long local = arrowTick - start;
            int from = rollPosition(seed, leg, distance);
            int to = rollPosition(seed, leg + 1, distance);
            shift = from + (int) (((long) (to - from) * local + (end - start) / 2L) / (end - start));
        } else {
            int from = rollPosition(seed, legs, distance);
            long local = arrowTick - roamTicks;
            long duration = Math.max(1, arrowDurationTicks - roamTicks);
            long remaining = duration - local;
            long denominator = duration * duration * duration;
            long travelled = denominator - remaining * remaining * remaining;
            shift = from + (int) (((long) (landing - from) * travelled + denominator / 2L) / denominator);
        }
        boolean pulse = arrowTick % arrowSoundIntervalTicks == 0;
        return new Frame(targetBarIndex, OptionalInt.of(shift), pulse, false);
    }

    private static long legBoundary(int leg, int legs, long duration) {
        return duration * (long) leg * leg / ((long) legs * legs);
    }

    private int rollPosition(long seed, int leg, int distance) {
        long mixed = seed + 0x9E3779B97F4A7C15L * (leg + 1L);
        mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
        mixed ^= mixed >>> 31;
        int half = Math.max(1, distance / 2);
        int offset = (int) Math.floorMod(mixed, half + 1L);
        return arrowStartShift + ((leg & 1) == 0 ? offset : distance - offset);
    }

    public static String compactValue(BigDecimal value) {
        Objects.requireNonNull(value);
        BigDecimal absolute = value.abs();
        String suffix = "";
        BigDecimal divisor = BigDecimal.ONE;
        if (absolute.compareTo(new BigDecimal("1000000000")) >= 0) { suffix = "B"; divisor = new BigDecimal("1000000000"); }
        else if (absolute.compareTo(new BigDecimal("1000000")) >= 0) { suffix = "M"; divisor = new BigDecimal("1000000"); }
        else if (absolute.compareTo(new BigDecimal("1000")) >= 0) { suffix = "K"; divisor = new BigDecimal("1000"); }
        BigDecimal shown = value.divide(divisor, suffix.isEmpty() ? 8 : 1, RoundingMode.HALF_UP).stripTrailingZeros();
        return shown.toPlainString() + suffix;
    }

    /** Compatibility default for deployed config files created before title-display existed. */
    public static UpgraderTitleLayout standard() {
        String empty = "<white><shift:-8><image:thanhviet:menu/item_upgrade/main><shift:-183>"
                + "%viethud_thanhviet:bossbar/35_80_CENTER_<black>CHƯA CÓ%<shift:-80>"
                + "%viethud_thanhviet:bossbar/44_80_CENTER_<black>ITEM%<shift:28>"
                + "%viethud_thanhviet:bossbar/35_80_CENTER_<black>CHƯA CÓ%<shift:-80>"
                + "%viethud_thanhviet:bossbar/44_80_CENTER_<black>ITEM%";
        return new UpgraderTitleLayout(true, 32, 8, 154, 24, -157, -4, 72, 2,
                empty, BASE + AMOUNT, BASE + AMOUNT + CHANCE_BAR,
                BASE + AMOUNT + CHANCE_BAR + "<shift:{arrow_shift}><image:thanhviet:menu/item_upgrade/arrow>");
    }
}
