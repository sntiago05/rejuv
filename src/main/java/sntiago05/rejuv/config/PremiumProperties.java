package sntiago05.rejuv.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rejuv.premium")
public record PremiumProperties(boolean enabled, Long guildId, Long roleId, Duration cacheTtl) {

    public PremiumProperties {
        if (cacheTtl == null) {
            cacheTtl = Duration.ofMinutes(2);
        }
        if (enabled) {
            if (guildId == null || guildId <= 0 || roleId == null || roleId <= 0) {
                throw new IllegalArgumentException(
                        "rejuv.premium.guild-id and rejuv.premium.role-id must be configured and positive when rejuv.premium.enabled is true"
                );
            }
        }
    }
}
