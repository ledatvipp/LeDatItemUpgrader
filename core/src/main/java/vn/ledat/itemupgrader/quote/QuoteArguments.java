package vn.ledat.itemupgrader.quote;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** /upgrader quote <target-id> [profile-id|default] [boost1,boost2|none]. */
public final class QuoteArguments {
    private QuoteArguments() {}
    public static QuoteRequest parse(List<String> args, UUID session, int sourceSlot) {
        if (args.isEmpty() || args.size() > 3 || args.stream().anyMatch(text -> text == null || text.length() > 600 || text.codePoints().anyMatch(Character::isISOControl)))
            throw new IllegalArgumentException("invalid quote arguments");
        String profile = args.size() < 2 || args.get(1).equals("default") ? "" : args.get(1);
        List<String> boosts = args.size() < 3 || args.get(2).equals("none") ? List.of() : Arrays.asList(args.get(2).split(",", -1));
        return new QuoteRequest(session, args.getFirst(), profile, boosts, sourceSlot);
    }
}
