package sntiago05.rejuv.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.OptionalDouble;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.DeadlockApiException;
import sntiago05.rejuv.clients.dto.PlayerRank;
import sntiago05.rejuv.clients.dto.RankDistributionEntry;

class RankPercentileServiceTest {

    private DeadlockApiClient deadlockApiClient;
    private RankPercentileService rankPercentileService;

    @BeforeEach
    void setUp() {
        deadlockApiClient = mock(DeadlockApiClient.class);
        rankPercentileService = new RankPercentileService(deadlockApiClient);
    }

    @Test
    void smallHandMadeDistribution() {
        // Distribution:
        // Badge 11: 10 players
        // Badge 12: 20 players
        // Badge 13: 10 players
        // Total = 40 players
        List<RankDistributionEntry> distribution = List.of(
                new RankDistributionEntry(12, 20L), // Unsorted on purpose to test sorting
                new RankDistributionEntry(11, 10L),
                new RankDistributionEntry(13, 10L)
        );
        when(deadlockApiClient.getRankDistribution()).thenReturn(distribution);

        // First rank (Badge 11): midRank = 0 + 5 = 5 -> 5/40*100 = 12.5%
        OptionalDouble firstRank = rankPercentileService.percentile(new PlayerRank(11, 1, 1));
        assertThat(firstRank).hasValue(12.5);

        // Middle rank (Badge 12): midRank = 10 + 10 = 20 -> 20/40*100 = 50.0%
        OptionalDouble middleRank = rankPercentileService.percentile(new PlayerRank(12, 1, 2));
        assertThat(middleRank).hasValue(50.0);

        // Top rank (Badge 13): midRank = 30 + 5 = 35 -> 35/40*100 = 87.5%
        OptionalDouble topRank = rankPercentileService.percentile(new PlayerRank(13, 1, 3));
        assertThat(topRank).hasValue(87.5);
    }

    @Test
    void unknownOrUnrankedPlayerReturnsEmpty() {
        // Badge 0 = unranked
        OptionalDouble unranked = rankPercentileService.percentile(new PlayerRank(0, 0, 0));
        assertThat(unranked).isEmpty();

        // Null rank
        OptionalDouble nullRank = rankPercentileService.percentile(null);
        assertThat(nullRank).isEmpty();
    }

    @Test
    void missingBadgeInDistributionReturnsEmpty() {
        List<RankDistributionEntry> distribution = List.of(
                new RankDistributionEntry(11, 10L),
                new RankDistributionEntry(12, 20L)
        );
        when(deadlockApiClient.getRankDistribution()).thenReturn(distribution);

        OptionalDouble missing = rankPercentileService.percentile(new PlayerRank(99, 9, 9));
        assertThat(missing).isEmpty();
    }

    @Test
    void emptyDistributionReturnsEmpty() {
        when(deadlockApiClient.getRankDistribution()).thenReturn(List.of());

        OptionalDouble empty = rankPercentileService.percentile(new PlayerRank(11, 1, 1));
        assertThat(empty).isEmpty();
    }

    @Test
    void apiExceptionPropagates() {
        when(deadlockApiClient.getRankDistribution())
                .thenThrow(new DeadlockApiException("Service error", null));

        assertThatThrownBy(() -> rankPercentileService.percentile(new PlayerRank(11, 1, 1)))
                .isInstanceOf(DeadlockApiException.class);
    }
}
