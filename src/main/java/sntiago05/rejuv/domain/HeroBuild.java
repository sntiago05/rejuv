package sntiago05.rejuv.domain;

import java.util.List;
import java.util.Objects;

/**
 * rankTier null = all ranks. phases immutable, ascending column, only non-empty phases.
 * abilityOrder: ability names in purchase order ("#<id>" for unknown ids, see Behavior), empty when no data.
 * abilityOrderMatches: matches of the chosen order, 0 when abilityOrder is empty.
 */
public record HeroBuild(int heroId, String heroName, Integer rankTier, List<BuildPhase> phases,
                        List<String> abilityOrder, long abilityOrderMatches) {

    public HeroBuild {
        Objects.requireNonNull(heroName, "heroName must not be null");
        phases = phases == null ? List.of() : List.copyOf(phases);
        abilityOrder = abilityOrder == null ? List.of() : List.copyOf(abilityOrder);
    }
}
