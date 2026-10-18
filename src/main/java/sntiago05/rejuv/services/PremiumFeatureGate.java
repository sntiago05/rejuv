package sntiago05.rejuv.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import sntiago05.rejuv.config.UsageProperties;

/**
 * Reusable gate for premium features.
 * When usage limits are disabled (free launch period), all features are open.
 * When limits are enabled, checks whether the Discord user is premium.
 * Never throws: logs exceptions and denies access.
 */
@Service
public class PremiumFeatureGate {

    private static final Logger log = LoggerFactory.getLogger(PremiumFeatureGate.class);

    private final UsageProperties usageProperties;
    private final PremiumChecker premiumChecker;

    public PremiumFeatureGate(UsageProperties usageProperties, PremiumChecker premiumChecker) {
        this.usageProperties = usageProperties;
        this.premiumChecker = premiumChecker;
    }

    public boolean allowed(long discordUserId) {
        if (!usageProperties.enabled()) {
            return true;
        }
        try {
            return premiumChecker.isPremium(discordUserId);
        } catch (Exception ex) {
            log.warn("PremiumChecker threw for user {}: {}", discordUserId, ex.toString());
            return false;
        }
    }
}
