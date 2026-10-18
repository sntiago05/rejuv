package sntiago05.rejuv.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rejuv.usage")
public record UsageProperties(boolean enabled, int weeklyFreeAnalyses) {

    public UsageProperties {
        if (weeklyFreeAnalyses <= 0) {
            throw new IllegalArgumentException("weeklyFreeAnalyses must be positive, got: " + weeklyFreeAnalyses);
        }
    }
}
