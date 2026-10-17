package sntiago05.rejuv.domain;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalysisResult(String strength,
                            @JsonProperty("biggest_leak") String biggestLeak,
                            @JsonProperty("next_game_action") String nextGameAction,
                            @JsonProperty("key_numbers") List<String> keyNumbers) {
}
