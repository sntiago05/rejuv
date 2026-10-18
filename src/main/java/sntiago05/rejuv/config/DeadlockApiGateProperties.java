package sntiago05.rejuv.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("deadlock-api.gate")
public record DeadlockApiGateProperties(
        @DefaultValue("1") int maxConcurrent,
        @DefaultValue("20s") Duration acquireTimeout) {

    public DeadlockApiGateProperties {
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("maxConcurrent must be at least 1, got: " + maxConcurrent);
        }
        if (acquireTimeout == null || acquireTimeout.isNegative() || acquireTimeout.isZero()) {
            throw new IllegalArgumentException("acquireTimeout must be positive, got: " + acquireTimeout);
        }
    }
}
