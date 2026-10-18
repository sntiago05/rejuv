package sntiago05.rejuv.domain;

import java.util.Comparator;
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
 * @param playerSlot   player slot number (0..11, null if unknown)
 * @param assignedLane assigned lane number (1..4, null if unknown)
 * @param deaths       death events, ordered by {@code gameTimeS} ascending
 * @param bossDamage   final cumulative boss damage (nullable)
 */
public record PlayerTelemetry(long accountId, int team, int heroId,
                              List<ItemPurchase> items,
                              List<StatSnapshot> snapshots,
                              List<PowerUpBuff> powerUpBuffs,
                              Integer playerSlot,
                              Integer assignedLane,
                              List<DeathEvent> deaths,
                              Long bossDamage) {

    public PlayerTelemetry(long accountId, int team, int heroId,
                           List<ItemPurchase> items,
                           List<StatSnapshot> snapshots,
                           List<PowerUpBuff> powerUpBuffs,
                           Integer playerSlot,
                           Integer assignedLane,
                           List<DeathEvent> deaths) {
        this(accountId, team, heroId, items, snapshots, powerUpBuffs, playerSlot, assignedLane, deaths, null);
    }

    public PlayerTelemetry(long accountId, int team, int heroId,
                           List<ItemPurchase> items,
                           List<StatSnapshot> snapshots,
                           List<PowerUpBuff> powerUpBuffs) {
        this(accountId, team, heroId, items, snapshots, powerUpBuffs, null, null, List.of(), null);
    }

    public PlayerTelemetry {
        items = List.copyOf(items);
        snapshots = List.copyOf(snapshots);
        powerUpBuffs = List.copyOf(powerUpBuffs);
        deaths = deaths == null
                ? List.of()
                : List.copyOf(deaths.stream().sorted(Comparator.comparingInt(DeathEvent::gameTimeS)).toList());
    }
}
