package sntiago05.rejuv.domain;

/**
 * Teammate statistics together with a player.
 *
 * @param accountId      teammate Steam account ID
 * @param matches        matches played together with the player
 * @param wins           wins together with the player
 * @param winRatePercent win rate percentage together (wins * 100.0 / matches)
 */
public record Teammate(long accountId, int matches, int wins, double winRatePercent) {
}
