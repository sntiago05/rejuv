package sntiago05.rejuv.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class DonationPropertiesTest {

    @Configuration
    @EnableConfigurationProperties(DonationProperties.class)
    static class PropertiesConfig {
    }

    @Test
    void bindsExplicitProperties() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfig.class)
                .withPropertyValues(
                        "rejuv.donation.url=https://example.com/donate",
                        "rejuv.donation.label=Support Us"
                )
                .run(context -> {
                    DonationProperties props = context.getBean(DonationProperties.class);
                    assertThat(props.url()).isEqualTo("https://example.com/donate");
                    assertThat(props.label()).isEqualTo("Support Us");
                });
    }

    @Test
    void emptyByDefault() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(PropertiesConfig.class)
                .run(context -> {
                    DonationProperties props = context.getBean(DonationProperties.class);
                    assertThat(props.url()).satisfiesAnyOf(
                            url -> assertThat(url).isNull(),
                            url -> assertThat(url).isBlank()
                    );
                    assertThat(props.label()).satisfiesAnyOf(
                            label -> assertThat(label).isNull(),
                            label -> assertThat(label).isBlank()
                    );
                });
    }
}
