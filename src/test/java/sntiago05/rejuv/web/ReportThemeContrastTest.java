package sntiago05.rejuv.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Reads the color tokens of the Tailwind source and checks WCAG AA contrast (4.5:1) for every text and background
 * pair the report uses, in the dark and the light theme.
 */
class ReportThemeContrastTest {

    private static final Pattern TOKEN = Pattern.compile("--c-([a-z-]+):\\s*(#[0-9a-fA-F]{6});");
    private static final String[][] TEXT_ON_BACKGROUND = {
            {"text", "bg"}, {"text", "panel"}, {"text", "raised"},
            {"muted", "bg"}, {"muted", "panel"}, {"muted", "raised"},
            {"accent", "bg"}, {"accent", "panel"}, {"accent", "raised"},
            {"on-accent", "accent"},
            {"win", "panel"}, {"win", "raised"}, {"loss", "panel"}, {"loss", "raised"},
            {"blue", "panel"}, {"orange", "panel"}, {"focus", "bg"}, {"focus", "panel"}
    };

    @Test
    void everyTextPairMeetsWcagAaInBothThemes() throws Exception {
        String css = Files.readString(Path.of("src/main/tailwind/report.css"));
        int light = css.indexOf("@media (prefers-color-scheme: light)");
        assertThat(light).isPositive();
        int darkStart = css.indexOf(":root {");
        Map<String, String> dark = tokens(css.substring(darkStart, light));
        Map<String, String> lightTheme = new LinkedHashMap<>(dark);
        lightTheme.putAll(tokens(css.substring(light, css.indexOf("@theme inline"))));

        assertThat(dark).containsKeys("text", "bg", "panel", "raised", "muted", "accent", "on-accent", "win", "loss");
        for (Map.Entry<String, Map<String, String>> theme : Map.of("dark", dark, "light", lightTheme).entrySet()) {
            for (String[] pair : TEXT_ON_BACKGROUND) {
                double ratio = contrast(theme.getValue().get(pair[0]), theme.getValue().get(pair[1]));
                assertThat(ratio)
                        .as("%s theme: %s on %s", theme.getKey(), pair[0], pair[1])
                        .isGreaterThanOrEqualTo(4.5);
            }
        }
    }

    private static Map<String, String> tokens(String block) {
        Map<String, String> map = new LinkedHashMap<>();
        Matcher matcher = TOKEN.matcher(block);
        while (matcher.find()) {
            map.put(matcher.group(1), matcher.group(2));
        }
        return map;
    }

    private static double contrast(String foreground, String background) {
        double a = luminance(foreground);
        double b = luminance(background);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    private static double luminance(String hex) {
        double r = channel(Integer.parseInt(hex.substring(1, 3), 16));
        double g = channel(Integer.parseInt(hex.substring(3, 5), 16));
        double b = channel(Integer.parseInt(hex.substring(5, 7), 16));
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double channel(int value) {
        double c = value / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
