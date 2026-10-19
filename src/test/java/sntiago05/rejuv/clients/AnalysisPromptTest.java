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
                .as("Prompt must stay under 5,000 characters")
                .isLessThan(5000);

        // Six coach keys
        assertThat(prompt).contains("phases");
        assertThat(prompt).contains("deaths");
        assertThat(prompt).contains("build");
        assertThat(prompt).contains("recent_form");
        assertThat(prompt).contains("matchup");
        assertThat(prompt).contains("objectives");

        // Three severity words
        assertThat(prompt).contains("minor");
        assertThat(prompt).contains("notable");
        assertThat(prompt).contains("major");

        // Required field and control words
        assertThat(prompt).contains("focus_area");
        assertThat(prompt).contains("severity");
        assertThat(prompt).contains("significance");
        assertThat(prompt).contains("language");
        assertThat(prompt).contains("omitted");
        assertThat(prompt).contains("nothing notable");
        assertThat(prompt).doesNotContain("p75");

        // Task 046 fields and rules
        assertThat(prompt).contains("ttk_median_s");
        assertThat(prompt).contains("ttk_p90_s");
        assertThat(prompt).contains("ttk_counted");
        assertThat(prompt).contains("rejuvenator_stolen_by_enemy");
        assertThat(prompt).contains("boss_damage_share_pct");
        assertThat(prompt).contains("deaths, phases, objectives, build, matchup, recent_form");

        String focusAreaLine = prompt.lines()
                .filter(line -> line.trim().startsWith("- focus_area:"))
                .findFirst()
                .orElseThrow();
        assertThat(focusAreaLine).contains("\"objectives\"");
    }
}
