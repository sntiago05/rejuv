package sntiago05.rejuv.clients.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record HeroSynergyStat(@JsonProperty("hero_id1") int heroId1,
                              @JsonProperty("hero_id2") int heroId2,
                              @JsonProperty("wins") long wins,
                              @JsonProperty("matches_played") long matchesPlayed) {
}
