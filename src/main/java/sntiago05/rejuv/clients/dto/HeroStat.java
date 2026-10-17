package sntiago05.rejuv.clients.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record HeroStat(@JsonProperty("hero_id") int heroId,
                       @JsonProperty("matches") long matches,
                       @JsonProperty("wins") long wins,
                       @JsonProperty("losses") long losses) { }
