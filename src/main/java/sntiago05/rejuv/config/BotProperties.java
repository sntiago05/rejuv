package sntiago05.rejuv.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("bot")
public record BotProperties(String token, String devGuildId) {
}
