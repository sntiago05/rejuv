package sntiago05.rejuv.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class DeadlockApiGatePropertiesTest {

    @Configuration
    @EnableConfigurationProperties(DeadlockApiGateProperties.class)
    static class PropertiesConfig {
    }

    @Test
    void bindsDefaultsFromApplicationYaml() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(PropertiesConfig.class)
                .run(context -> assertThat(context.getBean(DeadlockApiGateProperties.class))
                        .isEqualTo(new DeadlockApiGateProperties(1, Duration.ofSeconds(20))));
    }

    @Test
    void bindsDefaultsWhenAbsent() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfig.class)
                .run(context -> assertThat(context.getBean(DeadlockApiGateProperties.class))
                        .isEqualTo(new DeadlockApiGateProperties(1, Duration.ofSeconds(20))));
    }

    @Test
    void bindsCustomValues() {
        new ApplicationContextRunner()
                .withPropertyValues("deadlock-api.gate.max-concurrent=3", "deadlock-api.gate.acquire-timeout=10s")
                .withUserConfiguration(PropertiesConfig.class)
                .run(context -> assertThat(context.getBean(DeadlockApiGateProperties.class))
                        .isEqualTo(new DeadlockApiGateProperties(3, Duration.ofSeconds(10))));
    }

    @Test
    void rejectsZeroOrNegativePermits() {
        assertThatThrownBy(() -> new DeadlockApiGateProperties(0, Duration.ofSeconds(20)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeadlockApiGateProperties(-1, Duration.ofSeconds(20)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsZeroOrNegativeTimeout() {
        assertThatThrownBy(() -> new DeadlockApiGateProperties(1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeadlockApiGateProperties(1, Duration.ofSeconds(-5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeadlockApiGateProperties(1, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidValuesFromProperties() {
        new ApplicationContextRunner()
                .withPropertyValues("deadlock-api.gate.max-concurrent=0")
                .withUserConfiguration(PropertiesConfig.class)
                .run(context -> assertThat(context).hasFailed());
    }
}
