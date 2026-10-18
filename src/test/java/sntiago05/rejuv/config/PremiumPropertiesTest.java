package sntiago05.rejuv.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import sntiago05.rejuv.repositories.AnalysisUsageRepository;
import sntiago05.rejuv.services.PatreonRolePremiumChecker;
import sntiago05.rejuv.services.PremiumChecker;
import sntiago05.rejuv.services.PremiumRoleGateway;
import sntiago05.rejuv.services.UsageService;

class PremiumPropertiesTest {

    @Test
    void disabledWithEmptyIdsIsValid() {
        PremiumProperties disabledWithNulls = new PremiumProperties(false, null, null, null);
        assertThat(disabledWithNulls.enabled()).isFalse();
        assertThat(disabledWithNulls.guildId()).isNull();
        assertThat(disabledWithNulls.roleId()).isNull();
        assertThat(disabledWithNulls.cacheTtl()).isEqualTo(Duration.ofMinutes(2));

        PremiumProperties disabledWithZeros = new PremiumProperties(false, 0L, 0L, null);
        assertThat(disabledWithZeros.enabled()).isFalse();
        assertThat(disabledWithZeros.guildId()).isEqualTo(0L);
        assertThat(disabledWithZeros.roleId()).isEqualTo(0L);
    }

    @Test
    void enabledWithoutIdsOrWithZeroFails() {
        assertThatThrownBy(() -> new PremiumProperties(true, null, 100L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rejuv.premium.guild-id and rejuv.premium.role-id must be configured and positive");

        assertThatThrownBy(() -> new PremiumProperties(true, 100L, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rejuv.premium.guild-id and rejuv.premium.role-id must be configured and positive");

        assertThatThrownBy(() -> new PremiumProperties(true, 0L, 100L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rejuv.premium.guild-id and rejuv.premium.role-id must be configured and positive");

        assertThatThrownBy(() -> new PremiumProperties(true, 100L, 0L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rejuv.premium.guild-id and rejuv.premium.role-id must be configured and positive");

        assertThatThrownBy(() -> new PremiumProperties(true, -1L, 100L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rejuv.premium.guild-id and rejuv.premium.role-id must be configured and positive");

        assertThatThrownBy(() -> new PremiumProperties(true, 100L, -1L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rejuv.premium.guild-id and rejuv.premium.role-id must be configured and positive");
    }

    @Test
    void defaultTtlIsTwoMinutes() {
        PremiumProperties propsDefault = new PremiumProperties(true, 111L, 222L, null);
        assertThat(propsDefault.cacheTtl()).isEqualTo(Duration.ofMinutes(2));

        PremiumProperties propsExplicit = new PremiumProperties(true, 111L, 222L, Duration.ofSeconds(45));
        assertThat(propsExplicit.cacheTtl()).isEqualTo(Duration.ofSeconds(45));
    }

    @Test
    void bindsDefaultsFromApplicationYaml() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(PatreonRolePremiumChecker.class)
                .withPropertyValues("rejuv.premium.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(PremiumProperties.class);
                    assertThat(context).doesNotHaveBean(PremiumChecker.class);
                });
    }

    @Test
    void flagOffUsesDefaultLambdaBean() {
        new ApplicationContextRunner()
                .withPropertyValues(
                        "rejuv.premium.enabled=false",
                        "rejuv.usage.weekly-free-analyses=3"
                )
                .withUserConfiguration(UsageConfig.class, PatreonRolePremiumChecker.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(PremiumChecker.class);
                    PremiumChecker checker = context.getBean(PremiumChecker.class);
                    assertThat(checker).isNotInstanceOf(PatreonRolePremiumChecker.class);
                    assertThat(checker.isPremium(12345L)).isFalse();
                });
    }

    @Test
    void flagOnWithStubGatewayInjectsPatreonRolePremiumCheckerIntoUsageService() {
        new ApplicationContextRunner()
                .withPropertyValues(
                        "rejuv.premium.enabled=true",
                        "rejuv.premium.guild-id=111",
                        "rejuv.premium.role-id=222",
                        "rejuv.usage.weekly-free-analyses=3"
                )
                .withBean(PremiumRoleGateway.class, () -> mock(PremiumRoleGateway.class))
                .withBean(AnalysisUsageRepository.class, () -> mock(AnalysisUsageRepository.class))
                .withBean(Clock.class, Clock::systemUTC)
                .withUserConfiguration(UsageConfig.class, PatreonRolePremiumChecker.class, UsageService.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(PremiumChecker.class);
                    assertThat(context.getBean(PremiumChecker.class)).isInstanceOf(PatreonRolePremiumChecker.class);

                    UsageService usageService = context.getBean(UsageService.class);
                    assertThat(usageService).isNotNull();
                });
    }
}
