package sntiago05.rejuv.domain;

import java.util.List;

/** rankTier null = all ranks. heroes immutable, sorted by winRatePercent desc, then heroId asc; may be empty. */
public record MetaTierList(Integer rankTier, int minMatches, List<MetaHero> heroes) {

    public MetaTierList {
        heroes = List.copyOf(heroes);
    }
}
