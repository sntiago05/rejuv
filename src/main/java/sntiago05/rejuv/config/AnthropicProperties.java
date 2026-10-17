package sntiago05.rejuv.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("anthropic")
public record AnthropicProperties(String baseUrl, String apiKey, String model, String effort, int maxTokens,
                                  Duration connectTimeout, Duration readTimeout,
                                  Duration defaultRetryAfter, Duration maxRetryAfter) {

    @Override
    public String toString() {
        String maskedKey = (apiKey == null || apiKey.isBlank()) ? "" : "****";
        return "AnthropicProperties[baseUrl=" + baseUrl + ", apiKey=" + maskedKey + ", model=" + model
                + ", effort=" + effort + ", maxTokens=" + maxTokens + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + ", defaultRetryAfter=" + defaultRetryAfter
                + ", maxRetryAfter=" + maxRetryAfter + "]";
    }
}
