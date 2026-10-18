package sntiago05.rejuv.services;

public interface PremiumChecker {
    /** True when the Discord user has unlimited analyses. Task 020 provides the Patreon role implementation. */
    boolean isPremium(long discordUserId);
}
