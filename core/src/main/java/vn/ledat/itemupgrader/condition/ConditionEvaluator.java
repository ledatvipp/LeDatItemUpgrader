package vn.ledat.itemupgrader.condition;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;

/** Pure comparisons on detached evidence. Missing and unresolved text fail even a NOT_EQUALS condition. */
public final class ConditionEvaluator {
    public enum Status { SATISFIED, NOT_MATCHED, UNAVAILABLE, UNRESOLVED, INVALID_VALUE, ERROR, BUDGET_EXCEEDED }
    public record Resolution(Status status, String value) {
        public Resolution { Objects.requireNonNull(status); Objects.requireNonNull(value); }
        public static Resolution value(String value) { return new Resolution(Status.SATISFIED, value); }
        public static Resolution missing() { return new Resolution(Status.UNAVAILABLE, ""); }
    }
    public record Evidence(Set<String> satisfied, Map<String, Status> statuses) {
        public Evidence { satisfied = Set.copyOf(satisfied); statuses = Map.copyOf(statuses); }
    }
    public Evidence evaluate(List<ConditionDefinition> definitions, Map<String, Resolution> resolutions) {
        if (definitions.size() > 32 || resolutions.size() > 32) throw new IllegalArgumentException("condition evidence budget exceeded");
        Set<String> ids = new HashSet<>(), satisfied = new HashSet<>(); Map<String, Status> statuses = new HashMap<>();
        for (ConditionDefinition condition : definitions) {
            if (!ids.add(condition.id())) throw new IllegalArgumentException("duplicate condition id");
            Status result = test(condition, resolutions.getOrDefault(condition.placeholder(), Resolution.missing()));
            statuses.put(condition.id(), result);
            if (result == Status.SATISFIED) satisfied.add(condition.id());
        }
        return new Evidence(satisfied, statuses);
    }
    public Status test(ConditionDefinition condition, Resolution resolution) {
        if (resolution.status() != Status.SATISFIED) return resolution.status();
        String actual = resolution.value();
        if (actual.length() > 256 || actual.codePoints().anyMatch(Character::isISOControl)) return Status.INVALID_VALUE;
        if (actual.indexOf('%') >= 0) return Status.UNRESOLVED;
        try {
            int comparison = switch (condition.type()) {
                case NUMBER -> number(actual.strip()).compareTo(number(condition.expected()));
                case TEXT -> actual.compareTo(condition.expected()); // Exact, case-sensitive; no color/format stripping.
                case BOOLEAN -> {
                    if (!actual.equals("true") && !actual.equals("false")) throw new IllegalArgumentException("invalid boolean result");
                    yield actual.compareTo(condition.expected());
                }
            };
            boolean matches = switch (condition.operator()) {
                case EQ -> comparison == 0; case NE -> comparison != 0; case GT -> comparison > 0;
                case GE -> comparison >= 0; case LT -> comparison < 0; case LE -> comparison <= 0;
            };
            return matches ? Status.SATISFIED : Status.NOT_MATCHED;
        } catch (IllegalArgumentException invalid) { return Status.INVALID_VALUE; }
    }
    static BigDecimal number(String raw) {
        if (raw.length() > 28 || !raw.matches("-?(?:0|[1-9][0-9]{0,17})(?:\\.[0-9]{1,8})?"))
            throw new IllegalArgumentException("condition number: plain decimal only, no exponent/color/NaN/locale separators");
        return new BigDecimal(raw);
    }
}
