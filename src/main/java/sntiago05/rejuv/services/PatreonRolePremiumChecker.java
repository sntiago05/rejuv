package sntiago05.rejuv.services;

import java.util.Objects;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import sntiago05.rejuv.config.PremiumProperties;

@Component
@Primary
@ConditionalOnProperty(prefix = "rejuv.premium", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(PremiumProperties.class)
public class PatreonRolePremiumChecker implements PremiumChecker {

    private static final Logger log = LoggerFactory.getLogger(PatreonRolePremiumChecker.class);
    private static final long MAX_CACHE_ENTRIES = 10_000L;

    private final PremiumRoleGateway gateway;
    private final PremiumProperties properties;
    private final Cache<Long, Boolean> cache;

    @Autowired
    public PatreonRolePremiumChecker(PremiumRoleGateway gateway, PremiumProperties properties) {
        this(gateway, properties, Ticker.systemTicker());
    }

    public PatreonRolePremiumChecker(PremiumRoleGateway gateway, PremiumProperties properties, Ticker ticker) {
        this.gateway = Objects.requireNonNull(gateway, "gateway must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        Objects.requireNonNull(ticker, "ticker must not be null");

        this.cache = Caffeine.newBuilder()
                .maximumSize(MAX_CACHE_ENTRIES)
                .expireAfterWrite(properties.cacheTtl())
                .ticker(ticker)
                .build();
    }

    @Override
    public boolean isPremium(long discordUserId) {
        try {
            Boolean result = cache.get(discordUserId, id -> gateway.hasRole(properties.guildId(), properties.roleId(), id));
            return Boolean.TRUE.equals(result);
        } catch (RuntimeException ex) {
            log.warn("Premium role lookup failed for user {}: {}", discordUserId, ex.getClass().getSimpleName());
            return false;
        }
    }
}
