package sntiago05.rejuv.domain;

/**
 * An event recording a player death during a match.
 *
 * @param gameTimeS     elapsed game time in seconds when the death occurred
 * @param killerSlot    player slot of the killer, if known (null otherwise)
 * @param timeToKillS   time in seconds the killer spent dealing damage (null if not provided)
 * @param respawnS      duration spent dead before respawning (API death_duration_s)
 */
public record DeathEvent(int gameTimeS, Integer killerSlot, Double timeToKillS, int respawnS) {
}
