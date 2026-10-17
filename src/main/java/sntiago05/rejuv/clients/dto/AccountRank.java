package sntiago05.rejuv.clients.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AccountRank(@JsonProperty("account_id") long accountId,
                          @JsonProperty("badge") int badge,
                          @JsonProperty("rank") int rank,
                          @JsonProperty("subrank") int subrank) { }
