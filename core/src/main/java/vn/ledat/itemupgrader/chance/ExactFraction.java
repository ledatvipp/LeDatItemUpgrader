package vn.ledat.itemupgrader.chance;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/** Package-private exact rational arithmetic. Only bounded, validated rule inputs reach this type. */
record ExactFraction(BigInteger numerator, BigInteger denominator) implements Comparable<ExactFraction> {
    static final ExactFraction ZERO = of(BigDecimal.ZERO);
    static final ExactFraction ONE = of(BigDecimal.ONE);
    ExactFraction {
        if (denominator.signum() <= 0) throw new IllegalArgumentException("nonpositive denominator");
        BigInteger gcd = numerator.gcd(denominator);
        numerator = numerator.divide(gcd); denominator = denominator.divide(gcd);
    }
    static ExactFraction of(BigDecimal number) {
        return number.scale() >= 0
                ? new ExactFraction(number.unscaledValue(), BigInteger.TEN.pow(number.scale()))
                : new ExactFraction(number.unscaledValue().multiply(BigInteger.TEN.pow(-number.scale())), BigInteger.ONE);
    }
    ExactFraction add(ExactFraction other) { return new ExactFraction(numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)), denominator.multiply(other.denominator)); }
    ExactFraction subtract(ExactFraction other) { return add(new ExactFraction(other.numerator.negate(), other.denominator)); }
    ExactFraction multiply(ExactFraction other) { return new ExactFraction(numerator.multiply(other.numerator), denominator.multiply(other.denominator)); }
    ExactFraction divide(ExactFraction other) {
        if (other.numerator.signum() <= 0) throw new IllegalArgumentException("nonpositive divisor");
        return new ExactFraction(numerator.multiply(other.denominator), denominator.multiply(other.numerator));
    }
    ExactFraction power(int exponent) { return new ExactFraction(numerator.pow(exponent), denominator.pow(exponent)); }
    @Override public int compareTo(ExactFraction other) { return numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator)); }
    BigDecimal decimal(int scale) { return new BigDecimal(numerator).divide(new BigDecimal(denominator), scale, RoundingMode.DOWN).stripTrailingZeros(); }
    long floorTickets() { return numerator.multiply(BigInteger.valueOf(Probability.DENOMINATOR)).divide(denominator).longValueExact(); }
    long ceilTickets() {
        BigInteger[] qr = numerator.multiply(BigInteger.valueOf(Probability.DENOMINATOR)).divideAndRemainder(denominator);
        return qr[0].add(qr[1].signum() == 0 ? BigInteger.ZERO : BigInteger.ONE).longValueExact();
    }
}
