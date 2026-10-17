package sntiago05.rejuv.domain;

import java.util.List;

/** entries: ranked members only, best first, at most LeaderboardService.LEADERBOARD_SIZE. rankedCount counts all ranked members (not only those shown). */
public record ServerLeaderboard(List<LeaderboardEntry> entries, int rankedCount, int unrankedCount) {

    public ServerLeaderboard {
        entries = List.copyOf(entries);
    }
}
