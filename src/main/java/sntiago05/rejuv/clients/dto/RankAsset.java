package sntiago05.rejuv.clients.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RankAsset(@JsonProperty("tier") int tier,
                         @JsonProperty("name") String name,
                         @JsonProperty("images") Map<String, String> images) {

    public RankAsset(int tier, String name) {
        this(tier, name, null);
    }
}
