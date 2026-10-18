package sntiago05.rejuv.domain;

import java.util.List;

/**
 * Extra presentation data for the /analyze result, built from match telemetry (no AI involved).
 *
 * @param items final build of the player; never null
 * @param chart chart series, or null when they cannot be built
 */
public record MatchVisuals(List<PurchasedItem> items, ChartData chart) {

    public MatchVisuals {
        items = List.copyOf(items);
    }

    /** Item id and name are always set; items without a known name are dropped by the service. */
    public record PurchasedItem(long itemId, String name, int boughtAtS) { }

    /**
     * Parallel lists, same length n >= 2, ascending timeS. soulLead is the own team total net worth minus the enemy
     * team total; playerNetWorth is the player; teammatesAvgNetWorth is the average of the other players of the
     * player's team (empty when the player has no teammate in the telemetry).
     */
    public record ChartData(List<Integer> timesS, List<Long> soulLead, List<Long> playerNetWorth,
                            List<Long> teammatesAvgNetWorth) { }
}
