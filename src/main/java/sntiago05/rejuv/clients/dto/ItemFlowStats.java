package sntiago05.rejuv.clients.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ItemFlowStats(@JsonProperty("nodes") List<ItemFlowNode> nodes,
                            @JsonProperty("reached_per_column") List<Long> reachedPerColumn) {

    public ItemFlowStats {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        reachedPerColumn = reachedPerColumn == null ? List.of() : List.copyOf(reachedPerColumn);
    }
}
