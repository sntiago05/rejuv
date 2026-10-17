package sntiago05.rejuv.clients.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ItemAsset(@JsonProperty("id") long id,
                         @JsonProperty("class_name") String className,
                         @JsonProperty("name") String name,
                         @JsonProperty("type") String type,
                         @JsonProperty("item_tier") Integer itemTier,
                         @JsonProperty("item_slot_type") String itemSlotType,
                         @JsonProperty("cost") Integer cost,
                         @JsonProperty("image") String image) {

    public ItemAsset(long id, String className, String name, String type, Integer itemTier, String itemSlotType, Integer cost) {
        this(id, className, name, type, itemTier, itemSlotType, cost, null);
    }
}
