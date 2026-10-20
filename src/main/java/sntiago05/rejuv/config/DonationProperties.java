package sntiago05.rejuv.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rejuv.donation")
public record DonationProperties(String url, String label) {
}
