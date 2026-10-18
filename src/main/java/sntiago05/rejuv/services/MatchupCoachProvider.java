package sntiago05.rejuv.services;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.DeadlockApiException;
import sntiago05.rejuv.clients.dto.HeroAsset;
import sntiago05.rejuv.clients.dto.HeroCounterStat;
import sntiago05.rejuv.domain.MatchTelemetry;
import sntiago05.rejuv.domain.PlayerTelemetry;
import sntiago05.rejuv.domain.StatSnapshot;

/**
 * Coach insight provider for matchup context: draft win rates against enemy heroes,
 * lane opponents, and laning phase outcome (net worth diff at 12 minutes).
 */
@Component
public class MatchupCoachProvider implements CoachInsightProvider {

    private static final Logger log = LoggerFactory.getLogger(MatchupCoachProvider.class);

    public static final String KEY = "matchup";

    /** Minimum matches played for a hero counter pair to be considered statistically valid. */
    static final long MIN_PAIR_MATCHES = 100;

    /** Win rate threshold to identify an enemy as the "easiest" matchup. */
    static final double EASIEST_WIN_RATE_THRESHOLD = 52.0;

    /** Maximum number of hardest enemy matchups to include in payload. */
    static final int HARDEST_CAP = 2;

    /** Maximum number of lane opponents to include in payload. */
    static final int LANE_OPPONENTS_CAP = 2;

    /** Laning phase boundary timestamp in seconds (12 minutes). */
    static final int LANE_CHECK_S = 720;

    /** Percentage net worth lead/deficit determining a decisive lane win/loss. */
    static final double LANE_DECISIVE_PCT = 10.0;

    /** Lane net worth deficit percentage threshold for major significance. */
    static final double MAJOR_LANE_LOST_PCT = -20.0;

    /** Win rate threshold against a hero indicating a major disadvantage. */
    static final double MAJOR_WIN_RATE_PCT = 45.0;

    /** Win rate threshold against a hero indicating a notable disadvantage. */
    static final double NOTABLE_WIN_RATE_PCT = 48.0;

    private final DeadlockApiClient deadlockApiClient;
    private final Clock clock;

    @Autowired
    public MatchupCoachProvider(DeadlockApiClient deadlockApiClient) {
        this(deadlockApiClient, Clock.systemUTC());
    }

    MatchupCoachProvider(DeadlockApiClient deadlockApiClient, Clock clock) {
        this.deadlockApiClient = Objects.requireNonNull(deadlockApiClient, "deadlockApiClient must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public Optional<CoachInsight> provide(CoachRequest request) {
        if (request == null || request.telemetry() == null || request.player() == null) {
            return Optional.empty();
        }

        MatchTelemetry telemetry = request.telemetry();
        PlayerTelemetry player = request.player();

        if (telemetry.players() == null || telemetry.players().isEmpty()) {
            return Optional.empty();
        }

        List<PlayerTelemetry> enemies = new ArrayList<>();
        for (PlayerTelemetry p : telemetry.players()) {
            if (p != null && p.team() != player.team()) {
                enemies.add(p);
            }
        }

        if (enemies.isEmpty()) {
            return Optional.empty();
        }

        // Tier handling: 1..11 passed as tier, otherwise null (all ranks)
        Integer rankTier = request.rankTier();
        Integer tier = (rankTier != null && rankTier >= ServiceSupport.MIN_RANK_TIER && rankTier <= ServiceSupport.MAX_RANK_TIER)
                ? rankTier
                : null;

        long startOfWindow = ServiceSupport.startOfUtcDayMinus(clock, ServiceSupport.WINDOW);

        List<HeroCounterStat> counterStats = null;
        try {
            counterStats = deadlockApiClient.getHeroCounterStats(tier, startOfWindow);
        } catch (DeadlockApiException e) {
            log.warn("Hero counter stats unavailable: {}", e.getClass().getSimpleName());
        }

        // Build lookup map for qualifying pairs facing player's hero in a single pass
        Map<Integer, HeroCounterStat> qualifyingPairsByEnemyHero = new HashMap<>();
        if (counterStats != null) {
            int playerHeroId = player.heroId();
            for (HeroCounterStat stat : counterStats) {
                if (stat.heroId() == playerHeroId && stat.matchesPlayed() >= MIN_PAIR_MATCHES) {
                    qualifyingPairsByEnemyHero.put(stat.enemyHeroId(), stat);
                }
            }
        }

        // Build hero name lookup map
        Map<Integer, String> heroNames = buildHeroNamesMap(request.heroes());

        // Process enemy pair table stats
        List<EnemyMatchup> qualifyingEnemyMatchups = new ArrayList<>();
        for (PlayerTelemetry enemy : enemies) {
            HeroCounterStat pairStat = qualifyingPairsByEnemyHero.get(enemy.heroId());
            if (pairStat != null) {
                double winRatePct = round((double) pairStat.wins() / pairStat.matchesPlayed() * 100.0);
                String heroName = resolveHeroName(enemy.heroId(), heroNames);
                qualifyingEnemyMatchups.add(new EnemyMatchup(enemy.heroId(), heroName, winRatePct));
            }
        }

        Double enemyAvgWinRatePct = null;
        List<HeroWinRate> hardest = null;
        HeroWinRate easiest = null;

        if (!qualifyingEnemyMatchups.isEmpty()) {
            double avg = qualifyingEnemyMatchups.stream()
                    .mapToDouble(EnemyMatchup::winRatePct)
                    .average()
                    .orElse(0.0);
            enemyAvgWinRatePct = round(avg);

            // Hardest: up to 2 enemies with lowest win rate, ties broken by lower hero id
            hardest = qualifyingEnemyMatchups.stream()
                    .sorted(Comparator.comparingDouble(EnemyMatchup::winRatePct)
                            .thenComparingInt(EnemyMatchup::heroId))
                    .limit(HARDEST_CAP)
                    .map(m -> new HeroWinRate(m.heroName(), m.winRatePct()))
                    .toList();

            // Easiest: enemy with highest win rate, only when win rate >= 52.0
            EnemyMatchup bestMatchup = qualifyingEnemyMatchups.stream()
                    .sorted(Comparator.comparingDouble(EnemyMatchup::winRatePct).reversed()
                            .thenComparingInt(EnemyMatchup::heroId))
                    .findFirst()
                    .orElse(null);

            if (bestMatchup != null && bestMatchup.winRatePct() >= EASIEST_WIN_RATE_THRESHOLD) {
                easiest = new HeroWinRate(bestMatchup.heroName(), bestMatchup.winRatePct());
            }
        }

        // Lane section: only when player.assignedLane() is non-null
        List<HeroWinRate> laneOpponents = null;
        Long laneNetWorthDiff = null;
        Double laneNetWorthDiffPct = null;
        String laneResult = null;

        if (player.assignedLane() != null) {
            int playerLane = player.assignedLane();
            List<PlayerTelemetry> laneOpponentPlayers = enemies.stream()
                    .filter(e -> e.assignedLane() != null && e.assignedLane() == playerLane)
                    .sorted(Comparator.comparingInt(PlayerTelemetry::heroId))
                    .limit(LANE_OPPONENTS_CAP)
                    .toList();

            laneOpponents = new ArrayList<>();
            for (PlayerTelemetry opponent : laneOpponentPlayers) {
                String heroName = resolveHeroName(opponent.heroId(), heroNames);
                HeroCounterStat pairStat = qualifyingPairsByEnemyHero.get(opponent.heroId());
                Double winRatePct = (pairStat != null)
                        ? round((double) pairStat.wins() / pairStat.matchesPlayed() * 100.0)
                        : null;
                laneOpponents.add(new HeroWinRate(heroName, winRatePct));
            }

            // Net worth diff at LANE_CHECK_S = 720
            if (telemetry.durationS() >= LANE_CHECK_S && !laneOpponentPlayers.isEmpty()) {
                Optional<StatSnapshot> playerSnap = player.snapshots().stream()
                        .filter(s -> s.timeS() == LANE_CHECK_S)
                        .findFirst();

                if (playerSnap.isPresent()) {
                    List<Long> opponentNetWorths = new ArrayList<>();
                    for (PlayerTelemetry opp : laneOpponentPlayers) {
                        opp.snapshots().stream()
                                .filter(s -> s.timeS() == LANE_CHECK_S)
                                .findFirst()
                                .ifPresent(s -> opponentNetWorths.add(s.netWorth()));
                    }

                    if (!opponentNetWorths.isEmpty()) {
                        double oppAvg = opponentNetWorths.stream()
                                .mapToLong(Long::longValue)
                                .average()
                                .orElse(0.0);

                        if (oppAvg > 0.0) {
                            long playerNw = playerSnap.get().netWorth();
                            long diff = Math.round(playerNw - oppAvg);
                            double diffPct = round((playerNw - oppAvg) / oppAvg * 100.0);

                            laneNetWorthDiff = diff;
                            laneNetWorthDiffPct = diffPct;
                            if (diffPct >= LANE_DECISIVE_PCT) {
                                laneResult = "won";
                            } else if (diffPct <= -LANE_DECISIVE_PCT) {
                                laneResult = "lost";
                            } else {
                                laneResult = "even";
                            }
                        }
                    }
                }
            }
        }

        // Return empty when there is no data from pair table and no lane data
        boolean hasPairData = (enemyAvgWinRatePct != null || hardest != null || easiest != null);
        boolean hasLaneData = (laneOpponents != null && !laneOpponents.isEmpty()) || laneResult != null;
        if (!hasPairData && !hasLaneData) {
            return Optional.empty();
        }

        String significance = computeSignificance(laneResult, laneNetWorthDiffPct, hardest, laneOpponents);

        MatchupPayload payload = new MatchupPayload(
                significance,
                enemyAvgWinRatePct,
                hardest,
                easiest,
                laneOpponents,
                laneNetWorthDiff,
                laneNetWorthDiffPct,
                laneResult
        );

        return Optional.of(new CoachInsight(KEY, payload));
    }

    private String computeSignificance(String laneResult,
                                       Double laneNetWorthDiffPct,
                                       List<HeroWinRate> hardest,
                                       List<HeroWinRate> laneOpponents) {
        boolean majorLaneLoss = "lost".equals(laneResult)
                && laneNetWorthDiffPct != null
                && laneNetWorthDiffPct <= MAJOR_LANE_LOST_PCT;

        boolean majorMatchup = false;
        if (hardest != null) {
            for (HeroWinRate h : hardest) {
                if (h.winRatePct() != null && h.winRatePct() <= MAJOR_WIN_RATE_PCT) {
                    majorMatchup = true;
                    break;
                }
            }
        }
        if (!majorMatchup && laneOpponents != null) {
            for (HeroWinRate op : laneOpponents) {
                if (op.winRatePct() != null && op.winRatePct() <= MAJOR_WIN_RATE_PCT) {
                    majorMatchup = true;
                    break;
                }
            }
        }

        if (majorLaneLoss || majorMatchup) {
            return "major";
        }

        boolean notableLaneLoss = "lost".equals(laneResult);
        boolean notableMatchup = false;
        if (hardest != null) {
            for (HeroWinRate h : hardest) {
                if (h.winRatePct() != null && h.winRatePct() <= NOTABLE_WIN_RATE_PCT) {
                    notableMatchup = true;
                    break;
                }
            }
        }

        if (notableLaneLoss || notableMatchup) {
            return "notable";
        }

        return "none";
    }

    private static Map<Integer, String> buildHeroNamesMap(List<HeroAsset> heroes) {
        Map<Integer, String> map = new HashMap<>();
        if (heroes != null) {
            for (HeroAsset hero : heroes) {
                if (hero != null) {
                    String name = hero.name();
                    if (name == null || name.isBlank()) {
                        name = hero.className();
                    }
                    if (name != null && !name.isBlank()) {
                        map.put(hero.id(), name);
                    }
                }
            }
        }
        return map;
    }

    private static String resolveHeroName(int heroId, Map<Integer, String> heroNames) {
        return heroNames.getOrDefault(heroId, "hero_" + heroId);
    }

    private static double round(double value) {
        double rounded = BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
        return rounded == 0.0 ? 0.0 : rounded;
    }

    private record EnemyMatchup(int heroId, String heroName, double winRatePct) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({
            "significance",
            "enemy_avg_win_rate_pct",
            "hardest",
            "easiest",
            "lane_opponents",
            "lane_net_worth_diff",
            "lane_net_worth_diff_pct",
            "lane_result"
    })
    public record MatchupPayload(
            @JsonProperty("significance") String significance,
            @JsonProperty("enemy_avg_win_rate_pct") Double enemyAvgWinRatePct,
            @JsonProperty("hardest") List<HeroWinRate> hardest,
            @JsonProperty("easiest") HeroWinRate easiest,
            @JsonProperty("lane_opponents") List<HeroWinRate> laneOpponents,
            @JsonProperty("lane_net_worth_diff") Long laneNetWorthDiff,
            @JsonProperty("lane_net_worth_diff_pct") Double laneNetWorthDiffPct,
            @JsonProperty("lane_result") String laneResult
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({ "hero", "win_rate_pct" })
    public record HeroWinRate(
            @JsonProperty("hero") String hero,
            @JsonProperty("win_rate_pct") Double winRatePct
    ) {}
}
