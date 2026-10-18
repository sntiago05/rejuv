package sntiago05.rejuv.services;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Component;
import sntiago05.rejuv.clients.dto.MatchHistoryEntry;

/**
 * Coach layer "recent_form": evaluates a player's recent form on the analyzed hero
 * against their recent baseline (averages, deviations, trend, losing streaks, fatigue).
 */
@Component
public class FormCoachProvider implements CoachInsightProvider {

    public static final String KEY = "recent_form";

    // Duration and sample constraints
    static final int MIN_DURATION_S = 600;      // 10 minutes; shorter matches are remakes or early abandons
    static final int SAMPLE_MIN = 5;            // Minimum qualifying games needed to establish a personal baseline
    static final int SAMPLE_MAX = 20;           // Maximum recent games to evaluate for baseline statistics
    static final int TREND_MIN_GAMES = 10;      // Minimum games needed to compute recent 5 vs previous 5 trend
    static final int TREND_WINDOW = 5;          // Number of games in each trend window (newest 5 vs next 5)
    static final long SECONDS_IN_24H = 86_400L; // 24 hours in seconds for fatigue evaluation

    // Major significance thresholds: clearly abnormal fatigue, tilt, or feeding
    /** Two standard deviations above player's average death rate on this hero indicates acute feeding. */
    static final double MAJOR_DEATHS_PER_10_MIN_Z = 2.0;
    /** 4+ consecutive losses entering the match is a strong indicator of tilt. */
    static final int MAJOR_LOSSES_IN_A_ROW = 4;
    /** 12+ games in the last 24 hours indicates acute fatigue and marathon grinding. */
    static final int MAJOR_GAMES_LAST_24H = 12;

    // Notable significance thresholds: moderate deviation from personal baseline
    /** One standard deviation above or below average farm indicates notable over- or under-performance. */
    static final double NOTABLE_SOULS_PER_MIN_ABS_Z = 1.0;
    /** One standard deviation above average death rate indicates notable dying. */
    static final double NOTABLE_DEATHS_PER_10_MIN_Z = 1.0;
    /** 3 consecutive losses indicates a noticeable losing streak entering the match. */
    static final int NOTABLE_LOSSES_IN_A_ROW = 3;
    /** 8+ games in the last 24 hours indicates elevated match volume and potential fatigue. */
    static final int NOTABLE_GAMES_LAST_24H = 8;
    /** Dying at least 1.0 more per 10 minutes across recent 5 vs prior 5 indicates a degrading trend. */
    static final double NOTABLE_DEATHS_CHANGE = 1.0;

    private static final Comparator<MatchHistoryEntry> NEWEST_FIRST =
            Comparator.comparingLong(MatchHistoryEntry::startTime).reversed()
                    .thenComparing(Comparator.comparingLong(MatchHistoryEntry::matchId).reversed());

    public FormCoachProvider() {
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public Optional<CoachInsight> provide(CoachRequest request) {
        if (request == null || request.match() == null) {
            return Optional.empty();
        }

        MatchHistoryEntry match = request.match();
        List<MatchHistoryEntry> history = request.history();
        if (history == null || history.isEmpty()) {
            return Optional.empty();
        }

        // Context across all heroes: every history entry strictly before analyzed match, newest first, no duration filter
        List<MatchHistoryEntry> priorMatches = history.stream()
                .filter(entry -> entry.matchId() != match.matchId() && entry.startTime() < match.startTime())
                .sorted(NEWEST_FIRST)
                .toList();

        int lossesInARowBefore = 0;
        for (MatchHistoryEntry entry : priorMatches) {
            boolean isWin = entry.matchResult() == entry.playerTeam();
            if (!isWin) {
                lossesInARowBefore++;
            } else {
                break;
            }
        }

        long lowerBound24h = match.startTime() - SECONDS_IN_24H;
        int gamesLast24h = (int) priorMatches.stream()
                .filter(entry -> entry.startTime() >= lowerBound24h)
                .count();

        // Reference sample: same heroId, startTime < match.startTime(), matchDurationS >= MIN_DURATION_S, excluding analyzed match id
        List<MatchHistoryEntry> sample = priorMatches.stream()
                .filter(entry -> entry.heroId() == match.heroId() && entry.matchDurationS() >= MIN_DURATION_S)
                .limit(SAMPLE_MAX)
                .toList();

        if (sample.size() < SAMPLE_MIN) {
            return Optional.empty();
        }

        int sampleSize = sample.size();
        long wins = sample.stream().filter(entry -> entry.matchResult() == entry.playerTeam()).count();
        double winRatePct = round((wins * 100.0) / sampleSize);

        double sumSoulsPerMin = 0.0;
        double sumDeathsPer10Min = 0.0;
        double sumKda = 0.0;
        for (MatchHistoryEntry entry : sample) {
            sumSoulsPerMin += soulsPerMin(entry);
            sumDeathsPer10Min += deathsPer10Min(entry);
            sumKda += kda(entry);
        }

        double meanSoulsPerMin = sumSoulsPerMin / sampleSize;
        double meanDeathsPer10Min = sumDeathsPer10Min / sampleSize;
        double meanKda = sumKda / sampleSize;

        double avgSoulsPerMin = round(meanSoulsPerMin);
        double avgDeathsPer10Min = round(meanDeathsPer10Min);
        double avgKda = round(meanKda);

        double varSoulsPerMin = 0.0;
        double varDeathsPer10Min = 0.0;
        for (MatchHistoryEntry entry : sample) {
            double diffSouls = soulsPerMin(entry) - meanSoulsPerMin;
            varSoulsPerMin += diffSouls * diffSouls;
            double diffDeaths = deathsPer10Min(entry) - meanDeathsPer10Min;
            varDeathsPer10Min += diffDeaths * diffDeaths;
        }

        double stdSoulsPerMin = Math.sqrt(varSoulsPerMin / sampleSize);
        double stdDeathsPer10Min = Math.sqrt(varDeathsPer10Min / sampleSize);

        Double soulsPerMinZ = null;
        Double deathsPer10MinZ = null;
        if (match.matchDurationS() >= MIN_DURATION_S) {
            if (stdSoulsPerMin > 0.0) {
                soulsPerMinZ = round((soulsPerMin(match) - meanSoulsPerMin) / stdSoulsPerMin);
            }
            if (stdDeathsPer10Min > 0.0) {
                deathsPer10MinZ = round((deathsPer10Min(match) - meanDeathsPer10Min) / stdDeathsPer10Min);
            }
        }

        RecentVsPrevious recentVsPrevious = null;
        Double deathsPer10MinChange = null;
        if (sampleSize >= TREND_MIN_GAMES) {
            List<MatchHistoryEntry> recent5 = sample.subList(0, TREND_WINDOW);
            List<MatchHistoryEntry> previous5 = sample.subList(TREND_WINDOW, TREND_WINDOW * 2);

            long recentWins = recent5.stream().filter(entry -> entry.matchResult() == entry.playerTeam()).count();
            long previousWins = previous5.stream().filter(entry -> entry.matchResult() == entry.playerTeam()).count();

            double recentWinRate = (recentWins * 100.0) / TREND_WINDOW;
            double previousWinRate = (previousWins * 100.0) / TREND_WINDOW;
            double winRateChangePct = round(recentWinRate - previousWinRate);

            double recentDeathsSum = 0.0;
            for (MatchHistoryEntry entry : recent5) {
                recentDeathsSum += deathsPer10Min(entry);
            }
            double previousDeathsSum = 0.0;
            for (MatchHistoryEntry entry : previous5) {
                previousDeathsSum += deathsPer10Min(entry);
            }

            double meanRecentDeaths = recentDeathsSum / TREND_WINDOW;
            double meanPreviousDeaths = previousDeathsSum / TREND_WINDOW;
            deathsPer10MinChange = round(meanRecentDeaths - meanPreviousDeaths);

            recentVsPrevious = new RecentVsPrevious(winRateChangePct, deathsPer10MinChange);
        }

        String significance = computeSignificance(deathsPer10MinZ, lossesInARowBefore, gamesLast24h,
                soulsPerMinZ, deathsPer10MinChange);

        FormPayload payload = new FormPayload(
                significance,
                sampleSize,
                winRatePct,
                avgSoulsPerMin,
                avgDeathsPer10Min,
                avgKda,
                soulsPerMinZ,
                deathsPer10MinZ,
                recentVsPrevious,
                lossesInARowBefore,
                gamesLast24h
        );

        return Optional.of(new CoachInsight(KEY, payload));
    }

    private static String computeSignificance(Double deathsPer10MinZ, int lossesInARowBefore, int gamesLast24h,
                                              Double soulsPerMinZ, Double deathsPer10MinChange) {
        if ((deathsPer10MinZ != null && deathsPer10MinZ >= MAJOR_DEATHS_PER_10_MIN_Z)
                || lossesInARowBefore >= MAJOR_LOSSES_IN_A_ROW
                || gamesLast24h >= MAJOR_GAMES_LAST_24H) {
            return "major";
        }
        if ((soulsPerMinZ != null && Math.abs(soulsPerMinZ) >= NOTABLE_SOULS_PER_MIN_ABS_Z)
                || (deathsPer10MinZ != null && deathsPer10MinZ >= NOTABLE_DEATHS_PER_10_MIN_Z)
                || lossesInARowBefore >= NOTABLE_LOSSES_IN_A_ROW
                || gamesLast24h >= NOTABLE_GAMES_LAST_24H
                || (deathsPer10MinChange != null && deathsPer10MinChange >= NOTABLE_DEATHS_CHANGE)) {
            return "notable";
        }
        return "none";
    }

    private static double soulsPerMin(MatchHistoryEntry entry) {
        return entry.netWorth() / (entry.matchDurationS() / 60.0);
    }

    private static double deathsPer10Min(MatchHistoryEntry entry) {
        return entry.playerDeaths() / (entry.matchDurationS() / 60.0) * 10.0;
    }

    private static double kda(MatchHistoryEntry entry) {
        return (double) (entry.playerKills() + entry.playerAssists()) / Math.max(1, entry.playerDeaths());
    }

    private static double round(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        double rounded = BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
        return rounded == -0.0 ? 0.0 : rounded;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FormPayload(
            @JsonProperty("significance") String significance,
            @JsonProperty("sample_size") int sampleSize,
            @JsonProperty("win_rate_pct") double winRatePct,
            @JsonProperty("avg_souls_per_min") double avgSoulsPerMin,
            @JsonProperty("avg_deaths_per_10_min") double avgDeathsPer10Min,
            @JsonProperty("avg_kda") double avgKda,
            @JsonProperty("souls_per_min_z") Double soulsPerMinZ,
            @JsonProperty("deaths_per_10_min_z") Double deathsPer10MinZ,
            @JsonProperty("recent_vs_previous") RecentVsPrevious recentVsPrevious,
            @JsonProperty("losses_in_a_row_before") int lossesInARowBefore,
            @JsonProperty("games_last_24h") int gamesLast24h
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RecentVsPrevious(
            @JsonProperty("win_rate_change_pct") double winRateChangePct,
            @JsonProperty("deaths_per_10_min_change") double deathsPer10MinChange
    ) {
    }
}
