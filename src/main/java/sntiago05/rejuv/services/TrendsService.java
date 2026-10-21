package sntiago05.rejuv.services;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.dto.MatchHistoryEntry;
import sntiago05.rejuv.domain.MatchFilter;

@Service
public class TrendsService {

    public static final int MIN_GAMES_FOR_TREND = 5;
    public static final int MIN_GAMES_FOR_TOP_HERO = 2;
    public static final int TOP_HEROES_LIMIT = 3;
    public static final double STEADY_THRESHOLD_POINTS = 5.0;

    public enum TrendDirection {
        IMPROVING,
        STEADY,
        DECLINING
    }

    public record HeroLine(int heroId, int games, double winRate) {}

    public record TrendsSummary(
            int games,
            double winRate,
            double avgKills,
            double avgDeaths,
            double avgAssists,
            double kdaRatio,
            long avgNetWorth,
            double winRateRecentHalf,
            double winRateOlderHalf,
            List<HeroLine> topHeroes) {

        public TrendsSummary {
            topHeroes = List.copyOf(topHeroes);
        }

        public double pointDifference() {
            return winRateRecentHalf - winRateOlderHalf;
        }

        public TrendDirection direction() {
            double diff = pointDifference();
            if (diff > STEADY_THRESHOLD_POINTS) {
                return TrendDirection.IMPROVING;
            }
            if (diff < -STEADY_THRESHOLD_POINTS) {
                return TrendDirection.DECLINING;
            }
            return TrendDirection.STEADY;
        }
    }

    private final DeadlockApiClient deadlockApiClient;

    @Autowired
    public TrendsService(DeadlockApiClient deadlockApiClient) {
        this.deadlockApiClient = deadlockApiClient;
    }

    public TrendsSummary summarize(long accountId, int games) {
        return summarize(accountId, games, null);
    }

    public TrendsSummary summarize(long accountId, int games, MatchFilter filter) {
        if (games <= 0) {
            return emptySummary(0);
        }

        List<MatchHistoryEntry> history = deadlockApiClient.getMatchHistory(accountId);
        if (history.isEmpty()) {
            return emptySummary(0);
        }

        // Single pass over the history: filter and take at most 'games' matches with known outcome.
        List<MatchHistoryEntry> sample = new ArrayList<>(Math.min(games, history.size()));
        for (MatchHistoryEntry entry : history) {
            if (!isKnownOutcome(entry)) {
                continue;
            }
            if (!matchesFilter(entry, filter)) {
                continue;
            }
            sample.add(entry);
            if (sample.size() >= games) {
                break;
            }
        }

        int sampleSize = sample.size();
        if (sampleSize == 0) {
            return emptySummary(0);
        }

        int totalWins = 0;
        long totalKills = 0;
        long totalDeaths = 0;
        long totalAssists = 0;
        long totalNetWorth = 0;

        // Track hero usage: heroId -> [games, wins]
        Map<Integer, int[]> heroUsage = new HashMap<>();

        for (MatchHistoryEntry entry : sample) {
            boolean win = isWin(entry);
            if (win) {
                totalWins++;
            }
            totalKills += entry.playerKills();
            totalDeaths += entry.playerDeaths();
            totalAssists += entry.playerAssists();
            totalNetWorth += entry.netWorth();

            int[] counts = heroUsage.computeIfAbsent(entry.heroId(), k -> new int[2]);
            counts[0]++;
            if (win) {
                counts[1]++;
            }
        }

        double winRate = totalWins * 100.0 / sampleSize;
        double avgKills = (double) totalKills / sampleSize;
        double avgDeaths = (double) totalDeaths / sampleSize;
        double avgAssists = (double) totalAssists / sampleSize;
        double kdaRatio = (double) (totalKills + totalAssists) / Math.max(1, totalDeaths);
        long avgNetWorth = Math.round((double) totalNetWorth / sampleSize);

        // Halves comparison
        int half = sampleSize / 2;
        double winRateRecentHalf = 0.0;
        double winRateOlderHalf = 0.0;
        if (half > 0) {
            int recentWins = 0;
            for (int i = 0; i < half; i++) {
                if (isWin(sample.get(i))) {
                    recentWins++;
                }
            }
            int olderWins = 0;
            int olderStart = sampleSize - half;
            for (int i = olderStart; i < sampleSize; i++) {
                if (isWin(sample.get(i))) {
                    olderWins++;
                }
            }
            winRateRecentHalf = recentWins * 100.0 / half;
            winRateOlderHalf = olderWins * 100.0 / half;
        }

        // Top 3 heroes by games (minimum 2 games)
        List<HeroLine> topHeroes = heroUsage.entrySet().stream()
                .filter(e -> e.getValue()[0] >= MIN_GAMES_FOR_TOP_HERO)
                .map(e -> new HeroLine(e.getKey(), e.getValue()[0], e.getValue()[1] * 100.0 / e.getValue()[0]))
                .sorted(Comparator.comparingInt(HeroLine::games).reversed()
                        .thenComparing(Comparator.comparingDouble(HeroLine::winRate).reversed())
                        .thenComparingInt(HeroLine::heroId))
                .limit(TOP_HEROES_LIMIT)
                .toList();

        return new TrendsSummary(
                sampleSize,
                winRate,
                avgKills,
                avgDeaths,
                avgAssists,
                kdaRatio,
                avgNetWorth,
                winRateRecentHalf,
                winRateOlderHalf,
                topHeroes);
    }

    private static boolean isKnownOutcome(MatchHistoryEntry entry) {
        return (entry.matchResult() == 0 || entry.matchResult() == 1)
                && (entry.playerTeam() == 0 || entry.playerTeam() == 1);
    }

    private static boolean isWin(MatchHistoryEntry entry) {
        return entry.matchResult() == entry.playerTeam();
    }

    private static boolean matchesFilter(MatchHistoryEntry entry, MatchFilter filter) {
        if (filter == null) {
            return true;
        }
        if (filter.matchId() != null && entry.matchId() != filter.matchId()) {
            return false;
        }
        if (filter.heroId() != null && entry.heroId() != filter.heroId()) {
            return false;
        }
        if (filter.win() != null && isWin(entry) != filter.win()) {
            return false;
        }
        return true;
    }

    private static TrendsSummary emptySummary(int games) {
        return new TrendsSummary(games, 0.0, 0.0, 0.0, 0.0, 0.0, 0L, 0.0, 0.0, List.of());
    }
}
