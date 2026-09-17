package vn.ledat.itemupgrader.storage.management;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record StorageArguments(Action action, Optional<UUID> after) {
    public enum Action { STATUS, RECHECK, RECOVERY }
    public StorageArguments {
        Objects.requireNonNull(action); Objects.requireNonNull(after);
        if (action != Action.RECOVERY && after.isPresent()) throw new IllegalArgumentException("cursor requires recovery");
    }
    public static StorageArguments parse(List<String> arguments) {
        if (arguments.size() > 2 || arguments.stream().anyMatch(s -> s == null || s.length() > 36 || s.codePoints().anyMatch(Character::isISOControl)))
            throw new IllegalArgumentException("storage command arguments");
        var action = arguments.isEmpty() ? Action.STATUS : Action.valueOf(arguments.getFirst().toUpperCase(Locale.ROOT));
        Optional<UUID> cursor = Optional.empty();
        if (arguments.size() == 2) {
            String text = arguments.get(1);
            if (!text.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"))
                throw new IllegalArgumentException("invalid recovery cursor");
            cursor = Optional.of(UUID.fromString(text));
        }
        return new StorageArguments(action, cursor);
    }
}
