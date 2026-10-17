package sntiago05.rejuv.clients.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record HeroImages(@JsonProperty("icon_image_small") String iconImageSmall,
                         @JsonProperty("icon_hero_card") String iconHeroCard) {
}
