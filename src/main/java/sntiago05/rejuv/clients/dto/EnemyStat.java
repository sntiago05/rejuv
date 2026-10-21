package sntiago05.rejuv.clients.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EnemyStat(
        @JsonProperty("enemy_id") long enemyId,
        @JsonProperty("matches_played") long matches,
        @JsonProperty("wins") long wins) {
}
