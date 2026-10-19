package sntiago05.rejuv.services;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.dto.HeroAsset;
import sntiago05.rejuv.clients.dto.PerformanceCurvePoint;
import sntiago05.rejuv.domain.DeathEvent;
import sntiago05.rejuv.domain.MatchTelemetry;
import sntiago05.rejuv.domain.PlayerTelemetry;
import sntiago05.rejuv.domain.StatSnapshot;

/**
 * Coach insight provider for the "deaths" layer (Task 032).
 * Analyzes player death timings, phase breakdown, death duration, economic loss,
 * rapid death clustering, killer identities, deaths while behind, benchmark z-score,
 * and time-to-kill summary.
 */
@Component
public class DeathsCoachProvider implements CoachInsightProvider {

    private static final Logger log = LoggerFactory.getLogger(DeathsCoachProvider.class);

    public static final String KEY = "deaths";

    /**
     * Values above this threshold are treated as no data (excluded, not clamped).
     * Conservative cut of observed outliers up to 180 s, not a game rule.
     */
    static final double TTK_MAX_VALID_S = 60.0;

    /** Minimum valid time_to_kill_s values as reported by deadlock-api required to emit the summary. */
    static final int MIN_TTK_SAMPLES = 3;

    /** Deaths occurring within 60 seconds of the preceding death are treated as clustered. */
    static final int CLUSTER_WINDOW_S = 60;

    /** Laning phase boundary in seconds (< 720 s). */
    static final int LANING_PHASE_END_S = 720;

    /** Mid game phase boundary in seconds (< 1200 s). */
    static final int MID_GAME_END_S = 1200;

    /** Minimum kills by a single enemy hero to be considered a repeat killer candidate. */
    static final int REPEAT_KILLER_MIN_KILLS = 3;

    /** Minimum fraction of all resolved kills held by the top killer to qualify as repeat killer. */
    static final double REPEAT_KILLER_MIN_SHARE = 0.40;

    /** Required player count in standard Deadlock matches for team-lead analysis. */
    static final int EXPECTED_MATCH_PLAYERS = 12;

    // Significance thresholds

    /** 2 standard deviations above rank average indicates exceptionally high deaths. */
    static final double MAJOR_DEATHS_Z = 2.0;

    /** Spending 15% or more of the entire match dead is a severe loss of map pressure and farm. */
    static final double MAJOR_PERCENT_DEAD = 15.0;

    /** 3 or more deaths while team is behind indicates chronic feed during deficit. */
    static final int MAJOR_DEATHS_WHILE_BEHIND = 3;

    /** 1 standard deviation above rank average indicates elevated deaths compared to peers. */
    static final double NOTABLE_DEATHS_Z = 1.0;

    /** Spending 10% or more of the match dead is a notable disadvantage. */
    static final double NOTABLE_PERCENT_DEAD = 10.0;

    /** 2 or more rapid consecutive deaths suggests tilt or caught repeatedly upon respawn. */
    static final int NOTABLE_BACK_TO_BACK = 2;

    /** 8 or more total deaths represents a high death count in a Deadlock match. */
    static final int NOTABLE_TOTAL_DEATHS = 8;

    private final DeadlockApiClient deadlockApiClient;

    public DeathsCoachProvider(DeadlockApiClient deadlockApiClient) {
        this.deadlockApiClient = Objects.requireNonNull(deadlockApiClient, "deadlockApiClient must not be null");
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

        if (telemetry.durationS() <= 0) {
            return Optional.empty();
        }

        // Marker of JSON-sourced match telemetry: raw protobuf fallback has all null details.
        boolean hasDetail = player.snapshots().stream().anyMatch(s -> s.detail() != null);
        if (!hasDetail) {
            return Optional.empty();
        }

        List<DeathEvent> deaths = player.deaths();
        if (deaths == null || deaths.isEmpty()) {
            return Optional.empty();
        }

        // 1. Core death facts
        int total = deaths.size();
        int laning = 0;
        int mid = 0;
        int late = 0;
        int secondsDead = 0;
        int longestRespawnS = 0;

        for (DeathEvent death : deaths) {
            int t = death.gameTimeS();
            if (t < LANING_PHASE_END_S) {
                laning++;
            } else if (t < MID_GAME_END_S) {
                mid++;
            } else {
                late++;
            }

            int respawn = death.respawnS();
            secondsDead += respawn;
            if (respawn > longestRespawnS) {
                longestRespawnS = respawn;
            }
        }

        PhaseDeaths byPhase = new PhaseDeaths(laning, mid, late);
        double percentOfMatchDead = round(((double) secondsDead / telemetry.durationS()) * 100.0);

        Long soulsLostOnDeath = null;
        List<StatSnapshot> snapshots = player.snapshots();
        if (!snapshots.isEmpty()) {
            StatSnapshot lastSnapshot = snapshots.getLast();
            if (lastSnapshot.detail() != null) {
                soulsLostOnDeath = lastSnapshot.detail().goldDeathLoss();
            }
        }

        // 2. Clusters: back-to-back deaths within CLUSTER_WINDOW_S
        int backToBack = 0;
        for (int i = 1; i < deaths.size(); i++) {
            if (deaths.get(i).gameTimeS() - deaths.get(i - 1).gameTimeS() <= CLUSTER_WINDOW_S) {
                backToBack++;
            }
        }

        // 3. Killers resolution and repeat killer detection
        int resolvedKillers = 0;
        Map<Integer, KillerStats> killersByHeroId = new LinkedHashMap<>();

        for (DeathEvent death : deaths) {
            Integer killerSlot = death.killerSlot();
            if (killerSlot == null) {
                continue;
            }
            Optional<PlayerTelemetry> killerPlayerOpt = telemetry.playerBySlot(killerSlot);
            if (killerPlayerOpt.isEmpty()) {
                continue;
            }
            PlayerTelemetry killerPlayer = killerPlayerOpt.get();
            int heroId = killerPlayer.heroId();
            resolvedKillers++;

            killersByHeroId.computeIfAbsent(heroId, id -> new KillerStats(id, resolveHeroName(id, request.heroes())))
                    .increment();
        }

        TopKiller topKiller = null;
        boolean repeatKiller = false;
        if (!killersByHeroId.isEmpty()) {
            KillerStats top = killersByHeroId.values().stream()
                    .min(Comparator
                            .<KillerStats, Integer>comparing(s -> s.count, Comparator.reverseOrder())
                            .thenComparing(s -> s.heroId))
                    .orElseThrow();

            topKiller = new TopKiller(top.heroName, top.count);
            if (top.count >= REPEAT_KILLER_MIN_KILLS
                    && ((double) top.count / resolvedKillers) >= REPEAT_KILLER_MIN_SHARE - 1e-9) {
                repeatKiller = true;
            }
        }

        // 4. Deaths while behind
        Integer deathsWhileBehind = null;
        Integer deathsCounted = null;

        List<PlayerTelemetry> allPlayers = telemetry.players();
        if (allPlayers.size() == EXPECTED_MATCH_PLAYERS) {
            List<Set<Integer>> timesPerPlayer = allPlayers.stream()
                    .map(p -> p.snapshots().stream().map(StatSnapshot::timeS).collect(Collectors.toSet()))
                    .toList();

            Set<Integer> commonTimes = new HashSet<>(timesPerPlayer.getFirst());
            for (int i = 1; i < timesPerPlayer.size(); i++) {
                commonTimes.retainAll(timesPerPlayer.get(i));
            }

            if (!commonTimes.isEmpty()) {
                List<Map<Integer, Long>> netWorthByTimePerPlayer = allPlayers.stream()
                        .map(p -> p.snapshots().stream().collect(Collectors.toMap(
                                StatSnapshot::timeS,
                                StatSnapshot::netWorth,
                                (existing, replacement) -> existing
                        )))
                        .toList();

                int targetTeam = player.team();
                NavigableMap<Integer, Long> teamLeadByTime = new TreeMap<>();
                for (int t : commonTimes) {
                    long targetTeamNw = 0L;
                    long enemyTeamNw = 0L;
                    for (int i = 0; i < EXPECTED_MATCH_PLAYERS; i++) {
                        long nw = netWorthByTimePerPlayer.get(i).get(t);
                        if (allPlayers.get(i).team() == targetTeam) {
                            targetTeamNw += nw;
                        } else {
                            enemyTeamNw += nw;
                        }
                    }
                    teamLeadByTime.put(t, targetTeamNw - enemyTeamNw);
                }

                int evaluated = 0;
                int behindCount = 0;
                for (DeathEvent death : deaths) {
                    Map.Entry<Integer, Long> latestCommon = teamLeadByTime.floorEntry(death.gameTimeS());
                    if (latestCommon != null) {
                        evaluated++;
                        if (latestCommon.getValue() < 0) {
                            behindCount++;
                        }
                    }
                }

                if (evaluated > 0) {
                    deathsCounted = evaluated;
                    deathsWhileBehind = behindCount;
                }
            }
        }

        // 5. Rank curve (best effort)
        Double deathsZ = null;
        if (request.rankTier() != null) {
            List<PerformanceCurvePoint> curve = null;
            try {
                curve = deadlockApiClient.getPerformanceCurve(player.heroId(), request.rankTier());
            } catch (RuntimeException ex) {
                log.warn("Failed to get performance curve for hero {} and rankTier {}: {}",
                        player.heroId(), request.rankTier(), ex.getClass().getSimpleName());
            }

            if (curve != null && !curve.isEmpty()) {
                int durationS = telemetry.durationS();
                PerformanceCurvePoint bestPoint = curve.stream()
                        .filter(p -> p.gameTime() <= durationS && p.deathsStd() > 0)
                        .max(Comparator.comparingInt(PerformanceCurvePoint::gameTime))
                        .orElse(null);

                if (bestPoint != null) {
                    StatSnapshot matchingSnapshot = snapshots.stream()
                            .filter(s -> s.timeS() == bestPoint.gameTime())
                            .findFirst()
                            .orElse(null);

                    if (matchingSnapshot != null && matchingSnapshot.detail() != null) {
                        int deathsAtThatTime = matchingSnapshot.detail().deaths();
                        double rawZ = (deathsAtThatTime - bestPoint.deathsAvg()) / bestPoint.deathsStd();
                        deathsZ = round(rawZ);
                    }
                }
            }
        }

        // 6. Time-to-kill summary (time_to_kill_s as reported by deadlock-api)
        Double ttkMedianS = null;
        Double ttkP90S = null;
        Integer ttkCounted = null;

        int validTtkCount = 0;
        double[] ttkBuffer = new double[deaths.size()];
        for (DeathEvent death : deaths) {
            Double ttk = death.timeToKillS();
            if (isValidTtk(ttk)) {
                ttkBuffer[validTtkCount++] = ttk;
            }
        }

        if (validTtkCount >= MIN_TTK_SAMPLES) {
            double[] sortedTtk = Arrays.copyOf(ttkBuffer, validTtkCount);
            Arrays.sort(sortedTtk);

            double median;
            if (validTtkCount % 2 == 1) {
                median = sortedTtk[validTtkCount / 2];
            } else {
                median = (sortedTtk[(validTtkCount / 2) - 1] + sortedTtk[validTtkCount / 2]) / 2.0;
            }

            int p90Index = (int) Math.ceil(0.9 * validTtkCount) - 1;

            ttkMedianS = round(median);
            ttkP90S = round(sortedTtk[p90Index]);
            ttkCounted = validTtkCount;
        }

        // 7. Significance computation
        String significance;
        if ((deathsZ != null && deathsZ >= MAJOR_DEATHS_Z)
                || percentOfMatchDead >= MAJOR_PERCENT_DEAD
                || repeatKiller
                || (deathsWhileBehind != null && deathsWhileBehind >= MAJOR_DEATHS_WHILE_BEHIND)) {
            significance = "major";
        } else if ((deathsZ != null && deathsZ >= NOTABLE_DEATHS_Z)
                || percentOfMatchDead >= NOTABLE_PERCENT_DEAD
                || backToBack >= NOTABLE_BACK_TO_BACK
                || total >= NOTABLE_TOTAL_DEATHS) {
            significance = "notable";
        } else {
            significance = "none";
        }

        DeathsPayload payload = new DeathsPayload(
                significance,
                total,
                byPhase,
                secondsDead,
                percentOfMatchDead,
                longestRespawnS,
                soulsLostOnDeath,
                backToBack,
                topKiller,
                repeatKiller,
                deathsWhileBehind,
                deathsCounted,
                deathsZ,
                ttkMedianS,
                ttkP90S,
                ttkCounted
        );

        return Optional.of(new CoachInsight(KEY, payload));
    }

    private static boolean isValidTtk(Double ttk) {
        return ttk != null && Double.isFinite(ttk) && ttk > 0.0 && ttk <= TTK_MAX_VALID_S;
    }

    private static String resolveHeroName(int heroId, List<HeroAsset> heroes) {
        if (heroes != null) {
            for (HeroAsset hero : heroes) {
                if (hero.id() == heroId) {
                    if (hero.name() != null && !hero.name().isBlank()) {
                        return hero.name();
                    }
                    if (hero.className() != null && !hero.className().isBlank()) {
                        return hero.className();
                    }
                }
            }
        }
        return "hero_" + heroId;
    }

    private static double round(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    private static class KillerStats {
        final int heroId;
        final String heroName;
        int count = 0;

        KillerStats(int heroId, String heroName) {
            this.heroId = heroId;
            this.heroName = heroName;
        }

        void increment() {
            this.count++;
        }
    }

    @JsonPropertyOrder({
            "significance",
            "total",
            "by_phase",
            "seconds_dead",
            "percent_of_match_dead",
            "longest_respawn_s",
            "souls_lost_on_death",
            "back_to_back",
            "top_killer",
            "repeat_killer",
            "deaths_while_behind",
            "deaths_counted",
            "deaths_z",
            "ttk_median_s",
            "ttk_p90_s",
            "ttk_counted"
    })
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DeathsPayload(
            @JsonProperty("significance") String significance,
            @JsonProperty("total") int total,
            @JsonProperty("by_phase") PhaseDeaths byPhase,
            @JsonProperty("seconds_dead") int secondsDead,
            @JsonProperty("percent_of_match_dead") double percentOfMatchDead,
            @JsonProperty("longest_respawn_s") int longestRespawnS,
            @JsonProperty("souls_lost_on_death") Long soulsLostOnDeath,
            @JsonProperty("back_to_back") int backToBack,
            @JsonProperty("top_killer") TopKiller topKiller,
            @JsonProperty("repeat_killer") boolean repeatKiller,
            @JsonProperty("deaths_while_behind") Integer deathsWhileBehind,
            @JsonProperty("deaths_counted") Integer deathsCounted,
            @JsonProperty("deaths_z") Double deathsZ,
            @JsonProperty("ttk_median_s") Double ttkMedianS,
            @JsonProperty("ttk_p90_s") Double ttkP90S,
            @JsonProperty("ttk_counted") Integer ttkCounted
    ) {
    }

    @JsonPropertyOrder({"laning", "mid", "late"})
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PhaseDeaths(
            @JsonProperty("laning") int laning,
            @JsonProperty("mid") int mid,
            @JsonProperty("late") int late
    ) {
    }

    @JsonPropertyOrder({"hero", "count"})
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TopKiller(
            @JsonProperty("hero") String hero,
            @JsonProperty("count") int count
    ) {
    }
}
