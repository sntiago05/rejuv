package sntiago05.rejuv.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class AnthropicPropertiesTest {

    @Configuration
    @EnableConfigurationProperties(AnthropicProperties.class)
    static class PropertiesConfig {
    }

    @Test
    void bindsDefaultsFromApplicationYaml() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(PropertiesConfig.class)
                .run(context -> assertThat(context.getBean(AnthropicProperties.class))
                        .isEqualTo(new AnthropicProperties(
                                "https://api.anthropic.com",
                                "",
                                "claude-haiku-5-5",
                                "low",
                                4000,
                                Duration.ofSeconds(3),
                                Duration.ofSeconds(30),
                                Duration.ofSeconds(1),
                                Duration.ofSeconds(10)
                        )));
    }

    @Test
    void toStringMasksApiKey() {
        AnthropicProperties properties = new AnthropicProperties(
                "https://api.anthropic.com",
                "sk-test",
                "claude-haiku-5-5",
                "low",
                1500,
                Duration.ofSeconds(3),
                Duration.ofSeconds(30),
                Duration.ofSeconds(1),
                Duration.ofSeconds(10)
        );
        assertThat(properties.toString()).doesNotContain("sk-test");
    }
}
