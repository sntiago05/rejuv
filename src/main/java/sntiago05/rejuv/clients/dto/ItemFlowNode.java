package sntiago05.rejuv.clients.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ItemFlowNode(@JsonProperty("column") int column,
                           @JsonProperty("item_id") long itemId,
                           @JsonProperty("matches") long matches,
                           @JsonProperty("wins") long wins) { }
