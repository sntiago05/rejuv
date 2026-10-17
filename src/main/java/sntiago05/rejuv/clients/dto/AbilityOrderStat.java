package sntiago05.rejuv.clients.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AbilityOrderStat(@JsonProperty("abilities") List<Long> abilities,
                               @JsonProperty("matches") long matches,
                               @JsonProperty("wins") long wins) {

    public AbilityOrderStat {
        abilities = abilities == null ? List.of() : List.copyOf(abilities);
    }
}
