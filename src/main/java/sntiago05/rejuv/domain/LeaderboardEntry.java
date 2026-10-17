package sntiago05.rejuv.domain;

/** rankName is null when the tier is not in the ranks asset. */
public record LeaderboardEntry(long discordUserId, long accountId, int badge, int tier, int subrank, String rankName) { }
