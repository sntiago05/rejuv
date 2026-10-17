package sntiago05.rejuv.domain;

/** heroName is null when the hero id is not in the heroes asset. */
public record MetaHero(int heroId, String heroName, long matches, double winRatePercent, HeroTier tier) { }
