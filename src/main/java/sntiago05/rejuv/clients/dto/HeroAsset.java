package sntiago05.rejuv.clients.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record HeroAsset(@JsonProperty("id") int id,
                        @JsonProperty("class_name") String className,
                        @JsonProperty("name") String name) {
}
