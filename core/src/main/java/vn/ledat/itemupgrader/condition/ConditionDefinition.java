package vn.ledat.itemupgrader.condition;

import java.util.Objects;
import vn.ledat.itemupgrader.quote.RuleValidation;

/** Only a complete configured placeholder, one typed comparison and a literal. No scripting/regex. */
public record ConditionDefinition(String id, String placeholder, Type type, Operator operator, String expected) {
    public enum Type { NUMBER, TEXT, BOOLEAN }
    public enum Operator { EQ, NE, GT, GE, LT, LE }
    public ConditionDefinition {
        RuleValidation.id(id, "condition.id"); Objects.requireNonNull(type); Objects.requireNonNull(operator);
        if (placeholder == null || placeholder.length() > 128 || !placeholder.matches("%[A-Za-z0-9]+_[A-Za-z0-9_.:/-]+%")
                || placeholder.startsWith("%rel_")) throw new IllegalArgumentException("condition.placeholder: expected one non-relational placeholder");
        if (expected == null || expected.length() > 256 || expected.indexOf('%') >= 0 || expected.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("condition.expected: invalid literal");
        if (type != Type.NUMBER && operator != Operator.EQ && operator != Operator.NE)
            throw new IllegalArgumentException("ordered comparisons require NUMBER");
        if (type == Type.NUMBER) ConditionEvaluator.number(expected);
        if (type == Type.BOOLEAN && !expected.equals("true") && !expected.equals("false"))
            throw new IllegalArgumentException("boolean expected must be 'true' or 'false'");
    }
}
