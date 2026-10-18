package sntiago05.rejuv.domain;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalysisResult(String strength,
                            @JsonProperty("biggest_leak") String biggestLeak,
                            @JsonProperty("next_game_action") String nextGameAction,
                            @JsonProperty("key_numbers") List<String> keyNumbers) {

    public AnalysisResult {
        strength = clean(strength);
        biggestLeak = clean(biggestLeak);
        nextGameAction = clean(nextGameAction);
        keyNumbers = keyNumbers == null ? null : keyNumbers.stream().map(AnalysisResult::clean).toList();
    }

    /** Package-private or private static; null stays null. */
    static String clean(String text) {
        if (text == null) {
            return null;
        }
        int end = text.length();
        while (end > 0 && isStrayOrWhitespace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }

    private static boolean isStrayOrWhitespace(char c) {
        return Character.isWhitespace(c)
                || c == '}' || c == '{'
                || c == ']' || c == '['
                || c == ',';
    }
}
