package sntiago05.rejuv.domain;

import java.util.List;

/**
 * rankTier null = all ranks. All lists immutable and possibly empty.
 * counters: heroes that win most against the target hero (their win rate against it), best first.
 * favorable: heroes the target hero beats most often (the target hero's win rate against them), best first.
 * items: items with the best win rate when the enemy team has the target hero, best first.
 */
public record CounterGuide(int heroId, String heroName, Integer rankTier, List<CounterMatchup> counters,
                           List<CounterMatchup> favorable, List<CounterItem> items) {

    public CounterGuide {
        counters = List.copyOf(counters);
        favorable = List.copyOf(favorable);
        items = List.copyOf(items);
    }
}
