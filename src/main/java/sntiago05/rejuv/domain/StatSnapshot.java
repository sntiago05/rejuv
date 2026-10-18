package sntiago05.rejuv.domain;

/**
 * A snapshot of a player's key combat and economy stats at a given timestamp during the match.
 *
 * @param timeS         elapsed game time in seconds
 * @param netWorth      accumulated net worth
 * @param playerDamage  total damage dealt to enemy players
 * @param playerHealing total healing provided
 */
public record StatSnapshot(int timeS, long netWorth, long playerDamage, long playerHealing, SnapshotDetail detail) {

    public StatSnapshot(int timeS, long netWorth, long playerDamage, long playerHealing) {
        this(timeS, netWorth, playerDamage, playerHealing, null);
    }
}
