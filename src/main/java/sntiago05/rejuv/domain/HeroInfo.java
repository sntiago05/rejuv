package sntiago05.rejuv.domain;

import java.util.List;

/**
 * What {@code /heroinfo} shows about one hero. Optional parts are null (or empty lists) when the API has no value.
 *
 * @param lore     text already cut to a readable length
 * @param playable true when the hero can be picked in a normal match right now
 * @param stats    base stats in display order; only stats the API returned
 */
public record HeroInfo(int id, String name, String role, String playstyle, String lore, String heroType,
                       Integer complexity, String gunTag, List<String> tags, String colorHex, String iconUrl,
                       boolean playable, List<Stat> stats) {

    public HeroInfo {
        tags = tags == null ? List.of() : List.copyOf(tags);
        stats = stats == null ? List.of() : List.copyOf(stats);
    }

    /** One base stat: the API key (for example {@code max_health}) and its value. */
    public record Stat(String key, double value) {
    }
}
