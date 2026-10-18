package sntiago05.rejuv.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class AnalysisInputJsonTest {

    private final ObjectMapper mapper = new JsonMapper();

    @Test
    void emptyCoachOmittedAndJsonIdenticalToLegacyOutput() throws Exception {
        AnalysisInput input = new AnalysisInput(
                "Infernus",
                "Archon 3",
                "win",
                30.0,
                Map.of("kills", new AnalysisInput.MetricComparison(5.0, 3.0, 5.0, 8.0)),
                List.of(new AnalysisInput.ItemTiming("Basic Magazine", 4.5, 4.0)),
                "en"
        );

        String jsonFrom7Arg = mapper.writeValueAsString(input);
        String jsonWithCoachEmpty = mapper.writeValueAsString(input.withCoach(Map.of()));
        String jsonWithCoachNull = mapper.writeValueAsString(input.withCoach(null));

        assertThat(jsonFrom7Arg).doesNotContain("\"coach\"");
        assertThat(jsonWithCoachEmpty).isEqualTo(jsonFrom7Arg);
        assertThat(jsonWithCoachNull).isEqualTo(jsonFrom7Arg);
    }

    @Test
    void nonEmptyCoachSerializedAsTopLevelCoachObject() throws Exception {
        AnalysisInput input = new AnalysisInput(
                "Infernus",
                "Archon 3",
                "win",
                30.0,
                Map.of("kills", new AnalysisInput.MetricComparison(5.0, 3.0, 5.0, 8.0)),
                List.of(new AnalysisInput.ItemTiming("Basic Magazine", 4.5, 4.0)),
                "en"
        );

        AnalysisInput enriched = input.withCoach(Map.of(
                "phase_stats", Map.of("z_score", 1.25),
                "deaths", List.of("early_death_lane")
        ));

        String json = mapper.writeValueAsString(enriched);

        assertThat(json).contains("\"coach\":{");
        assertThat(json).contains("\"phase_stats\":{\"z_score\":1.25}");
        assertThat(json).contains("\"deaths\":[\"early_death_lane\"]");
    }

    @Test
    void withCoachNullEqualsEmpty() {
        AnalysisInput input = new AnalysisInput(
                "Infernus",
                "Archon 3",
                "win",
                30.0,
                Map.of(),
                List.of(),
                "en"
        );

        assertThat(input.coach()).isEmpty();
        assertThat(input.withCoach(null).coach()).isEmpty();
        assertThat(input.withCoach(Map.of()).coach()).isEmpty();
        assertThat(input.withCoach(null)).isEqualTo(input);
    }
}
