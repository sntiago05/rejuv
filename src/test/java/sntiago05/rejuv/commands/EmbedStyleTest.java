package sntiago05.rejuv.commands;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.junit.jupiter.api.Test;

class EmbedStyleTest {

    @Test
    void withIconReturnsFormattedLineWhenPresent() {
        String result = EmbedStyle.withIcon(Optional.of("<:h_1:12345>"), "**Infernus** (54.2%)");
        assertThat(result).isEqualTo("<:h_1:12345> **Infernus** (54.2%)");
    }

    @Test
    void withIconReturnsRawTextWhenAbsentOrBlank() {
        assertThat(EmbedStyle.withIcon(Optional.empty(), "Infernus")).isEqualTo("Infernus");
        assertThat(EmbedStyle.withIcon(Optional.of(""), "Infernus")).isEqualTo("Infernus");
        assertThat(EmbedStyle.withIcon(Optional.of("   "), "Infernus")).isEqualTo("Infernus");
        assertThat(EmbedStyle.withIcon(null, "Infernus")).isEqualTo("Infernus");
    }

    @Test
    void fitFieldEmptyOrNullReturnsEmptyString() {
        assertThat(EmbedStyle.fitField(Collections.emptyList())).isEqualTo("");
        assertThat(EmbedStyle.fitField(null)).isEqualTo("");
    }

    @Test
    void fitFieldKeepsAllLinesWhenShort() {
        List<String> lines = List.of("Line 1", "Line 2", "Line 3");
        assertThat(EmbedStyle.fitField(lines)).isEqualTo("Line 1\nLine 2\nLine 3");
    }

    @Test
    void fitFieldDropsWholeTrailingLinesAtLimit() {
        // Build lines of length 100 each
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            lines.add("Line " + String.format("%02d", i) + ": " + "x".repeat(90)); // 9 + 90 = 99 chars
        }
        // 10 lines of 99 chars + 9 newlines = 999 chars <= 1024
        // 11th line would be 999 + 1 + 99 = 1099 > 1024

        String result = EmbedStyle.fitField(lines);

        assertThat(result.length()).isLessThanOrEqualTo(1024);
        assertThat(result).contains("Line 00");
        assertThat(result).contains("Line 09");
        assertThat(result).doesNotContain("Line 10");
        // Verify line 09 is intact and not truncated
        assertThat(result).endsWith("x".repeat(90));
    }

    @Test
    void fitFieldReturnsEmptyIfFirstLineExceedsLimit() {
        String hugeLine = "x".repeat(1025);
        assertThat(EmbedStyle.fitField(List.of(hugeLine))).isEqualTo("");
    }

    @Test
    void addFittedFieldAddsNonInlineFieldWithFittedLines() {
        EmbedBuilder builder = new EmbedBuilder().setTitle("t");

        EmbedStyle.addFittedField(builder, "Items", List.of("a", "b"));

        MessageEmbed.Field field = builder.build().getFields().getFirst();
        assertThat(field.getName()).isEqualTo("Items");
        assertThat(field.getValue()).isEqualTo("a\nb");
        assertThat(field.isInline()).isFalse();
    }

    @Test
    void addFittedFieldSkipsFieldWhenNothingFits() {
        EmbedBuilder builder = new EmbedBuilder().setTitle("t");

        EmbedStyle.addFittedField(builder, "Empty", List.of());
        EmbedStyle.addFittedField(builder, "Too long", List.of("x".repeat(1025)));

        assertThat(builder.build().getFields()).isEmpty();
    }
}
