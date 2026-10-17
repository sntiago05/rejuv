package sntiago05.rejuv.domain;

/** heroName is null when the hero id is not in the heroes asset. The three benchmark values are null when no benchmark exists (heroAvgKda also when the benchmark row has no deaths). */
public record HeroPerformance(int heroId, String heroName, int matches, int wins, double winRatePercent,
                              double kda, Double heroAvgWinRatePercent, Double deltaPoints, Double heroAvgKda) { }
