package sntiago05.rejuv.clients.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MetricPercentiles(@JsonProperty("avg") double avg,
                                @JsonProperty("percentile25") double p25,
                                @JsonProperty("percentile50") double p50,
                                @JsonProperty("percentile75") double p75) {
}
