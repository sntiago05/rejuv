package sntiago05.rejuv.clients.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ItemStat(@JsonProperty("item_id") long itemId,
                       @JsonProperty("matches") long matches,
                       @JsonProperty("wins") long wins,
                       @JsonProperty("losses") long losses,
                       @JsonProperty("avg_buy_time_s") double avgBuyTimeS,
                       @JsonProperty("avg_buy_time_relative") double avgBuyTimeRelative) {
}
