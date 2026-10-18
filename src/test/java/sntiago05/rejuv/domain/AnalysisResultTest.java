package sntiago05.rejuv.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class AnalysisResultTest {

    private final ObjectMapper objectMapper = new JsonMapper();

    @Test
    void cleansTrailingBraceAtEndOfSentence() {
        AnalysisResult result = new AnalysisResult(
                "Good farm",
                "High deaths",
                "Prioritize last hits to close the gap on creep score.}",
                List.of("souls: 800")
        );
        assertThat(result.nextGameAction()).isEqualTo("Prioritize last hits to close the gap on creep score.");
    }

    @Test
    void cleansTrailingCommaAfterPeriod() {
        AnalysisResult result = new AnalysisResult(
                "Good farm",
                "High deaths",
                "Stay alive so your souls per minute stays above 1043.3.,",
                List.of("souls: 800")
        );
        assertThat(result.nextGameAction()).isEqualTo("Stay alive so your souls per minute stays above 1043.3.");
    }

    @Test
    void preservesPunctuationWithoutStrayChars() {
        AnalysisResult result = new AnalysisResult(
                "Strong play",
                "Deaths: 7 / 5",
                "Keep it up!",
                List.of("Deaths: 7 / 5")
        );
        assertThat(result.biggestLeak()).isEqualTo("Deaths: 7 / 5");
        assertThat(result.keyNumbers().getFirst()).isEqualTo("Deaths: 7 / 5");
    }

    @Test
    void cleansCombinationOfTrailingBraceSpaceAndComma() {
        AnalysisResult result = new AnalysisResult(
                "Good job.} ,",
                "leak",
                "action",
                List.of("key")
        );
        assertThat(result.strength()).isEqualTo("Good job.");
    }

    @Test
    void singleStrayBraceBecomesEmptyString() {
        AnalysisResult result = new AnalysisResult(
                "}",
                "}",
                "}",
                List.of("}")
        );
        assertThat(result.strength()).isEmpty();
        assertThat(result.biggestLeak()).isEmpty();
        assertThat(result.nextGameAction()).isEmpty();
        assertThat(result.keyNumbers()).containsExactly("");
    }

    @Test
    void preservesBracesAndCommasInMiddleOfText() {
        AnalysisResult result = new AnalysisResult(
                "Deaths: 7, assists: 9",
                "Avoid {risky} plays in lane, especially top",
                "Next action: 1, 2, and 3",
                List.of("assists: 9, deaths: 7")
        );
        assertThat(result.strength()).isEqualTo("Deaths: 7, assists: 9");
        assertThat(result.biggestLeak()).isEqualTo("Avoid {risky} plays in lane, especially top");
        assertThat(result.nextGameAction()).isEqualTo("Next action: 1, 2, and 3");
        assertThat(result.keyNumbers().getFirst()).isEqualTo("assists: 9, deaths: 7");
    }

    @Test
    void preservesTrailingPunctuationSet() {
        assertThat(AnalysisResult.clean("Nice!")).isEqualTo("Nice!");
        assertThat(AnalysisResult.clean("Really?")).isEqualTo("Really?");
        assertThat(AnalysisResult.clean("Note (important)")).isEqualTo("Note (important)");
        assertThat(AnalysisResult.clean("He said \"stop\"")).isEqualTo("He said \"stop\"");
        assertThat(AnalysisResult.clean("Player's")).isEqualTo("Player's");
        assertThat(AnalysisResult.clean("Done.")).isEqualTo("Done.");
    }

    @Test
    void cleansOtherStrayCharacters() {
        assertThat(AnalysisResult.clean("text]")).isEqualTo("text");
        assertThat(AnalysisResult.clean("text[")).isEqualTo("text");
        assertThat(AnalysisResult.clean("text{")).isEqualTo("text");
        assertThat(AnalysisResult.clean("text}")).isEqualTo("text");
        assertThat(AnalysisResult.clean("text,")).isEqualTo("text");
        assertThat(AnalysisResult.clean("text } ] , ")).isEqualTo("text");
    }

    @Test
    void preservesLeadingWhitespace() {
        assertThat(AnalysisResult.clean("   leading and trailing}")).isEqualTo("   leading and trailing");
    }

    @Test
    void nullFieldsAndNullKeyNumbersStayNull() {
        AnalysisResult result = new AnalysisResult(null, null, null, null);
        assertThat(result.strength()).isNull();
        assertThat(result.biggestLeak()).isNull();
        assertThat(result.nextGameAction()).isNull();
        assertThat(result.keyNumbers()).isNull();
    }

    @Test
    void nullElementInKeyNumbersStaysNull() {
        AnalysisResult result = new AnalysisResult("s", "b", "n", Collections.singletonList(null));
        assertThat(result.keyNumbers()).hasSize(1);
        assertThat(result.keyNumbers().getFirst()).isNull();
    }

    @Test
    void jacksonRoundTripCleansStoredJsonAndPreservesPropertyNames() throws Exception {
        String storedJson = """
                {
                    "strength": "Your assists (9) were at the p25 level and your net worth at 10 minutes (7071.3) gave you a usable base, showing you still contributed to fights despite the rough game.",
                    "biggest_leak": "You died 7 times (2.8 per 10 min) against a median of 6, and your souls per minute (846.3) sat below the p25 of 963.1, so deaths were costing you farm time. Your last hits (84 vs p50 130.3) and denies (0 vs p50 2) also show weak lane and creep control.",
                    "next_game_action": "Before each fight or risky push, check your death timers and enemy positioning, and prioritize a steady last-hit routine in the first 10 minutes to close the gap on creep score.}",
                    "key_numbers": [
                        "Deaths: 7 / 6",
                        "Souls per min: 846.3 / 1085.9",
                        "Last hits: 84 / 130.3",
                        "Player damage per min: 675.6 / 925.4"
                    ]
                }
                """;

        AnalysisResult result = objectMapper.readValue(storedJson, AnalysisResult.class);

        assertThat(result.nextGameAction())
                .isEqualTo("Before each fight or risky push, check your death timers and enemy positioning, and prioritize a steady last-hit routine in the first 10 minutes to close the gap on creep score.");
        assertThat(result.nextGameAction()).endsWith("creep score.");
        assertThat(result.nextGameAction()).doesNotEndWith("}");

        // Serialization round trip: check exact property names
        String serialized = objectMapper.writeValueAsString(result);
        JsonNode tree = objectMapper.readTree(serialized);

        assertThat(tree.has("strength")).isTrue();
        assertThat(tree.has("biggest_leak")).isTrue();
        assertThat(tree.has("next_game_action")).isTrue();
        assertThat(tree.has("key_numbers")).isTrue();

        assertThat(tree.get("next_game_action").asText()).endsWith("creep score.");
    }

    @Test
    void fourArgumentConstructorGivesNullFocusAreaAndSeverity() {
        AnalysisResult result = new AnalysisResult("s", "l", "a", List.of("1"));
        assertThat(result.focusArea()).isNull();
        assertThat(result.severity()).isNull();
    }

    @Test
    void oldJsonWithoutNewKeysParsesWithNulls() throws Exception {
        String json = """
                {
                    "strength": "Good farm",
                    "biggest_leak": "Many deaths",
                    "next_game_action": "Ward more",
                    "key_numbers": ["10 / 5"]
                }
                """;
        AnalysisResult result = objectMapper.readValue(json, AnalysisResult.class);
        assertThat(result.strength()).isEqualTo("Good farm");
        assertThat(result.focusArea()).isNull();
        assertThat(result.severity()).isNull();
    }

    @Test
    void focusAreaNormalizesWhitespaceAndCase() {
        AnalysisResult result = new AnalysisResult("s", "l", "a", List.of(), " Deaths ", "major");
        assertThat(result.focusArea()).isEqualTo("deaths");
    }

    @Test
    void invalidSeverityBecomesNull() {
        AnalysisResult result = new AnalysisResult("s", "l", "a", List.of(), "deaths", "critical");
        assertThat(result.severity()).isNull();
    }

    @Test
    void invalidFocusAreaBecomesNull() {
        AnalysisResult result = new AnalysisResult("s", "l", "a", List.of(), "unknown_area", "major");
        assertThat(result.focusArea()).isNull();
    }

    @Test
    void severityNormalizesWhitespaceAndCase() {
        AnalysisResult result = new AnalysisResult("s", "l", "a", List.of(), "deaths", " NOTABLE ");
        assertThat(result.severity()).isEqualTo("notable");
    }

    @Test
    void serializingResultWithBothNullOmitsTheKeys() throws Exception {
        AnalysisResult result = new AnalysisResult("s", "l", "a", List.of("1"), null, null);
        String json = objectMapper.writeValueAsString(result);
        JsonNode tree = objectMapper.readTree(json);
        assertThat(tree.has("focus_area")).isFalse();
        assertThat(tree.has("severity")).isFalse();
        assertThat(tree.has("strength")).isTrue();
        assertThat(tree.has("biggest_leak")).isTrue();
        assertThat(tree.has("next_game_action")).isTrue();
        assertThat(tree.has("key_numbers")).isTrue();
    }

    @Test
    void serializingResultWithValidKeysIncludesThem() throws Exception {
        AnalysisResult result = new AnalysisResult("s", "l", "a", List.of("1"), "phases", "major");
        String json = objectMapper.writeValueAsString(result);
        JsonNode tree = objectMapper.readTree(json);
        assertThat(tree.path("focus_area").asText()).isEqualTo("phases");
        assertThat(tree.path("severity").asText()).isEqualTo("major");
    }
}
