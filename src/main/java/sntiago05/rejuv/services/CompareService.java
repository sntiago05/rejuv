package sntiago05.rejuv.services;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.dto.HeroAsset;
import sntiago05.rejuv.clients.dto.MatchHistoryEntry;
import sntiago05.rejuv.clients.dto.MetricPercentiles;
import sntiago05.rejuv.clients.dto.PlayerRank;
import sntiago05.rejuv.domain.CompareReport;
import sntiago05.rejuv.domain.MetricGap;

@Service
public class CompareService {

    public static final int MIN_DURATION_S = 600;
    public static final int MIN_GAMES = 5;
    public static final int MAX_GAMES = 20;
    public static final int TOP_TIER = 11;

    private static final List<String> METRIC_KEYS = List.of(
            MetricsService.BENCHMARK_NET_WORTH_PER_MIN,
            MetricsService.BENCHMARK_KILLS,
            MetricsService.BENCHMARK_DEATHS,
            MetricsService.BENCHMARK_ASSISTS,
            MetricsService.BENCHMARK_LAST_HITS,
            MetricsService.BENCHMARK_DENIES
    );

    private final DeadlockApiClient deadlockApiClient;
    private final HeroResolver heroResolver;
    private final GameAssets gameAssets;

    public CompareService(DeadlockApiClient deadlockApiClient,
                          HeroResolver heroResolver,
                          GameAssets gameAssets) {
        this.deadlockApiClient = deadlockApiClient;
        this.heroResolver = heroResolver;
        this.gameAssets = gameAssets;
    }

    /**
     * Compares the player's recent matches on a hero with tier 11 benchmarks and their own rank tier benchmarks.
     *
     * @param accountId Deadlock account ID
     * @param heroInput hero name (or prefix), or null/blank to use the hero of the latest match
     * @return comparison report
     * @throws HeroNotFoundException if heroInput cannot be resolved
     * @throws NoMatchHistoryException if the account has no match history
     * @throws NotEnoughGamesException if the player has fewer than 5 matches of 600+ seconds on the hero
     */
    public CompareReport compare(long accountId, String heroInput) {
        int heroId;
        String heroName;

        if (heroInput != null && !heroInput.isBlank()) {
            HeroAsset hero = heroResolver.resolve(heroInput)
                    .orElseThrow(() -> new HeroNotFoundException(heroInput));
            heroId = hero.id();
            heroName = hero.name();

            List<MatchHistoryEntry> history = deadlockApiClient.getMatchHistory(accountId);
            if (history.isEmpty()) {
                throw new NoMatchHistoryException(accountId);
            }
            return buildReport(accountId, heroId, heroName, history);
        } else {
            List<MatchHistoryEntry> history = deadlockApiClient.getMatchHistory(accountId);
            if (history.isEmpty()) {
                throw new NoMatchHistoryException(accountId);
            }
            MatchHistoryEntry latest = history.stream()
                    .reduce((newest, entry) -> entry.startTime() > newest.startTime() ? entry : newest)
                    .orElseThrow(() -> new NoMatchHistoryException(accountId));
            heroId = latest.heroId();
            heroName = gameAssets.heroNames().getOrDefault(heroId, "Hero " + heroId);
            return buildReport(accountId, heroId, heroName, history);
        }
    }

    private CompareReport buildReport(long accountId, int heroId, String heroName, List<MatchHistoryEntry> history) {
        List<MatchHistoryEntry> sample = history.stream()
                .filter(entry -> entry.heroId() == heroId && entry.matchDurationS() >= MIN_DURATION_S)
                .sorted(Comparator.comparingLong(MatchHistoryEntry::startTime).reversed())
                .limit(MAX_GAMES)
                .toList();

        if (sample.size() < MIN_GAMES) {
            throw new NotEnoughGamesException(sample.size(), MIN_GAMES);
        }

        double sumNetWorthPerMin = 0.0;
        double sumKills = 0.0;
        double sumDeaths = 0.0;
        double sumAssists = 0.0;
        double sumLastHits = 0.0;
        double sumDenies = 0.0;

        for (MatchHistoryEntry entry : sample) {
            double durationMin = entry.matchDurationS() / 60.0;
            sumNetWorthPerMin += entry.netWorth() / durationMin;
            sumKills += entry.playerKills();
            sumDeaths += entry.playerDeaths();
            sumAssists += entry.playerAssists();
            sumLastHits += entry.lastHits();
            sumDenies += entry.denies();
        }

        int n = sample.size();
        double playerNetWorthPerMin = sumNetWorthPerMin / n;
        double playerKills = sumKills / n;
        double playerDeaths = sumDeaths / n;
        double playerAssists = sumAssists / n;
        double playerLastHits = sumLastHits / n;
        double playerDenies = sumDenies / n;

        Map<String, MetricPercentiles> topBenchmarks = deadlockApiClient.getBenchmarks(heroId, TOP_TIER);
        if (topBenchmarks == null || topBenchmarks.isEmpty()) {
            return new CompareReport(heroId, heroName, n, TOP_TIER, null, List.of());
        }

        PlayerRank playerRank = deadlockApiClient.getPlayerRank(accountId);
        Integer playerRankTier = null;
        Map<String, MetricPercentiles> rankBenchmarks = Map.of();
        if (playerRank != null && playerRank.badge() > 0 && playerRank.rank() >= 1 && playerRank.rank() <= 10) {
            playerRankTier = playerRank.rank();
            Map<String, MetricPercentiles> benchmarks = deadlockApiClient.getBenchmarks(heroId, playerRankTier);
            if (benchmarks != null) {
                rankBenchmarks = benchmarks;
            }
        }

        List<MetricGap> gaps = new ArrayList<>();
        for (String metricKey : METRIC_KEYS) {
            MetricPercentiles topPercentiles = topBenchmarks.get(metricKey);
            if (topPercentiles == null) {
                continue;
            }

            double topP50 = topPercentiles.p50();
            if (topP50 <= 0.0) {
                continue;
            }

            double playerValue = playerValueFor(metricKey, playerNetWorthPerMin, playerKills, playerDeaths,
                    playerAssists, playerLastHits, playerDenies);
            double gapPercent = (playerValue - topP50) / topP50 * 100.0;
            boolean playerBetter = metricKey.equals(MetricsService.BENCHMARK_DEATHS)
                    ? playerValue < topP50
                    : playerValue > topP50;

            MetricPercentiles rankPercentiles = rankBenchmarks.get(metricKey);
            Double rankP50 = rankPercentiles != null ? rankPercentiles.p50() : null;

            gaps.add(new MetricGap(metricKey, playerValue, topP50, rankP50, gapPercent, playerBetter));
        }

        return new CompareReport(heroId, heroName, n, TOP_TIER, playerRankTier, List.copyOf(gaps));
    }

    private double playerValueFor(String metricKey, double netWorthPerMin, double kills, double deaths,
                                  double assists, double lastHits, double denies) {
        return switch (metricKey) {
            case MetricsService.BENCHMARK_NET_WORTH_PER_MIN -> netWorthPerMin;
            case MetricsService.BENCHMARK_KILLS -> kills;
            case MetricsService.BENCHMARK_DEATHS -> deaths;
            case MetricsService.BENCHMARK_ASSISTS -> assists;
            case MetricsService.BENCHMARK_LAST_HITS -> lastHits;
            case MetricsService.BENCHMARK_DENIES -> denies;
            default -> throw new IllegalArgumentException("Unknown metric: " + metricKey);
        };
    }
}
