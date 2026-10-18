package sntiago05.rejuv.domain;

import java.util.List;
import java.util.Map;

/**
 * Summary of a player's top teammates.
 *
 * @param accountId player's Steam account ID
 * @param teammates top teammates (at most 5, empty when no teammate reaches the minimum)
 * @param names Steam persona names keyed by teammate account ID; best effort, missing entries mean unknown name
 */
public record MatesSummary(long accountId, List<Teammate> teammates, Map<Long, String> names) {

    public MatesSummary {
        teammates = List.copyOf(teammates);
        names = names == null ? Map.of() : Map.copyOf(names);
    }
}
