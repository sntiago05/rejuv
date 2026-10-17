package sntiago05.rejuv.clients.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MateStat(
        @JsonProperty("mate_id") long mateId,
        @JsonProperty("matches_played") long matches,
        @JsonProperty("wins") long wins) {
}
