package sntiago05.rejuv.clients.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record HeroCounterStat(@JsonProperty("hero_id") int heroId,
                              @JsonProperty("enemy_hero_id") int enemyHeroId,
                              @JsonProperty("wins") long wins,
                              @JsonProperty("matches_played") long matchesPlayed) {
}
