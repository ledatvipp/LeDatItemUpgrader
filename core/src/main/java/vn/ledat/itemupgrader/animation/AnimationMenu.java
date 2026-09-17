package vn.ledat.itemupgrader.animation;

import java.util.*;
import vn.ledat.itemupgrader.gui.MenuDefinition;

/** A matrix with an explicit ordered track; all icons are cosmetic configured items, never source/target snapshots. */
public record AnimationMenu(String title, List<String> matrix, Map<Character, Role> symbols,
        List<Integer> trackOrder, Map<Palette, MenuDefinition.Element> icons) {
    public enum Role { FILLER, TRACK, STATUS, BADGE, SKIP, CLOSE }
    public enum Palette { FILLER, TRACK, MARKER, WIN, LOSS, STATUS, BADGE, SKIP, CLOSE }
    public AnimationMenu {
        Objects.requireNonNull(title);
        matrix = List.copyOf(matrix); symbols = Map.copyOf(symbols); trackOrder = List.copyOf(trackOrder); icons = Map.copyOf(icons);
        if (title.length() > 1024 || matrix.isEmpty() || matrix.size() > 6) throw new IllegalArgumentException("invalid animation menu header");
        Map<Role, List<Integer>> positions = positions(matrix, symbols);
        if (positions.getOrDefault(Role.FILLER, List.of()).isEmpty()) throw new IllegalArgumentException("animation menu needs filler");
        for (Role role : List.of(Role.STATUS, Role.BADGE, Role.SKIP, Role.CLOSE))
            if (positions.getOrDefault(role, List.of()).size() != 1) throw new IllegalArgumentException("animation menu needs exactly one " + role);
        var tracks = positions.getOrDefault(Role.TRACK, List.of());
        if (trackOrder.size() < 4 || trackOrder.size() > 36 || new HashSet<>(trackOrder).size() != trackOrder.size()
                || !new HashSet<>(trackOrder).equals(new HashSet<>(tracks)))
            throw new IllegalArgumentException("track-order must name every TRACK slot exactly once (4..36)");
        if (!icons.keySet().equals(Set.of(Palette.values()))) throw new IllegalArgumentException("incomplete animation palette");
        for (var icon : icons.values()) {
            if (icon.action() != MenuDefinition.Action.NONE || icon.role() != MenuDefinition.Role.FILLER || !icon.argument().isEmpty())
                throw new IllegalArgumentException("animation palette cannot carry gameplay actions");
        }
    }
    public int size() { return matrix.size() * 9; }
    public Map<Integer, Role> slots() {
        Map<Integer, Role> slots = new LinkedHashMap<>();
        positions(matrix, symbols).forEach((role, list) -> list.forEach(slot -> slots.put(slot, role)));
        return Map.copyOf(slots);
    }
    public Map<Integer, Palette> frame(AnimationTimeline.Frame frame) {
        if (frame.markerIndex() >= trackOrder.size()) throw new IllegalArgumentException("marker outside route");
        Map<Integer, Palette> result = new HashMap<>();
        Palette reveal = frame.visibleOutcome().map(o -> o == AnimationRequest.Outcome.WIN ? Palette.WIN : Palette.LOSS).orElse(Palette.STATUS);
        slots().forEach((slot, role) -> result.put(slot, switch (role) {
            case FILLER -> Palette.FILLER; case TRACK -> Palette.TRACK;
            case STATUS -> reveal; case BADGE -> Palette.BADGE; case SKIP -> Palette.SKIP; case CLOSE -> Palette.CLOSE;
        }));
        result.put(trackOrder.get(frame.markerIndex()), frame.visibleOutcome().isPresent() ? reveal : Palette.MARKER);
        return Map.copyOf(result);
    }
    private static Map<Role, List<Integer>> positions(List<String> matrix, Map<Character, Role> symbols) {
        Map<Role, List<Integer>> positions = new EnumMap<>(Role.class); int slot = 0;
        for (String row : matrix) {
            if (row.length() != 9) throw new IllegalArgumentException("animation matrix needs nine columns");
            for (char symbol : row.toCharArray()) {
                if (symbol < 33 || symbol > 126 || !symbols.containsKey(symbol)) throw new IllegalArgumentException("undefined/non-ASCII animation symbol");
                positions.computeIfAbsent(symbols.get(symbol), ignored -> new ArrayList<>()).add(slot++);
            }
        }
        if (symbols.keySet().stream().anyMatch(c -> c < 33 || c > 126)) throw new IllegalArgumentException("invalid symbol definition");
        return positions;
    }
}
