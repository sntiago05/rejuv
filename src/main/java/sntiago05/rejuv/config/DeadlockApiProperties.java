package sntiago05.rejuv.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("deadlock-api")
public record DeadlockApiProperties(String baseUrl, Duration connectTimeout, Duration readTimeout,
                                    Duration defaultRetryAfter, Duration maxRetryAfter) {
}
