package sntiago05.rejuv.domain;

import java.util.List;

/**
 * Detailed match telemetry for a single player in a match.
 *
 * @param accountId    Steam account ID of the player
 * @param team         team identifier (e.g. 0 or 1)
 * @param heroId       hero identifier
 * @param items        items purchased, ordered by {@code boughtAtS} ascending
 * @param snapshots    stat snapshots over time, ordered by {@code timeS} ascending
 * @param powerUpBuffs power-up buffs acquired by the player
 */
public record PlayerTelemetry(long accountId, int team, int heroId,
                              List<ItemPurchase> items,
                              List<StatSnapshot> snapshots,
                              List<PowerUpBuff> powerUpBuffs) {

    public PlayerTelemetry {
        items = List.copyOf(items);
        snapshots = List.copyOf(snapshots);
        powerUpBuffs = List.copyOf(powerUpBuffs);
    }
}
