package sntiago05.rejuv.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rejuv.web")
public record WebProperties(String baseUrl) {

    public boolean enabled() {
        return baseUrl != null && !baseUrl.isBlank();
    }
}
