package vn.ledat.itemupgrader.paper.config;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import vn.ledat.itemupgrader.util.Decimals;

/** Small strict typed reader. Numeric values are never coerced silently to default/zero. */
final class YamlNode {
    private final Map<String, Object> values;
    private final String path;
    YamlNode(Map<String, Object> values, String path) { this.values = Map.copyOf(values); this.path = path; }
    static YamlNode from(Object raw, String path) {
        if (!(raw instanceof Map<?, ?> map)) throw new IllegalArgumentException(path + ": expected mapping");
        Map<String, Object> values = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (!(key instanceof String text) || value == null) throw new IllegalArgumentException(path + ": string keys and non-null values required");
            values.put(text, value);
        });
        return new YamlNode(values, path);
    }
    void allow(String... keys) {
        Set<String> allowed = Set.of(keys);
        for (String key : values.keySet()) if (!allowed.contains(key)) throw new IllegalArgumentException(at(key) + ": unknown key");
    }
    String at(String key) { return path + "." + key; }
    boolean has(String key) { return values.containsKey(key); }
    Object required(String key) {
        if (!has(key)) throw new IllegalArgumentException(at(key) + ": missing key");
        return values.get(key);
    }
    String string(String key) {
        Object raw = required(key);
        if (!(raw instanceof String value)) throw new IllegalArgumentException(at(key) + ": expected string");
        return value;
    }
    String string(String key, String fallback) { return has(key) ? string(key) : fallback; }
    boolean bool(String key) {
        Object raw = required(key);
        if (!(raw instanceof Boolean value)) throw new IllegalArgumentException(at(key) + ": expected boolean");
        return value;
    }
    int integer(String key, int min, int max) {
        Object raw = required(key);
        if (!(raw instanceof Byte || raw instanceof Short || raw instanceof Integer || raw instanceof Long))
            throw new IllegalArgumentException(at(key) + ": expected integer");
        long value = ((Number) raw).longValue();
        if (value < min || value > max) throw new IllegalArgumentException(at(key) + ": must be " + min + ".." + max);
        return (int) value;
    }
    BigDecimal decimal(String key) {
        Object raw = required(key);
        // Decimal values must be YAML strings to avoid YAML -> Double -> BigDecimal loss of precision.
        if (!(raw instanceof String value)) throw new IllegalArgumentException(at(key) + ": quote decimal values, e.g. '90.00'");
        return Decimals.parse(value, at(key));
    }
    YamlNode section(String key) { return from(required(key), at(key)); }
    List<YamlNode> sections(String key) {
        Object raw = required(key);
        if (!(raw instanceof List<?> list)) throw new IllegalArgumentException(at(key) + ": expected list");
        if (list.size() > 10000) throw new IllegalArgumentException(at(key) + ": list too long");
        List<YamlNode> nodes = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) nodes.add(from(list.get(i), at(key) + "[" + i + "]"));
        return List.copyOf(nodes);
    }
    List<String> strings(String key) {
        Object raw = required(key);
        if (!(raw instanceof List<?> list)) throw new IllegalArgumentException(at(key) + ": expected string list");
        if (list.size() > 10000) throw new IllegalArgumentException(at(key) + ": list too long");
        List<String> items = new ArrayList<>();
        for (Object entry : list) {
            if (!(entry instanceof String value)) throw new IllegalArgumentException(at(key) + ": non-string list entry");
            items.add(value);
        }
        return List.copyOf(items);
    }
    List<Integer> integers(String key, int min, int max) {
        Object raw = required(key);
        if (!(raw instanceof List<?> list) || list.size() > 54) throw new IllegalArgumentException(at(key) + ": expected bounded integer list");
        List<Integer> result = new ArrayList<>();
        for (Object entry : list) {
            if (!(entry instanceof Byte || entry instanceof Short || entry instanceof Integer || entry instanceof Long))
                throw new IllegalArgumentException(at(key) + ": expected integer list entry");
            long value = ((Number) entry).longValue();
            if (value < min || value > max) throw new IllegalArgumentException(at(key) + ": entry outside " + min + ".." + max);
            result.add((int) value);
        }
        return List.copyOf(result);
    }
    Map<String, Object> entries() { return values; }
}
