package sntiago05.rejuv.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class DeadlockApiPropertiesTest {

    @Configuration
    @EnableConfigurationProperties(DeadlockApiProperties.class)
    static class PropertiesConfig {
    }

    @Test
    void bindsDefaultsFromApplicationYaml() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(PropertiesConfig.class)
                .run(context -> assertThat(context.getBean(DeadlockApiProperties.class))
                        .isEqualTo(new DeadlockApiProperties("https://api.deadlock-api.com", Duration.ofSeconds(3),
                                Duration.ofSeconds(10), Duration.ofSeconds(2), Duration.ofSeconds(10))));
    }
}
