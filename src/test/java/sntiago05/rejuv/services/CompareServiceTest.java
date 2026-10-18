package sntiago05.rejuv.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.intThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.dto.HeroAsset;
import sntiago05.rejuv.clients.dto.MatchHistoryEntry;
import sntiago05.rejuv.clients.dto.MetricPercentiles;
import sntiago05.rejuv.clients.dto.PlayerRank;
import sntiago05.rejuv.domain.CompareReport;
import sntiago05.rejuv.domain.MetricGap;

class CompareServiceTest {

    private static final long ACCOUNT_ID = 10001L;
    private static final int HERO_ID = 1;
    private static final String HERO_NAME = "Infernus";

    private DeadlockApiClient apiClient;
    private HeroResolver heroResolver;
    private GameAssets gameAssets;
    private CompareService compareService;

    @BeforeEach
    void setUp() {
        apiClient = mock(DeadlockApiClient.class);
        heroResolver = mock(HeroResolver.class);
        gameAssets = mock(GameAssets.class);
        compareService = new CompareService(apiClient, heroResolver, gameAssets);

        when(heroResolver.resolve("Infernus"))
                .thenReturn(Optional.of(new HeroAsset(HERO_ID, "infernus", HERO_NAME, null)));
        when(gameAssets.heroNames()).thenReturn(Map.of(HERO_ID, HERO_NAME));
    }

    private static MatchHistoryEntry entry(long matchId, int heroId, long startTime, int durationS,
                                           int kills, int deaths, int assists, long netWorth,
                                           int lastHits, int denies) {
        return new MatchHistoryEntry(matchId, heroId, 1, startTime, durationS, 1, 1, 1, 1,
                kills, deaths, assists, netWorth, lastHits, denies, null);
    }

    private static MetricPercentiles p(double p50) {
        return new MetricPercentiles(p50, p50, p50, p50);
    }

    private static Map<String, MetricPercentiles> topBenchmarks(double netWorth, double kills, double deaths,
                                                               double assists, double lastHits, double denies) {
        return Map.of(
                MetricsService.BENCHMARK_NET_WORTH_PER_MIN, p(netWorth),
                MetricsService.BENCHMARK_KILLS, p(kills),
                MetricsService.BENCHMARK_DEATHS, p(deaths),
                MetricsService.BENCHMARK_ASSISTS, p(assists),
                MetricsService.BENCHMARK_LAST_HITS, p(lastHits),
                MetricsService.BENCHMARK_DENIES, p(denies)
        );
    }

    @Test
    void sampleSelectionFiltersHeroCutsUnder600sCapsAt20AndSortsNewestFirst() {
        List<MatchHistoryEntry> history = new ArrayList<>();
        // 25 eligible matches for HERO_ID with duration 600s, startTime 1000..1024
        // netWorth = (startTime - 1000) * 1000, duration = 600s (10 min) -> netWorthPerMin = (startTime - 1000) * 100
        for (int i = 0; i < 25; i++) {
            history.add(entry(100 + i, HERO_ID, 1000L + i, 600, 10, 2, 5, i * 6000L, 50, 10));
        }
        // Add ineligible matches: different hero, duration < 600s
        history.add(entry(200, 2, 2000L, 1200, 20, 0, 10, 50000L, 100, 20));
        history.add(entry(201, HERO_ID, 2001L, 599, 20, 0, 10, 50000L, 100, 20));

        when(apiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(history);
        when(apiClient.getBenchmarks(HERO_ID, 11)).thenReturn(topBenchmarks(1000.0, 5.0, 5.0, 5.0, 50.0, 10.0));
        when(apiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(new PlayerRank(0, 0, 0));

        CompareReport report = compareService.compare(ACCOUNT_ID, "Infernus");

        assertThat(report.heroId()).isEqualTo(HERO_ID);
        assertThat(report.heroName()).isEqualTo(HERO_NAME);
        assertThat(report.games()).isEqualTo(20);
        assertThat(report.topTier()).isEqualTo(11);
        assertThat(report.playerRankTier()).isNull();

        // The 20 newest eligible matches are i = 5 to 24 (startTimes 1005 to 1024)
        // Duration is 600s = 10 min.
        // For each i, netWorth = i * 6000 -> netWorth / 10 = i * 600.
        // Sum of i for i in 5..24 is (5 + 24) * 20 / 2 = 290.
        // Mean of (i * 600) is 290 * 600 / 20 = 8700.0.
        MetricGap nwGap = report.gaps().stream()
                .filter(g -> g.metricKey().equals(MetricsService.BENCHMARK_NET_WORTH_PER_MIN))
                .findFirst().orElseThrow();
        assertThat(nwGap.playerValue()).isCloseTo(8700.0, within(0.001));
    }

    @Test
    void fourGamesThrowsNotEnoughGamesException() {
        List<MatchHistoryEntry> history = List.of(
                entry(1, HERO_ID, 1000L, 600, 5, 2, 3, 10000L, 50, 5),
                entry(2, HERO_ID, 1001L, 700, 6, 3, 4, 12000L, 60, 6),
                entry(3, HERO_ID, 1002L, 800, 7, 4, 5, 14000L, 70, 7),
                entry(4, HERO_ID, 1003L, 900, 8, 5, 6, 16000L, 80, 8)
        );

        when(apiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(history);

        assertThatThrownBy(() -> compareService.compare(ACCOUNT_ID, "Infernus"))
                .isInstanceOf(NotEnoughGamesException.class)
                .satisfies(ex -> {
                    NotEnoughGamesException e = (NotEnoughGamesException) ex;
                    assertThat(e.games()).isEqualTo(4);
                    assertThat(e.minRequired()).isEqualTo(5);
                });
    }

    @Test
    void fiveGamesSucceedsWithHandComputedMeansAndGaps() {
        // 5 games on Infernus
        // Game 1: duration 600s (10 min), netWorth 10000 -> 1000/min; kills 10, deaths 2, assists 5, lh 50, denies 10
        // Game 2: duration 600s (10 min), netWorth 20000 -> 2000/min; kills 5, deaths 4, assists 5, lh 60, denies 20
        // Game 3: duration 1200s (20 min), netWorth 24000 -> 1200/min; kills 15, deaths 6, assists 10, lh 100, denies 15
        // Game 4: duration 600s (10 min), netWorth 15000 -> 1500/min; kills 0, deaths 8, assists 10, lh 40, denies 5
        // Game 5: duration 900s (15 min), netWorth 27000 -> 1800/min; kills 10, deaths 0, assists 20, lh 50, denies 0
        //
        // Means:
        // netWorthPerMin: (1000 + 2000 + 1200 + 1500 + 1800) / 5 = 7500 / 5 = 1500.0
        // kills: (10 + 5 + 15 + 0 + 10) / 5 = 40 / 5 = 8.0
        // deaths: (2 + 4 + 6 + 8 + 0) / 5 = 20 / 5 = 4.0
        // assists: (5 + 5 + 10 + 10 + 20) / 5 = 50 / 5 = 10.0
        // lastHits: (50 + 60 + 100 + 40 + 50) / 5 = 300 / 5 = 60.0
        // denies: (10 + 20 + 15 + 5 + 0) / 5 = 50 / 5 = 10.0
        List<MatchHistoryEntry> history = List.of(
                entry(1, HERO_ID, 1000L, 600, 10, 2, 5, 10000L, 50, 10),
                entry(2, HERO_ID, 1001L, 600, 5, 4, 5, 20000L, 60, 20),
                entry(3, HERO_ID, 1002L, 1200, 15, 6, 10, 24000L, 100, 15),
                entry(4, HERO_ID, 1003L, 600, 0, 8, 10, 15000L, 40, 5),
                entry(5, HERO_ID, 1004L, 900, 10, 0, 20, 27000L, 50, 0)
        );

        when(apiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(history);
        when(apiClient.getBenchmarks(HERO_ID, 11)).thenReturn(topBenchmarks(1200.0, 10.0, 5.0, 8.0, 50.0, 8.0));
        when(apiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(new PlayerRank(73, 7, 3));
        when(apiClient.getBenchmarks(HERO_ID, 7)).thenReturn(topBenchmarks(1000.0, 6.0, 6.0, 6.0, 40.0, 5.0));

        CompareReport report = compareService.compare(ACCOUNT_ID, "Infernus");

        assertThat(report.games()).isEqualTo(5);
        assertThat(report.playerRankTier()).isEqualTo(7);
        assertThat(report.gaps()).hasSize(6);

        // Fixed order: souls per min, kills, deaths, assists, last hits, denies
        MetricGap nw = report.gaps().get(0);
        assertThat(nw.metricKey()).isEqualTo("net_worth_per_min");
        assertThat(nw.playerValue()).isCloseTo(1500.0, within(0.001));
        assertThat(nw.topP50()).isCloseTo(1200.0, within(0.001));
        assertThat(nw.rankP50()).isCloseTo(1000.0, within(0.001));
        // gapPercent: (1500 - 1200) / 1200 * 100 = 25.0
        assertThat(nw.gapPercent()).isCloseTo(25.0, within(0.001));
        assertThat(nw.playerBetter()).isTrue();

        MetricGap kills = report.gaps().get(1);
        assertThat(kills.metricKey()).isEqualTo("kills");
        assertThat(kills.playerValue()).isCloseTo(8.0, within(0.001));
        assertThat(kills.topP50()).isCloseTo(10.0, within(0.001));
        // gapPercent: (8 - 10) / 10 * 100 = -20.0
        assertThat(kills.gapPercent()).isCloseTo(-20.0, within(0.001));
        assertThat(kills.playerBetter()).isFalse();

        MetricGap deaths = report.gaps().get(2);
        assertThat(deaths.metricKey()).isEqualTo("deaths");
        assertThat(deaths.playerValue()).isCloseTo(4.0, within(0.001));
        assertThat(deaths.topP50()).isCloseTo(5.0, within(0.001));
        // gapPercent: (4 - 5) / 5 * 100 = -20.0
        assertThat(deaths.gapPercent()).isCloseTo(-20.0, within(0.001));
        // For deaths, lower is better: 4.0 < 5.0 -> playerBetter is true!
        assertThat(deaths.playerBetter()).isTrue();

        MetricGap assists = report.gaps().get(3);
        assertThat(assists.metricKey()).isEqualTo("assists");
        assertThat(assists.playerValue()).isCloseTo(10.0, within(0.001));
        assertThat(assists.topP50()).isCloseTo(8.0, within(0.001));
        // gapPercent: (10 - 8) / 8 * 100 = 25.0
        assertThat(assists.gapPercent()).isCloseTo(25.0, within(0.001));
        assertThat(assists.playerBetter()).isTrue();

        MetricGap lastHits = report.gaps().get(4);
        assertThat(lastHits.metricKey()).isEqualTo("last_hits");
        assertThat(lastHits.playerValue()).isCloseTo(60.0, within(0.001));
        assertThat(lastHits.topP50()).isCloseTo(50.0, within(0.001));
        // gapPercent: (60 - 50) / 50 * 100 = 20.0
        assertThat(lastHits.gapPercent()).isCloseTo(20.0, within(0.001));
        assertThat(lastHits.playerBetter()).isTrue();

        MetricGap denies = report.gaps().get(5);
        assertThat(denies.metricKey()).isEqualTo("denies");
        assertThat(denies.playerValue()).isCloseTo(10.0, within(0.001));
        assertThat(denies.topP50()).isCloseTo(8.0, within(0.001));
        // gapPercent: (10 - 8) / 8 * 100 = 25.0
        assertThat(denies.gapPercent()).isCloseTo(25.0, within(0.001));
        assertThat(denies.playerBetter()).isTrue();
    }

    @Test
    void deathsDirectionPlayerBetterWhenLowerAndWorseWhenHigher() {
        // Player with 7.0 deaths vs top median 5.0 -> worse (playerBetter = false)
        List<MatchHistoryEntry> history = List.of(
                entry(1, HERO_ID, 1000L, 600, 5, 7, 5, 10000L, 50, 10),
                entry(2, HERO_ID, 1001L, 600, 5, 7, 5, 10000L, 50, 10),
                entry(3, HERO_ID, 1002L, 600, 5, 7, 5, 10000L, 50, 10),
                entry(4, HERO_ID, 1003L, 600, 5, 7, 5, 10000L, 50, 10),
                entry(5, HERO_ID, 1004L, 600, 5, 7, 5, 10000L, 50, 10)
        );

        when(apiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(history);
        when(apiClient.getBenchmarks(HERO_ID, 11)).thenReturn(topBenchmarks(1000.0, 5.0, 5.0, 5.0, 50.0, 10.0));
        when(apiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(new PlayerRank(0, 0, 0));

        CompareReport report = compareService.compare(ACCOUNT_ID, "Infernus");

        MetricGap deathsGap = report.gaps().stream()
                .filter(g -> g.metricKey().equals(MetricsService.BENCHMARK_DEATHS))
                .findFirst().orElseThrow();
        assertThat(deathsGap.playerValue()).isEqualTo(7.0);
        assertThat(deathsGap.topP50()).isEqualTo(5.0);
        assertThat(deathsGap.gapPercent()).isCloseTo(40.0, within(0.001));
        assertThat(deathsGap.playerBetter()).isFalse();
    }

    @Test
    void metricMissingInBenchmarkMapSkipped() {
        List<MatchHistoryEntry> history = List.of(
                entry(1, HERO_ID, 1000L, 600, 5, 5, 5, 10000L, 50, 10),
                entry(2, HERO_ID, 1001L, 600, 5, 5, 5, 10000L, 50, 10),
                entry(3, HERO_ID, 1002L, 600, 5, 5, 5, 10000L, 50, 10),
                entry(4, HERO_ID, 1003L, 600, 5, 5, 5, 10000L, 50, 10),
                entry(5, HERO_ID, 1004L, 600, 5, 5, 5, 10000L, 50, 10)
        );

        Map<String, MetricPercentiles> top = new HashMap<>(topBenchmarks(1000.0, 5.0, 5.0, 5.0, 50.0, 10.0));
        top.remove(MetricsService.BENCHMARK_DENIES);

        when(apiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(history);
        when(apiClient.getBenchmarks(HERO_ID, 11)).thenReturn(top);
        when(apiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(new PlayerRank(0, 0, 0));

        CompareReport report = compareService.compare(ACCOUNT_ID, "Infernus");

        assertThat(report.gaps()).hasSize(5);
        assertThat(report.gaps()).noneMatch(g -> g.metricKey().equals(MetricsService.BENCHMARK_DENIES));
    }

    @Test
    void topP50ZeroOrNegativeSkipped() {
        List<MatchHistoryEntry> history = List.of(
                entry(1, HERO_ID, 1000L, 600, 5, 5, 5, 10000L, 50, 10),
                entry(2, HERO_ID, 1001L, 600, 5, 5, 5, 10000L, 50, 10),
                entry(3, HERO_ID, 1002L, 600, 5, 5, 5, 10000L, 50, 10),
                entry(4, HERO_ID, 1003L, 600, 5, 5, 5, 10000L, 50, 10),
                entry(5, HERO_ID, 1004L, 600, 5, 5, 5, 10000L, 50, 10)
        );

        Map<String, MetricPercentiles> top = new HashMap<>(topBenchmarks(1000.0, 5.0, 5.0, 5.0, 50.0, 10.0));
        top.put(MetricsService.BENCHMARK_DENIES, p(0.0));
        top.put(MetricsService.BENCHMARK_LAST_HITS, p(-5.0));

        when(apiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(history);
        when(apiClient.getBenchmarks(HERO_ID, 11)).thenReturn(top);
        when(apiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(new PlayerRank(0, 0, 0));

        CompareReport report = compareService.compare(ACCOUNT_ID, "Infernus");

        assertThat(report.gaps()).hasSize(4);
        assertThat(report.gaps()).noneMatch(g -> g.metricKey().equals(MetricsService.BENCHMARK_DENIES)
                || g.metricKey().equals(MetricsService.BENCHMARK_LAST_HITS));
    }

    @Test
    void emptyTopBenchmarksReturnsReportWithEmptyGaps() {
        List<MatchHistoryEntry> history = List.of(
                entry(1, HERO_ID, 1000L, 600, 5, 5, 5, 10000L, 50, 10),
                entry(2, HERO_ID, 1001L, 600, 5, 5, 5, 10000L, 50, 10),
                entry(3, HERO_ID, 1002L, 600, 5, 5, 5, 10000L, 50, 10),
                entry(4, HERO_ID, 1003L, 600, 5, 5, 5, 10000L, 50, 10),
                entry(5, HERO_ID, 1004L, 600, 5, 5, 5, 10000L, 50, 10)
        );

        when(apiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(history);
        when(apiClient.getBenchmarks(HERO_ID, 11)).thenReturn(Map.of());

        CompareReport report = compareService.compare(ACCOUNT_ID, "Infernus");

        assertThat(report.gaps()).isEmpty();
        assertThat(report.games()).isEqualTo(5);
        verify(apiClient, never()).getPlayerRank(ACCOUNT_ID);
    }

    @Test
    void rankReferencePresentWhenRankIsBetween1And10() {
        List<MatchHistoryEntry> history = List.of(
                entry(1, HERO_ID, 1000L, 600, 5, 5, 5, 10000L, 50, 10),
                entry(2, HERO_ID, 1001L, 600, 5, 5, 5, 10000L, 50, 10),
                entry(3, HERO_ID, 1002L, 600, 5, 5, 5, 10000L, 50, 10),
                entry(4, HERO_ID, 1003L, 600, 5, 5, 5, 10000L, 50, 10),
                entry(5, HERO_ID, 1004L, 600, 5, 5, 5, 10000L, 50, 10)
        );

        when(apiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(history);
        when(apiClient.getBenchmarks(HERO_ID, 11)).thenReturn(topBenchmarks(1000.0, 5.0, 5.0, 5.0, 50.0, 10.0));
        when(apiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(new PlayerRank(52, 5, 2));
        when(apiClient.getBenchmarks(HERO_ID, 5)).thenReturn(topBenchmarks(800.0, 4.0, 6.0, 4.0, 40.0, 5.0));

        CompareReport report = compareService.compare(ACCOUNT_ID, "Infernus");

        assertThat(report.playerRankTier()).isEqualTo(5);
        verify(apiClient).getBenchmarks(HERO_ID, 5);
        assertThat(report.gaps().get(0).rankP50()).isEqualTo(800.0);
    }

    @Test
    void rankReferenceAbsentWhenUnranked() {
        List<MatchHistoryEntry> history = List.of(
                entry(1, HERO_ID, 1000L, 600, 5, 5, 5, 10000L, 50, 10),
                entry(2, HERO_ID, 1001L, 600, 5, 5, 5, 10000L, 50, 10),
                entry(3, HERO_ID, 1002L, 600, 5, 5, 5, 10000L, 50, 10),
                entry(4, HERO_ID, 1003L, 600, 5, 5, 5, 10000L, 50, 10),
                entry(5, HERO_ID, 1004L, 600, 5, 5, 5, 10000L, 50, 10)
        );

        when(apiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(history);
        when(apiClient.getBenchmarks(HERO_ID, 11)).thenReturn(topBenchmarks(1000.0, 5.0, 5.0, 5.0, 50.0, 10.0));
        when(apiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(new PlayerRank(0, 0, 0));

        CompareReport report = compareService.compare(ACCOUNT_ID, "Infernus");

        assertThat(report.playerRankTier()).isNull();
        assertThat(report.gaps().get(0).rankP50()).isNull();
        verify(apiClient).getBenchmarks(HERO_ID, 11);
        verify(apiClient, never()).getBenchmarks(eq(HERO_ID), intThat(tier -> tier != 11));
    }

    @Test
    void rankReferenceAbsentWhenTier11() {
        List<MatchHistoryEntry> history = List.of(
                entry(1, HERO_ID, 1000L, 600, 5, 5, 5, 10000L, 50, 10),
                entry(2, HERO_ID, 1001L, 600, 5, 5, 5, 10000L, 50, 10),
                entry(3, HERO_ID, 1002L, 600, 5, 5, 5, 10000L, 50, 10),
                entry(4, HERO_ID, 1003L, 600, 5, 5, 5, 10000L, 50, 10),
                entry(5, HERO_ID, 1004L, 600, 5, 5, 5, 10000L, 50, 10)
        );

        when(apiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(history);
        when(apiClient.getBenchmarks(HERO_ID, 11)).thenReturn(topBenchmarks(1000.0, 5.0, 5.0, 5.0, 50.0, 10.0));
        when(apiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(new PlayerRank(111, 11, 1));

        CompareReport report = compareService.compare(ACCOUNT_ID, "Infernus");

        assertThat(report.playerRankTier()).isNull();
        assertThat(report.gaps().get(0).rankP50()).isNull();
    }

    @Test
    void rankReferenceAbsentWhenTier0() {
        List<MatchHistoryEntry> history = List.of(
                entry(1, HERO_ID, 1000L, 600, 5, 5, 5, 10000L, 50, 10),
                entry(2, HERO_ID, 1001L, 600, 5, 5, 5, 10000L, 50, 10),
                entry(3, HERO_ID, 1002L, 600, 5, 5, 5, 10000L, 50, 10),
                entry(4, HERO_ID, 1003L, 600, 5, 5, 5, 10000L, 50, 10),
                entry(5, HERO_ID, 1004L, 600, 5, 5, 5, 10000L, 50, 10)
        );

        when(apiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(history);
        when(apiClient.getBenchmarks(HERO_ID, 11)).thenReturn(topBenchmarks(1000.0, 5.0, 5.0, 5.0, 50.0, 10.0));
        when(apiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(new PlayerRank(1, 0, 1));

        CompareReport report = compareService.compare(ACCOUNT_ID, "Infernus");

        assertThat(report.playerRankTier()).isNull();
        assertThat(report.gaps().get(0).rankP50()).isNull();
    }

    @Test
    void blankHeroUsesNewestHistoryEntry() {
        int heroSevenId = 2;
        when(gameAssets.heroNames()).thenReturn(Map.of(HERO_ID, HERO_NAME, heroSevenId, "Seven"));

        List<MatchHistoryEntry> history = List.of(
                entry(1, heroSevenId, 2000L, 700, 8, 2, 8, 12000L, 70, 12),
                entry(2, heroSevenId, 1900L, 650, 7, 3, 7, 11000L, 65, 11),
                entry(3, heroSevenId, 1800L, 600, 6, 4, 6, 10000L, 60, 10),
                entry(4, heroSevenId, 1700L, 800, 9, 1, 9, 13000L, 75, 13),
                entry(5, heroSevenId, 1600L, 900, 10, 0, 10, 14000L, 80, 14),
                entry(6, HERO_ID, 1000L, 600, 5, 5, 5, 10000L, 50, 10)
        );

        when(apiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(history);
        when(apiClient.getBenchmarks(heroSevenId, 11)).thenReturn(topBenchmarks(1000.0, 5.0, 5.0, 5.0, 50.0, 10.0));
        when(apiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(new PlayerRank(0, 0, 0));

        CompareReport report = compareService.compare(ACCOUNT_ID, "  ");

        assertThat(report.heroId()).isEqualTo(heroSevenId);
        assertThat(report.heroName()).isEqualTo("Seven");
        assertThat(report.games()).isEqualTo(5);
    }

    @Test
    void unknownHeroThrowsHeroNotFoundException() {
        when(heroResolver.resolve("UnknownHero")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> compareService.compare(ACCOUNT_ID, "UnknownHero"))
                .isInstanceOf(HeroNotFoundException.class)
                .hasMessageContaining("UnknownHero");
    }

    @Test
    void emptyHistoryThrowsNoMatchHistoryException() {
        when(apiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> compareService.compare(ACCOUNT_ID, "Infernus"))
                .isInstanceOf(NoMatchHistoryException.class);

        assertThatThrownBy(() -> compareService.compare(ACCOUNT_ID, null))
                .isInstanceOf(NoMatchHistoryException.class);
    }
}
