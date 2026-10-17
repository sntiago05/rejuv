package sntiago05.rejuv.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.DeadlockApiException;
import sntiago05.rejuv.clients.DeadlockApiUnavailableException;
import sntiago05.rejuv.clients.dto.HeroStat;
import sntiago05.rejuv.clients.dto.PlayerHeroStat;
import sntiago05.rejuv.clients.dto.PlayerRank;
import sntiago05.rejuv.domain.HeroPerformance;
import sntiago05.rejuv.domain.HeroPoolSummary;

class HeroPoolServiceTest {

    private static final long ACCOUNT_ID = 1000001L;
    private static final Instant FIXED_INSTANT = Instant.parse("2026-10-17T15:30:00Z");
    private static final long PLAYER_WINDOW_START = Instant.parse("2026-07-19T00:00:00Z").getEpochSecond();
    private static final long BENCHMARK_WINDOW_START = Instant.parse("2026-09-17T00:00:00Z").getEpochSecond();

    private DeadlockApiClient deadlockApiClient;
    private GameAssets gameAssets;
    private Clock clock;
    private HeroPoolService service;

    @BeforeEach
    void setUp() {
        deadlockApiClient = mock(DeadlockApiClient.class);
        gameAssets = mock(GameAssets.class);
        when(gameAssets.heroNames()).thenReturn(Map.of(
                1, "Infernus",
                2, "Seven",
                3, "Vindicta",
                4, "Lady Geist",
                5, "Abrams",
                6, "Wraith",
                7, "Pocket"
        ));
        clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        service = new HeroPoolService(deadlockApiClient, gameAssets, clock);
    }

    @Test
    void emptyPlayerStatsReturnsEmptySummaryAndNeverCallsRankOrBenchmark() {
        when(deadlockApiClient.getPlayerHeroStats(ACCOUNT_ID, PLAYER_WINDOW_START)).thenReturn(List.of());

        HeroPoolSummary summary = service.getHeroPool(ACCOUNT_ID);

        assertThat(summary.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(summary.rankTier()).isNull();
        assertThat(summary.topHeroes()).isEmpty();
        assertThat(summary.best()).isNull();
        assertThat(summary.weakest()).isNull();

        verify(deadlockApiClient, never()).getPlayerRank(anyLong());
        verify(deadlockApiClient, never()).getHeroStats(any(), anyLong());
    }

    @Test
    void rankedPlayerUsesRankTierForBenchmark() {
        when(deadlockApiClient.getPlayerHeroStats(ACCOUNT_ID, PLAYER_WINDOW_START)).thenReturn(List.of(
                new PlayerHeroStat(1, 15, 10, 80, 40, 100)
        ));
        when(deadlockApiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(new PlayerRank(74, 7, 4));
        when(deadlockApiClient.getHeroStats(eq(7), eq(BENCHMARK_WINDOW_START))).thenReturn(List.of(
                new HeroStat(1, 1000, 500, 500, 6000, 5000, 8000)
        ));

        HeroPoolSummary summary = service.getHeroPool(ACCOUNT_ID);

        assertThat(summary.rankTier()).isEqualTo(7);
        verify(deadlockApiClient).getHeroStats(7, BENCHMARK_WINDOW_START);
    }

    @Test
    void unrankedPlayerBadgeZeroUsesNullRankTierForBenchmark() {
        when(deadlockApiClient.getPlayerHeroStats(ACCOUNT_ID, PLAYER_WINDOW_START)).thenReturn(List.of(
                new PlayerHeroStat(1, 15, 10, 80, 40, 100)
        ));
        when(deadlockApiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(new PlayerRank(0, 0, 0));
        when(deadlockApiClient.getHeroStats(isNull(), eq(BENCHMARK_WINDOW_START))).thenReturn(List.of(
                new HeroStat(1, 1000, 500, 500, 6000, 5000, 8000)
        ));

        HeroPoolSummary summary = service.getHeroPool(ACCOUNT_ID);

        assertThat(summary.rankTier()).isNull();
        verify(deadlockApiClient).getHeroStats(null, BENCHMARK_WINDOW_START);
    }

    @Test
    void winRateKdaIncludingZeroDeathsAndDelta() {
        when(deadlockApiClient.getPlayerHeroStats(ACCOUNT_ID, PLAYER_WINDOW_START)).thenReturn(List.of(
                new PlayerHeroStat(1, 10, 6, 30, 0, 10) // 0 deaths -> max(0, 1) = 1 -> KDA = 40.0
        ));
        when(deadlockApiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(new PlayerRank(0, 0, 0));
        when(deadlockApiClient.getHeroStats(isNull(), eq(BENCHMARK_WINDOW_START))).thenReturn(List.of(
                new HeroStat(1, 200, 100, 100, 1000, 500, 1500) // avgWinRate = 50.0, avgKda = 2500 / 500 = 5.0
        ));

        HeroPoolSummary summary = service.getHeroPool(ACCOUNT_ID);

        assertThat(summary.topHeroes()).hasSize(1);
        HeroPerformance perf = summary.topHeroes().get(0);
        assertThat(perf.heroId()).isEqualTo(1);
        assertThat(perf.heroName()).isEqualTo("Infernus");
        assertThat(perf.matches()).isEqualTo(10);
        assertThat(perf.wins()).isEqualTo(6);
        assertThat(perf.winRatePercent()).isEqualTo(60.0);
        assertThat(perf.kda()).isEqualTo(40.0);
        assertThat(perf.heroAvgWinRatePercent()).isEqualTo(50.0);
        assertThat(perf.deltaPoints()).isEqualTo(10.0);
        assertThat(perf.heroAvgKda()).isEqualTo(5.0);
    }

    @Test
    void benchmarkKdaNullWhenBenchmarkTotalDeathsIsZero() {
        when(deadlockApiClient.getPlayerHeroStats(ACCOUNT_ID, PLAYER_WINDOW_START)).thenReturn(List.of(
                new PlayerHeroStat(1, 10, 6, 30, 5, 10)
        ));
        when(deadlockApiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(new PlayerRank(0, 0, 0));
        when(deadlockApiClient.getHeroStats(isNull(), eq(BENCHMARK_WINDOW_START))).thenReturn(List.of(
                new HeroStat(1, 200, 100, 100, 1000, 0, 1500)
        ));

        HeroPoolSummary summary = service.getHeroPool(ACCOUNT_ID);

        HeroPerformance perf = summary.topHeroes().get(0);
        assertThat(perf.heroAvgWinRatePercent()).isEqualTo(50.0);
        assertThat(perf.deltaPoints()).isEqualTo(10.0);
        assertThat(perf.heroAvgKda()).isNull();
    }

    @Test
    void benchmarkRowBelowMinMatchesGivesNullBenchmarkValues() {
        when(deadlockApiClient.getPlayerHeroStats(ACCOUNT_ID, PLAYER_WINDOW_START)).thenReturn(List.of(
                new PlayerHeroStat(1, 10, 6, 30, 5, 10)
        ));
        when(deadlockApiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(new PlayerRank(0, 0, 0));
        when(deadlockApiClient.getHeroStats(isNull(), eq(BENCHMARK_WINDOW_START))).thenReturn(List.of(
                new HeroStat(1, 99, 50, 49, 1000, 500, 1500) // below MIN_BENCHMARK_MATCHES (100)
        ));

        HeroPoolSummary summary = service.getHeroPool(ACCOUNT_ID);

        HeroPerformance perf = summary.topHeroes().get(0);
        assertThat(perf.heroAvgWinRatePercent()).isNull();
        assertThat(perf.deltaPoints()).isNull();
        assertThat(perf.heroAvgKda()).isNull();
    }

    @Test
    void topHeroesOrderMatchesDescThenHeroIdAscAndTruncatesToFive() {
        when(deadlockApiClient.getPlayerHeroStats(ACCOUNT_ID, PLAYER_WINDOW_START)).thenReturn(List.of(
                new PlayerHeroStat(7, 5, 2, 10, 10, 10),
                new PlayerHeroStat(6, 10, 5, 10, 10, 10),
                new PlayerHeroStat(5, 10, 5, 10, 10, 10), // tie with hero 6, heroId 5 < 6
                new PlayerHeroStat(4, 20, 10, 10, 10, 10),
                new PlayerHeroStat(3, 30, 15, 10, 10, 10),
                new PlayerHeroStat(2, 40, 20, 10, 10, 10),
                new PlayerHeroStat(1, 50, 25, 10, 10, 10)
        ));
        when(deadlockApiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(new PlayerRank(0, 0, 0));
        when(deadlockApiClient.getHeroStats(isNull(), eq(BENCHMARK_WINDOW_START))).thenReturn(List.of());

        HeroPoolSummary summary = service.getHeroPool(ACCOUNT_ID);

        assertThat(summary.topHeroes()).extracting(HeroPerformance::heroId)
                .containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void bestAndWeakestSelectionFromAllHeroesIncludingOutsideTopFive() {
        // 6 heroes: heroes 1..5 have 20 matches each and delta +1.0.
        // Hero 6 has 12 matches (outside top 5) and delta +15.0 -> best!
        // Hero 7 has 11 matches (outside top 5) and delta -10.0 -> weakest!
        when(deadlockApiClient.getPlayerHeroStats(ACCOUNT_ID, PLAYER_WINDOW_START)).thenReturn(List.of(
                new PlayerHeroStat(1, 20, 10, 50, 50, 50),
                new PlayerHeroStat(2, 20, 10, 50, 50, 50),
                new PlayerHeroStat(3, 20, 10, 50, 50, 50),
                new PlayerHeroStat(4, 20, 10, 50, 50, 50),
                new PlayerHeroStat(5, 20, 10, 50, 50, 50),
                new PlayerHeroStat(6, 12, 10, 50, 50, 50), // 10/12 = 83.33% win rate
                new PlayerHeroStat(7, 11, 2, 50, 50, 50)   // 2/11 = 18.18% win rate
        ));
        when(deadlockApiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(new PlayerRank(0, 0, 0));
        when(deadlockApiClient.getHeroStats(isNull(), eq(BENCHMARK_WINDOW_START))).thenReturn(List.of(
                new HeroStat(1, 1000, 500, 500, 5000, 5000, 5000), // benchmark 50%
                new HeroStat(2, 1000, 500, 500, 5000, 5000, 5000),
                new HeroStat(3, 1000, 500, 500, 5000, 5000, 5000),
                new HeroStat(4, 1000, 500, 500, 5000, 5000, 5000),
                new HeroStat(5, 1000, 500, 500, 5000, 5000, 5000),
                new HeroStat(6, 1000, 500, 500, 5000, 5000, 5000),
                new HeroStat(7, 1000, 500, 500, 5000, 5000, 5000)
        ));

        HeroPoolSummary summary = service.getHeroPool(ACCOUNT_ID);

        assertThat(summary.topHeroes()).extracting(HeroPerformance::heroId)
                .containsExactly(1, 2, 3, 4, 5);
        assertThat(summary.best()).isNotNull();
        assertThat(summary.best().heroId()).isEqualTo(6);
        assertThat(summary.weakest()).isNotNull();
        assertThat(summary.weakest().heroId()).isEqualTo(7);
    }

    @Test
    void tipsIgnoreHeroesUnderTenMatches() {
        when(deadlockApiClient.getPlayerHeroStats(ACCOUNT_ID, PLAYER_WINDOW_START)).thenReturn(List.of(
                new PlayerHeroStat(1, 9, 9, 50, 10, 50), // delta +50.0 but matches 9 < 10
                new PlayerHeroStat(2, 10, 7, 50, 10, 50), // delta +20.0, matches 10 >= 10
                new PlayerHeroStat(3, 9, 0, 10, 50, 10)  // delta -50.0 but matches 9 < 10
        ));
        when(deadlockApiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(new PlayerRank(0, 0, 0));
        when(deadlockApiClient.getHeroStats(isNull(), eq(BENCHMARK_WINDOW_START))).thenReturn(List.of(
                new HeroStat(1, 1000, 500, 500, 5000, 5000, 5000),
                new HeroStat(2, 1000, 500, 500, 5000, 5000, 5000),
                new HeroStat(3, 1000, 500, 500, 5000, 5000, 5000)
        ));

        HeroPoolSummary summary = service.getHeroPool(ACCOUNT_ID);

        assertThat(summary.best()).isNotNull();
        assertThat(summary.best().heroId()).isEqualTo(2);
        assertThat(summary.weakest()).isNull();
    }

    @Test
    void tipsTieBreakingPrefersMoreMatchesThenLowerHeroId() {
        // Best candidates with delta +10.0:
        // hero 1: matches 15
        // hero 2: matches 20 (more matches than 1)
        // hero 3: matches 20 (tied with 2, but heroId 2 < 3)
        // Weakest candidates with delta -10.0:
        // hero 4: matches 15
        // hero 5: matches 20 (more matches than 4)
        // hero 6: matches 20 (tied with 5, but heroId 5 < 6)
        when(deadlockApiClient.getPlayerHeroStats(ACCOUNT_ID, PLAYER_WINDOW_START)).thenReturn(List.of(
                new PlayerHeroStat(1, 15, 9, 50, 50, 50), // 60% vs 50% = +10.0
                new PlayerHeroStat(2, 20, 12, 50, 50, 50), // 60% vs 50% = +10.0
                new PlayerHeroStat(3, 20, 12, 50, 50, 50), // 60% vs 50% = +10.0
                new PlayerHeroStat(4, 15, 6, 50, 50, 50), // 40% vs 50% = -10.0
                new PlayerHeroStat(5, 20, 8, 50, 50, 50), // 40% vs 50% = -10.0
                new PlayerHeroStat(6, 20, 8, 50, 50, 50)  // 40% vs 50% = -10.0
        ));
        when(deadlockApiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(new PlayerRank(0, 0, 0));
        when(deadlockApiClient.getHeroStats(isNull(), eq(BENCHMARK_WINDOW_START))).thenReturn(List.of(
                new HeroStat(1, 1000, 500, 500, 5000, 5000, 5000),
                new HeroStat(2, 1000, 500, 500, 5000, 5000, 5000),
                new HeroStat(3, 1000, 500, 500, 5000, 5000, 5000),
                new HeroStat(4, 1000, 500, 500, 5000, 5000, 5000),
                new HeroStat(5, 1000, 500, 500, 5000, 5000, 5000),
                new HeroStat(6, 1000, 500, 500, 5000, 5000, 5000)
        ));

        HeroPoolSummary summary = service.getHeroPool(ACCOUNT_ID);

        assertThat(summary.best()).isNotNull();
        assertThat(summary.best().heroId()).isEqualTo(2);
        assertThat(summary.weakest()).isNotNull();
        assertThat(summary.weakest().heroId()).isEqualTo(5);
    }

    @Test
    void tipsNoneAvailableWhenNoEligibleHero() {
        when(deadlockApiClient.getPlayerHeroStats(ACCOUNT_ID, PLAYER_WINDOW_START)).thenReturn(List.of(
                new PlayerHeroStat(1, 20, 10, 50, 50, 50) // 50% vs 50% = delta 0.0 (neither > 0 nor < 0)
        ));
        when(deadlockApiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(new PlayerRank(0, 0, 0));
        when(deadlockApiClient.getHeroStats(isNull(), eq(BENCHMARK_WINDOW_START))).thenReturn(List.of(
                new HeroStat(1, 1000, 500, 500, 5000, 5000, 5000)
        ));

        HeroPoolSummary summary = service.getHeroPool(ACCOUNT_ID);

        assertThat(summary.best()).isNull();
        assertThat(summary.weakest()).isNull();
    }

    @Test
    void heroAbsentFromAssetsHasNullName() {
        when(deadlockApiClient.getPlayerHeroStats(ACCOUNT_ID, PLAYER_WINDOW_START)).thenReturn(List.of(
                new PlayerHeroStat(999, 10, 5, 20, 20, 20)
        ));
        when(deadlockApiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(new PlayerRank(0, 0, 0));
        when(deadlockApiClient.getHeroStats(isNull(), eq(BENCHMARK_WINDOW_START))).thenReturn(List.of());

        HeroPoolSummary summary = service.getHeroPool(ACCOUNT_ID);

        assertThat(summary.topHeroes().get(0).heroName()).isNull();
    }

    @Test
    void duplicatedHeroRowsInEitherListKeepsFirst() {
        when(deadlockApiClient.getPlayerHeroStats(ACCOUNT_ID, PLAYER_WINDOW_START)).thenReturn(List.of(
                new PlayerHeroStat(1, 20, 10, 50, 50, 50),
                new PlayerHeroStat(1, 50, 40, 100, 10, 100) // duplicate, should be ignored
        ));
        when(deadlockApiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(new PlayerRank(0, 0, 0));
        when(deadlockApiClient.getHeroStats(isNull(), eq(BENCHMARK_WINDOW_START))).thenReturn(List.of(
                new HeroStat(1, 1000, 600, 400, 6000, 5000, 6000), // win rate 60%
                new HeroStat(1, 2000, 800, 1200, 8000, 5000, 8000) // duplicate, should be ignored
        ));

        HeroPoolSummary summary = service.getHeroPool(ACCOUNT_ID);

        assertThat(summary.topHeroes()).hasSize(1);
        HeroPerformance perf = summary.topHeroes().get(0);
        assertThat(perf.matches()).isEqualTo(20);
        assertThat(perf.heroAvgWinRatePercent()).isEqualTo(60.0);
    }

    @Test
    void windowsComputedFromInjectedClock() {
        when(deadlockApiClient.getPlayerHeroStats(ACCOUNT_ID, PLAYER_WINDOW_START)).thenReturn(List.of(
                new PlayerHeroStat(1, 10, 5, 10, 10, 10)
        ));
        when(deadlockApiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(new PlayerRank(0, 0, 0));
        when(deadlockApiClient.getHeroStats(isNull(), eq(BENCHMARK_WINDOW_START))).thenReturn(List.of());

        service.getHeroPool(ACCOUNT_ID);

        verify(deadlockApiClient).getPlayerHeroStats(ACCOUNT_ID, PLAYER_WINDOW_START);
        verify(deadlockApiClient).getHeroStats(null, BENCHMARK_WINDOW_START);
    }

    @Test
    void propagatesUnavailableException() {
        when(deadlockApiClient.getPlayerHeroStats(anyLong(), anyLong()))
                .thenThrow(new DeadlockApiUnavailableException("busy", Duration.ofSeconds(5), null));

        assertThatThrownBy(() -> service.getHeroPool(ACCOUNT_ID))
                .isInstanceOf(DeadlockApiUnavailableException.class);
    }

    @Test
    void propagatesApiException() {
        when(deadlockApiClient.getPlayerHeroStats(anyLong(), anyLong()))
                .thenThrow(new DeadlockApiException("failure"));

        assertThatThrownBy(() -> service.getHeroPool(ACCOUNT_ID))
                .isInstanceOf(DeadlockApiException.class);
    }
}
