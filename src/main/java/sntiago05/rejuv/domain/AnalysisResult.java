package sntiago05.rejuv.domain;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalysisResult(String strength,
                            @JsonProperty("biggest_leak") String biggestLeak,
                            @JsonProperty("next_game_action") String nextGameAction,
                            @JsonProperty("key_numbers") List<String> keyNumbers,
                            @JsonProperty("focus_area") @JsonInclude(JsonInclude.Include.NON_NULL) String focusArea,
                            @JsonProperty("severity") @JsonInclude(JsonInclude.Include.NON_NULL) String severity) {

    public static final Set<String> FOCUS_AREAS = Set.of("phases", "deaths", "build", "recent_form", "matchup", "metrics");
    public static final Set<String> SEVERITIES = Set.of("minor", "notable", "major");

    public AnalysisResult(String strength, String biggestLeak, String nextGameAction, List<String> keyNumbers) {
        this(strength, biggestLeak, nextGameAction, keyNumbers, null, null);
    }

    public AnalysisResult {
        strength = clean(strength);
        biggestLeak = clean(biggestLeak);
        nextGameAction = clean(nextGameAction);
        keyNumbers = keyNumbers == null ? null : keyNumbers.stream().map(AnalysisResult::clean).toList();
        focusArea = normalize(focusArea, FOCUS_AREAS);
        severity = normalize(severity, SEVERITIES);
    }

    private static String normalize(String value, Set<String> allowed) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return allowed.contains(normalized) ? normalized : null;
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
