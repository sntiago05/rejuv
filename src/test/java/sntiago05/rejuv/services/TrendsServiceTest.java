package sntiago05.rejuv.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.dto.MatchHistoryEntry;
import sntiago05.rejuv.domain.MatchFilter;
import sntiago05.rejuv.services.TrendsService.HeroLine;
import sntiago05.rejuv.services.TrendsService.TrendDirection;
import sntiago05.rejuv.services.TrendsService.TrendsSummary;

class TrendsServiceTest {

    private static final long ACCOUNT_ID = 1000001L;

    private DeadlockApiClient deadlockApiClient;
    private TrendsService trendsService;

    @BeforeEach
    void setUp() {
        deadlockApiClient = mock(DeadlockApiClient.class);
        trendsService = new TrendsService(deadlockApiClient);
    }

    private static MatchHistoryEntry entry(long matchId, int heroId, int matchResult, int playerTeam,
                                           int kills, int deaths, int assists, long netWorth) {
        return new MatchHistoryEntry(matchId, heroId, 20, 1780000000L, 1800, 1,
                matchResult, playerTeam, 0, kills, deaths, assists, netWorth, 100, 10, null);
    }

    @Test
    void trendsMathWithHandCheckedList() {
        List<MatchHistoryEntry> history = List.of(
                entry(1L, 1, 0, 0, 10, 2, 8, 25000L),  // Win
                entry(2L, 1, 1, 1, 8, 4, 12, 22000L),  // Win
                entry(3L, 2, 0, 0, 6, 1, 10, 20000L),  // Win
                entry(4L, 2, 1, 0, 4, 5, 6, 15000L),   // Loss
                entry(5L, 3, 0, 1, 2, 8, 4, 12000L),   // Loss
                entry(6L, 1, 1, 0, 6, 6, 2, 14000L)    // Loss
        );
        when(deadlockApiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(history);

        TrendsSummary summary = trendsService.summarize(ACCOUNT_ID, 20);

        assertThat(summary.games()).isEqualTo(6);
        assertThat(summary.winRate()).isEqualTo(50.0);
        assertThat(summary.avgKills()).isEqualTo(6.0);
        assertThat(summary.avgDeaths()).isCloseTo(4.333, offset(0.001));
        assertThat(summary.avgAssists()).isEqualTo(7.0);
        assertThat(summary.kdaRatio()).isEqualTo(3.0); // (36 + 42) / 26 = 78 / 26 = 3.0
        assertThat(summary.avgNetWorth()).isEqualTo(18000L); // 108000 / 6

        // Half comparison: half = 3.
        // Recent 3: 3 wins out of 3 = 100.0%. Older 3: 0 wins out of 3 = 0.0%.
        assertThat(summary.winRateRecentHalf()).isEqualTo(100.0);
        assertThat(summary.winRateOlderHalf()).isEqualTo(0.0);
        assertThat(summary.pointDifference()).isEqualTo(100.0);
        assertThat(summary.direction()).isEqualTo(TrendDirection.IMPROVING);

        // Top heroes: hero 1 (3 games, 2 wins = 66.67%), hero 2 (2 games, 1 win = 50.0%). Hero 3 has 1 game (< 2).
        assertThat(summary.topHeroes()).hasSize(2);
        assertThat(summary.topHeroes().get(0)).isEqualTo(new HeroLine(1, 3, 200.0 / 3.0));
        assertThat(summary.topHeroes().get(1)).isEqualTo(new HeroLine(2, 2, 50.0));
    }

    @Test
    void directionThresholds() {
        // Improving: diff > +5.0 (recent 60%, older 50% -> diff = +10)
        List<MatchHistoryEntry> improving = List.of(
                // Recent 5: 3 wins, 2 losses -> 60%
                entry(1, 1, 0, 0, 5, 5, 5, 10000),
                entry(2, 1, 0, 0, 5, 5, 5, 10000),
                entry(3, 1, 0, 0, 5, 5, 5, 10000),
                entry(4, 1, 1, 0, 5, 5, 5, 10000),
                entry(5, 1, 1, 0, 5, 5, 5, 10000),
                // Older 5: 2 wins, 3 losses -> 40% (diff = +20)
                entry(6, 1, 0, 0, 5, 5, 5, 10000),
                entry(7, 1, 0, 0, 5, 5, 5, 10000),
                entry(8, 1, 1, 0, 5, 5, 5, 10000),
                entry(9, 1, 1, 0, 5, 5, 5, 10000),
                entry(10, 1, 1, 0, 5, 5, 5, 10000)
        );
        when(deadlockApiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(improving);
        TrendsSummary summaryImproving = trendsService.summarize(ACCOUNT_ID, 10);
        assertThat(summaryImproving.direction()).isEqualTo(TrendDirection.IMPROVING);
        assertThat(summaryImproving.pointDifference()).isEqualTo(20.0);

        // Steady: diff within [-5.0, 5.0] (recent 2/4 = 50%, older 2/4 = 50% -> diff = 0)
        List<MatchHistoryEntry> steady = List.of(
                entry(1, 1, 0, 0, 5, 5, 5, 10000),
                entry(2, 1, 1, 0, 5, 5, 5, 10000),
                entry(3, 1, 0, 0, 5, 5, 5, 10000),
                entry(4, 1, 1, 0, 5, 5, 5, 10000)
        );
        when(deadlockApiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(steady);
        TrendsSummary summarySteady = trendsService.summarize(ACCOUNT_ID, 4);
        assertThat(summarySteady.direction()).isEqualTo(TrendDirection.STEADY);
        assertThat(summarySteady.pointDifference()).isEqualTo(0.0);

        // Declining: diff < -5.0 (recent 1 win out of 5 = 20%, older 4 wins out of 5 = 80% -> diff = -60)
        List<MatchHistoryEntry> declining = List.of(
                entry(1, 1, 0, 0, 5, 5, 5, 10000),
                entry(2, 1, 1, 0, 5, 5, 5, 10000),
                entry(3, 1, 1, 0, 5, 5, 5, 10000),
                entry(4, 1, 1, 0, 5, 5, 5, 10000),
                entry(5, 1, 1, 0, 5, 5, 5, 10000),
                entry(6, 1, 0, 0, 5, 5, 5, 10000),
                entry(7, 1, 0, 0, 5, 5, 5, 10000),
                entry(8, 1, 0, 0, 5, 5, 5, 10000),
                entry(9, 1, 0, 0, 5, 5, 5, 10000),
                entry(10, 1, 1, 0, 5, 5, 5, 10000)
        );
        when(deadlockApiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(declining);
        TrendsSummary summaryDeclining = trendsService.summarize(ACCOUNT_ID, 10);
        assertThat(summaryDeclining.direction()).isEqualTo(TrendDirection.DECLINING);
        assertThat(summaryDeclining.pointDifference()).isEqualTo(-60.0);
    }

    @Test
    void unknownOutcomesAreSkippedAndNotCounted() {
        List<MatchHistoryEntry> history = List.of(
                entry(1L, 1, 0, 0, 10, 2, 5, 20000L), // Known win
                entry(2L, 1, -1, 0, 10, 2, 5, 20000L), // Unknown matchResult
                entry(3L, 1, 2, 0, 10, 2, 5, 20000L),  // Unknown matchResult
                entry(4L, 1, 0, -1, 10, 2, 5, 20000L), // Unknown playerTeam
                entry(5L, 1, 1, 0, 2, 4, 3, 10000L)   // Known loss
        );
        when(deadlockApiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(history);

        TrendsSummary summary = trendsService.summarize(ACCOUNT_ID, 10);

        assertThat(summary.games()).isEqualTo(2);
        assertThat(summary.winRate()).isEqualTo(50.0);
        assertThat(summary.avgKills()).isEqualTo(6.0);
        assertThat(summary.avgDeaths()).isEqualTo(3.0);
        assertThat(summary.avgAssists()).isEqualTo(4.0);
        assertThat(summary.avgNetWorth()).isEqualTo(15000L);
    }

    @Test
    void fewerThanFiveGames() {
        List<MatchHistoryEntry> history = List.of(
                entry(1L, 1, 0, 0, 5, 1, 4, 15000L),
                entry(2L, 1, 1, 1, 7, 2, 3, 18000L),
                entry(3L, 1, 1, 0, 2, 5, 1, 12000L)
        );
        when(deadlockApiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(history);

        TrendsSummary summary = trendsService.summarize(ACCOUNT_ID, 20);

        assertThat(summary.games()).isEqualTo(3);
        assertThat(summary.winRate()).isCloseTo(66.67, offset(0.01));
    }

    @Test
    void emptyHistoryReturnsZeroSummary() {
        when(deadlockApiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(List.of());

        TrendsSummary summary = trendsService.summarize(ACCOUNT_ID, 20);

        assertThat(summary.games()).isZero();
        assertThat(summary.winRate()).isEqualTo(0.0);
        assertThat(summary.topHeroes()).isEmpty();
    }

    @Test
    void respectsGamesLimit() {
        List<MatchHistoryEntry> history = List.of(
                entry(1L, 1, 0, 0, 5, 1, 4, 15000L),
                entry(2L, 1, 1, 1, 7, 2, 3, 18000L),
                entry(3L, 1, 1, 0, 2, 5, 1, 12000L),
                entry(4L, 1, 0, 0, 5, 1, 4, 15000L)
        );
        when(deadlockApiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(history);

        TrendsSummary summary = trendsService.summarize(ACCOUNT_ID, 2);

        assertThat(summary.games()).isEqualTo(2);
        assertThat(summary.winRate()).isEqualTo(100.0);
    }

    @Test
    void respectsMatchFilter() {
        List<MatchHistoryEntry> history = List.of(
                entry(1L, 1, 0, 0, 5, 1, 4, 15000L),
                entry(2L, 2, 1, 1, 7, 2, 3, 18000L),
                entry(3L, 1, 1, 0, 2, 5, 1, 12000L)
        );
        when(deadlockApiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(history);

        TrendsSummary summary = trendsService.summarize(ACCOUNT_ID, 20, new MatchFilter(null, 1, null));

        assertThat(summary.games()).isEqualTo(2);
        assertThat(summary.winRate()).isEqualTo(50.0);
    }
}
