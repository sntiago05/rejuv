package sntiago05.rejuv.clients;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class AnalysisPromptTest {

    @Test
    void promptMeetsSpecRequirements() throws IOException {
        String prompt;
        try (InputStream in = getClass().getResourceAsStream("/prompts/analysis-system.txt")) {
            assertThat(in).as("analysis-system.txt should exist on classpath").isNotNull();
            prompt = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(prompt.length())
                .as("Prompt must stay under 4,000 characters")
                .isLessThan(4000);

        // Five coach keys
        assertThat(prompt).contains("phases");
        assertThat(prompt).contains("deaths");
        assertThat(prompt).contains("build");
        assertThat(prompt).contains("recent_form");
        assertThat(prompt).contains("matchup");

        // Three severity words
        assertThat(prompt).contains("minor");
        assertThat(prompt).contains("notable");
        assertThat(prompt).contains("major");

        // Required field and control words
        assertThat(prompt).contains("focus_area");
        assertThat(prompt).contains("severity");
        assertThat(prompt).contains("significance");
        assertThat(prompt).contains("language");
    }
}
