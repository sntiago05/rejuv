package sntiago05.rejuv.clients.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import sntiago05.rejuv.domain.AnalysisResult;

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
                    "key_numbers", Map.of("type", "array", "items", Map.of("type", "string")),
                    "focus_area", Map.of("type", "string", "enum", AnalysisResult.FOCUS_AREAS.stream().sorted().toList()),
                    "severity", Map.of("type", "string", "enum", AnalysisResult.SEVERITIES.stream().sorted().toList())
            ),
            "required", List.of("strength", "biggest_leak", "next_game_action", "key_numbers", "focus_area", "severity"),
            "additionalProperties", false
    );

    public record Message(String role, String content) {
    }

    public record OutputConfig(String effort, Format format) {
    }

    public record Format(String type, Map<String, Object> schema) {
    }
}
