package sntiago05.rejuv.services;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.dto.HeroStat;
import sntiago05.rejuv.clients.dto.PlayerHeroStat;
import sntiago05.rejuv.clients.dto.PlayerRank;
import sntiago05.rejuv.domain.HeroPerformance;
import sntiago05.rejuv.domain.HeroPoolSummary;

@Service
public class HeroPoolService {

    static final int TOP_HEROES = 5;
    static final int MIN_MATCHES_FOR_TIP = 10;
    public static final int MIN_BENCHMARK_MATCHES = 100;
    static final Duration PLAYER_WINDOW = Duration.ofDays(90);

    private final DeadlockApiClient deadlockApiClient;
    private final GameAssets gameAssets;
    private final Clock clock;

    @Autowired
    public HeroPoolService(DeadlockApiClient deadlockApiClient, GameAssets gameAssets) {
        this(deadlockApiClient, gameAssets, Clock.systemUTC());
    }

    HeroPoolService(DeadlockApiClient deadlockApiClient, GameAssets gameAssets, Clock clock) {
        this.deadlockApiClient = deadlockApiClient;
        this.gameAssets = gameAssets;
        this.clock = clock;
    }

    /** Lets DeadlockApiUnavailableException / DeadlockApiException propagate. */
    public HeroPoolSummary getHeroPool(long accountId) {
        long playerStart = ServiceSupport.startOfUtcDayMinus(clock, PLAYER_WINDOW);
        List<PlayerHeroStat> stats = deadlockApiClient.getPlayerHeroStats(accountId, playerStart);
        if (stats.isEmpty()) {
            return new HeroPoolSummary(accountId, null, List.of(), null, null);
        }

        PlayerRank rank = deadlockApiClient.getPlayerRank(accountId);
        Integer rankTier = (rank.badge() != 0 && rank.rank() >= ServiceSupport.MIN_RANK_TIER && rank.rank() <= ServiceSupport.MAX_RANK_TIER)
                ? rank.rank()
                : null;

        long benchmarkStart = ServiceSupport.startOfUtcDayMinus(clock, ServiceSupport.WINDOW);
        List<HeroStat> benchmarkStats = deadlockApiClient.getHeroStats(rankTier, benchmarkStart);

        record Benchmark(double avgWinRate, Double avgKda) { }
        Set<Integer> seenBenchmark = new HashSet<>();
        Map<Integer, Benchmark> benchmarkByHero = new HashMap<>();
        for (HeroStat stat : benchmarkStats) {
            if (!seenBenchmark.add(stat.heroId())) {
                continue;
            }
            if (stat.matches() < MIN_BENCHMARK_MATCHES) {
                continue;
            }
            double avgWinRate = stat.wins() * 100.0 / stat.matches();
            Double avgKda = stat.totalDeaths() > 0
                    ? (double) (stat.totalKills() + stat.totalAssists()) / stat.totalDeaths()
                    : null;
            benchmarkByHero.put(stat.heroId(), new Benchmark(avgWinRate, avgKda));
        }

        Map<Integer, String> heroNames = gameAssets.heroNames();
        Set<Integer> seenPlayerHeroes = new HashSet<>();
        List<HeroPerformance> allPerformances = new ArrayList<>();

        for (PlayerHeroStat stat : stats) {
            if (!seenPlayerHeroes.add(stat.heroId())) {
                continue;
            }
            if (stat.matchesPlayed() <= 0) {
                continue;
            }
            double winRatePercent = stat.wins() * 100.0 / stat.matchesPlayed();
            double kda = (double) (stat.kills() + stat.assists()) / Math.max(stat.deaths(), 1);
            Benchmark bm = benchmarkByHero.get(stat.heroId());
            Double heroAvgWinRatePercent = bm != null ? bm.avgWinRate() : null;
            Double deltaPoints = bm != null ? winRatePercent - bm.avgWinRate() : null;
            Double heroAvgKda = bm != null ? bm.avgKda() : null;
            String heroName = heroNames.get(stat.heroId());
            allPerformances.add(new HeroPerformance(
                    stat.heroId(),
                    heroName,
                    (int) stat.matchesPlayed(),
                    (int) stat.wins(),
                    winRatePercent,
                    kda,
                    heroAvgWinRatePercent,
                    deltaPoints,
                    heroAvgKda
            ));
        }

        Comparator<HeroPerformance> topOrder = Comparator
                .comparingInt(HeroPerformance::matches).reversed()
                .thenComparingInt(HeroPerformance::heroId);
        List<HeroPerformance> topHeroes = ServiceSupport.top(new ArrayList<>(allPerformances), topOrder, TOP_HEROES);

        Comparator<HeroPerformance> bestOrder = Comparator
                .<HeroPerformance>comparingDouble(HeroPerformance::deltaPoints).reversed()
                .thenComparing(Comparator.comparingInt(HeroPerformance::matches).reversed())
                .thenComparingInt(HeroPerformance::heroId);
        HeroPerformance best = allPerformances.stream()
                .filter(p -> p.matches() >= MIN_MATCHES_FOR_TIP && p.deltaPoints() != null && p.deltaPoints() > 0)
                .min(bestOrder)
                .orElse(null);

        Comparator<HeroPerformance> weakestOrder = Comparator
                .<HeroPerformance>comparingDouble(HeroPerformance::deltaPoints)
                .thenComparing(Comparator.comparingInt(HeroPerformance::matches).reversed())
                .thenComparingInt(HeroPerformance::heroId);
        HeroPerformance weakest = allPerformances.stream()
                .filter(p -> p.matches() >= MIN_MATCHES_FOR_TIP && p.deltaPoints() != null && p.deltaPoints() < 0)
                .min(weakestOrder)
                .orElse(null);

        return new HeroPoolSummary(accountId, rankTier, topHeroes, best, weakest);
    }
}
