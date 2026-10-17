package sntiago05.rejuv.clients.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MessagesRequest(
        String model,
        @JsonProperty("max_tokens") int maxTokens,
        String system,
        List<Message> messages,
        @JsonProperty("output_config") OutputConfig outputConfig
) {

    public static final Map<String, Object> RESULT_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "strength", Map.of("type", "string"),
                    "biggest_leak", Map.of("type", "string"),
                    "next_game_action", Map.of("type", "string"),
                    "key_numbers", Map.of("type", "array", "items", Map.of("type", "string"))
            ),
            "required", List.of("strength", "biggest_leak", "next_game_action", "key_numbers"),
            "additionalProperties", false
    );

    public record Message(String role, String content) {
    }

    public record OutputConfig(String effort, Format format) {
    }

    public record Format(String type, Map<String, Object> schema) {
    }
}
