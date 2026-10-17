package sntiago05.rejuv.clients.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PlayerHeroStat(@JsonProperty("hero_id") int heroId,
                             @JsonProperty("matches_played") long matchesPlayed,
                             @JsonProperty("wins") long wins,
                             @JsonProperty("kills") long kills,
                             @JsonProperty("deaths") long deaths,
                             @JsonProperty("assists") long assists) { }
