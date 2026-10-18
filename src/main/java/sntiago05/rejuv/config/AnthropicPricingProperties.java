package sntiago05.rejuv.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("anthropic.pricing")
public record AnthropicPricingProperties(double inputUsdPerMillion, double outputUsdPerMillion) { }
