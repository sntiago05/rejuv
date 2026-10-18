package sntiago05.rejuv.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import sntiago05.rejuv.services.PremiumChecker;

@Configuration
@EnableConfigurationProperties(UsageProperties.class)
public class UsageConfig {

    @Bean
    @ConditionalOnMissingBean(PremiumChecker.class)
    public PremiumChecker defaultPremiumChecker() {
        return discordUserId -> false;
    }
}
