package sntiago05.rejuv.domain;

/**
 * Detailed combat and economy statistics for a stat snapshot.
 * All values are cumulative since the match start.
 */
public record SnapshotDetail(int kills, int deaths, int assists,
                             int creepKills, int possibleCreeps,
                             int shotsHit, int shotsMissed,
                             int denies,
                             long playerDamageTaken,
                             long goldLaneCreep, long goldNeutralCreep,
                             long goldDenied, long goldDeathLoss) {
}
