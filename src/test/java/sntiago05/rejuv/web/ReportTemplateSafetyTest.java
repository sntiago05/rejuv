package sntiago05.rejuv.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class ReportTemplateSafetyTest {

    private static final Path TEMPLATES_DIR = Path.of("src/main/resources/templates");

    @Test
    void templatesAreFreeFromUnsafeDirectivesScriptsAndExternalResources() throws IOException {
        try (Stream<Path> paths = Files.walk(TEMPLATES_DIR)) {
            List<Path> htmlFiles = paths.filter(p -> p.toString().endsWith(".html")).toList();
            assertThat(htmlFiles).isNotEmpty();

            Pattern inlineStylePattern = Pattern.compile("\\bstyle\\s*=", Pattern.CASE_INSENSITIVE);
            Pattern scriptPattern = Pattern.compile("<script", Pattern.CASE_INSENSITIVE);
            Pattern utextPattern = Pattern.compile("th:utext", Pattern.CASE_INSENSITIVE);
            Pattern eventHandlerPattern = Pattern.compile("\\bon[a-z]+\\s*=", Pattern.CASE_INSENSITIVE);
            Pattern externalResourcePattern = Pattern.compile("(?:href|src)\\s*=\\s*[\"']https?://", Pattern.CASE_INSENSITIVE);

            for (Path htmlFile : htmlFiles) {
                String content = Files.readString(htmlFile);

                assertThat(utextPattern.matcher(content).find())
                        .as("File %s must not contain th:utext", htmlFile.getFileName())
                        .isFalse();

                assertThat(scriptPattern.matcher(content).find())
                        .as("File %s must not contain <script", htmlFile.getFileName())
                        .isFalse();

                assertThat(inlineStylePattern.matcher(content).find())
                        .as("File %s must not contain inline style= attributes", htmlFile.getFileName())
                        .isFalse();

                assertThat(eventHandlerPattern.matcher(content).find())
                        .as("File %s must not contain inline event handlers", htmlFile.getFileName())
                        .isFalse();

                assertThat(externalResourcePattern.matcher(content).find())
                        .as("File %s must not contain static external http(s) resources", htmlFile.getFileName())
                        .isFalse();
            }
        }
    }
}
