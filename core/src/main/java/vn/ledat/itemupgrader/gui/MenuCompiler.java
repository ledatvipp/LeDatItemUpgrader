package vn.ledat.itemupgrader.gui;

import java.util.LinkedHashMap;
import java.util.Map;

public final class MenuCompiler {
    public record CompiledMenu(String id, String title, int size, Map<Integer, MenuDefinition.Element> slots, int sourceSlot) {
        public CompiledMenu {
            if (id == null || !id.matches("[a-z0-9_-]{1,64}") || title == null || title.length() > 2048
                    || size < 9 || size > 54 || size % 9 != 0) throw new IllegalArgumentException("invalid compiled menu header");
            slots = Map.copyOf(slots);
            if (slots.keySet().stream().anyMatch(slot -> slot == null || slot < 0 || slot >= size))
                throw new IllegalArgumentException("compiled menu slot outside inventory");
            int foundSource = -1;
            for (int slot = 0; slot < size; slot++) {
                var element = slots.get(slot);
                if (element == null) continue;
                if (element.role() == MenuDefinition.Role.SOURCE_INPUT) {
                    if (foundSource != -1) throw new IllegalArgumentException("multiple compiled source selectors");
                    foundSource = slot;
                }
            }
            if (sourceSlot != foundSource) throw new IllegalArgumentException("compiled source selector index mismatch");
        }
    }
    public CompiledMenu compile(MenuDefinition definition, boolean requireSource) {
        if (definition.matrix().isEmpty() || definition.matrix().size() > 6)
            throw new IllegalArgumentException("menus." + definition.id() + ".matrix: require 1..6 rows");
        Map<Integer, MenuDefinition.Element> slots = new LinkedHashMap<>();
        int source = -1;
        int row = 0;
        for (String line : definition.matrix()) {
            if (line.length() != 9) throw new IllegalArgumentException("menus." + definition.id() + ".matrix[" + row + "]: require 9 ASCII symbols");
            for (int column = 0; column < 9; column++) {
                char symbol = line.charAt(column);
                if (symbol == '.') continue; // Reserved empty inventory slot; no ItemStack is rendered.
                if (symbol < 33 || symbol > 126) throw new IllegalArgumentException("menu symbols must be printable non-space ASCII");
                var element = definition.symbols().get(symbol);
                if (element == null) throw new IllegalArgumentException("undefined symbol: " + symbol);
                int slot = row * 9 + column;
                if (element.role() == MenuDefinition.Role.SOURCE_INPUT) {
                    if (source != -1) throw new IllegalArgumentException("source input must appear exactly once; use SOURCE_PREVIEW for mirrors");
                    source = slot;
                }
                slots.put(slot, element);
            }
            row++;
        }
        if (requireSource && source == -1) throw new IllegalArgumentException("main menu needs a source input");
        return new CompiledMenu(definition.id(), definition.title(), row * 9, slots, source);
    }
}
