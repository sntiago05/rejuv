package sntiago05.rejuv.domain;

import java.util.List;

/**
 * rankTier is null when the player is unranked (badge 0). rankName is null when unranked
 * or when the tier is missing from the ranks asset. subrank is 0 when unknown.
 * topHeroes is immutable, at most 3 entries, possibly empty.
 */
public record ProfileSummary(long accountId,
                             Integer rankTier, String rankName, int subrank,
                             int recentMatches, int recentWins, int winRatePercent,
                             double avgKills, double avgDeaths, double avgAssists,
                             List<HeroUsage> topHeroes) {

    public ProfileSummary {
        topHeroes = List.copyOf(topHeroes);
    }
}
