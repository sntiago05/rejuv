package sntiago05.rejuv.domain;

/** One match of the player's cached history. heroName is null when the hero id is not in the heroes asset. */
public record MatchSummary(long matchId, int heroId, String heroName, long startTimeEpochS, int durationS,
                           boolean win, int kills, int deaths, int assists) { }
