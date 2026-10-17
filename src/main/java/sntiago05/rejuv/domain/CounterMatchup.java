package sntiago05.rejuv.domain;

/** heroName is null when the hero id is not in the heroes asset. winRatePercent is unrounded. */
public record CounterMatchup(int heroId, String heroName, long matches, double winRatePercent) {
}
