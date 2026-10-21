package sntiago05.rejuv.clients.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RankDistributionEntry(
        @JsonProperty("badge") int badge,
        @JsonProperty("players") long players) {
}
