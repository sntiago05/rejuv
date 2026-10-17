package sntiago05.rejuv.domain;

import java.util.List;

/**
 * Summary of a player's top teammates.
 *
 * @param accountId player's Steam account ID
 * @param teammates top teammates (at most 5, empty when no teammate reaches the minimum)
 */
public record MatesSummary(long accountId, List<Teammate> teammates) {

    public MatesSummary {
        teammates = List.copyOf(teammates);
    }
}
