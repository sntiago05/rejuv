package sntiago05.rejuv.domain;

/** heroName is null when the hero id is not in the heroes asset. */
public record HeroUsage(int heroId, String heroName, int matches) {
}
