package vn.ledat.itemupgrader.chance;

import java.math.BigDecimal;
import java.util.List;
import vn.ledat.itemupgrader.quote.RuleValidation;

/** Declarative strategies only: no script, arbitrary expression, floating pow, or reflection. */
public sealed interface ChanceFormula permits ChanceFormula.Ratio, ChanceFormula.Power, ChanceFormula.Table, ChanceFormula.Curve {
    record Ratio(BigDecimal multiplier) implements ChanceFormula {
        public Ratio { multiplier = RuleValidation.multiplier(multiplier, "ratio.multiplier"); }
    }
    record Power(BigDecimal multiplier, int exponent) implements ChanceFormula {
        public Power {
            multiplier = RuleValidation.multiplier(multiplier, "power.multiplier");
            if (exponent < 1 || exponent > 8) throw new IllegalArgumentException("power.exponent: require integer 1..8");
        }
    }
    record Point(BigDecimal ratio, BigDecimal percent) {
        public Point {
            ratio = RuleValidation.bounded(ratio, BigDecimal.ONE, "point.ratio");
            percent = RuleValidation.percent(percent, "point.percent");
        }
    }
    /** Thresholds are lower-inclusive. First threshold must be zero. No silent sorting/duplicate threshold. */
    record Table(List<Point> points) implements ChanceFormula {
        public Table { points = validatePoints(points, false); }
    }
    /** Piecewise linear, monotone curve. Both endpoints zero-ratio and one-ratio are required. */
    record Curve(List<Point> points) implements ChanceFormula {
        public Curve { points = validatePoints(points, true); }
    }
    private static List<Point> validatePoints(List<Point> input, boolean curve) {
        List<Point> points = List.copyOf(input);
        if (points.size() < (curve ? 2 : 1) || points.size() > 64) throw new IllegalArgumentException("formula points: invalid count");
        if (points.getFirst().ratio().signum() != 0) throw new IllegalArgumentException("formula must start at ratio 0");
        for (int i = 1; i < points.size(); i++) {
            if (points.get(i).ratio().compareTo(points.get(i - 1).ratio()) <= 0)
                throw new IllegalArgumentException("formula ratios must be strictly increasing");
            if (points.get(i).percent().compareTo(points.get(i - 1).percent()) < 0)
                throw new IllegalArgumentException("formula chance must be nondecreasing");
        }
        if (curve && points.getLast().ratio().compareTo(BigDecimal.ONE) != 0)
            throw new IllegalArgumentException("curve must end at ratio 1");
        return points;
    }
}
