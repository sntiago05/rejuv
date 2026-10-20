package sntiago05.rejuv.services;

import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.dto.PlayerRank;
import sntiago05.rejuv.clients.dto.RankDistributionEntry;

@Service
public class RankPercentileService {

    private final DeadlockApiClient deadlockApiClient;

    @Autowired
    public RankPercentileService(DeadlockApiClient deadlockApiClient) {
        this.deadlockApiClient = deadlockApiClient;
    }

    /**
     * Calculates the share (0 to 100) of players below the given rank badge,
     * using the mid-rank percentile method, rounded to one decimal place.
     * Returns empty if the rank is null, unranked (badge 0), the distribution is empty,
     * or the badge is not found in the distribution.
     */
    public OptionalDouble percentile(PlayerRank rank) {
        if (rank == null || rank.badge() == 0) {
            return OptionalDouble.empty();
        }

        List<RankDistributionEntry> distribution = deadlockApiClient.getRankDistribution();
        if (distribution.isEmpty()) {
            return OptionalDouble.empty();
        }

        RankDistributionEntry playerEntry = null;
        long totalPlayers = 0;
        long playersBelow = 0;

        // Ensure distribution is ordered by badge ascending
        List<RankDistributionEntry> sorted = distribution.stream()
                .sorted(Comparator.comparingInt(RankDistributionEntry::badge))
                .toList();

        for (RankDistributionEntry entry : sorted) {
            totalPlayers += entry.players();
            if (entry.badge() == rank.badge()) {
                playerEntry = entry;
            } else if (entry.badge() < rank.badge()) {
                playersBelow += entry.players();
            }
        }

        if (playerEntry == null || totalPlayers <= 0) {
            return OptionalDouble.empty();
        }

        double midRank = playersBelow + (playerEntry.players() / 2.0);
        double percentile = (midRank / totalPlayers) * 100.0;
        double rounded = Math.round(percentile * 10.0) / 10.0;

        return OptionalDouble.of(rounded);
    }
}
