package sntiago05.rejuv.services;

public interface PremiumRoleGateway {
    /**
     * Checks if a Discord user holds the given role in the specified guild.
     *
     * @param guildId the ID of the guild to inspect
     * @param roleId the ID of the role to check for
     * @param userId the Discord user ID
     * @return true if the user has the role, false otherwise
     * @throws PremiumLookupException when the answer cannot be known (guild unavailable, Discord error, timeout)
     */
    boolean hasRole(long guildId, long roleId, long userId);
}
