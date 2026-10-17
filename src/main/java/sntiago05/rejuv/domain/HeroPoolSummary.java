package sntiago05.rejuv.domain;

import java.util.List;

/** rankTier null = the player is unranked, so the benchmark is all ranks. topHeroes is empty when the player has no match in the window. best / weakest are null when not available. */
public record HeroPoolSummary(long accountId, Integer rankTier, List<HeroPerformance> topHeroes,
                              HeroPerformance best, HeroPerformance weakest) {
    public HeroPoolSummary {
        topHeroes = List.copyOf(topHeroes);
    }
}
