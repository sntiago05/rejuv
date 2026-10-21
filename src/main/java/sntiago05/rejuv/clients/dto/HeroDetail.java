package sntiago05.rejuv.clients.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One hero from {@code GET /v1/assets/heroes/by-name/{name}}. Only the fields Rejuv shows are mapped.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HeroDetail(@JsonProperty("id") int id,
                         @JsonProperty("name") String name,
                         @JsonProperty("description") Description description,
                         @JsonProperty("hero_type") String heroType,
                         @JsonProperty("complexity") Integer complexity,
                         @JsonProperty("gun_tag") String gunTag,
                         @JsonProperty("tags") List<String> tags,
                         @JsonProperty("player_selectable") boolean playerSelectable,
                         @JsonProperty("disabled") boolean disabled,
                         @JsonProperty("in_development") boolean inDevelopment,
                         @JsonProperty("colors") Colors colors,
                         @JsonProperty("images") Images images,
                         @JsonProperty("starting_stats") Map<String, StatValue> startingStats) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Description(@JsonProperty("lore") String lore,
                              @JsonProperty("role") String role,
                              @JsonProperty("playstyle") String playstyle) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Colors(@JsonProperty("style_hex") String styleHex) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Images(@JsonProperty("icon_hero_card") String iconHeroCard) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StatValue(@JsonProperty("value") Double value) {
    }
}
