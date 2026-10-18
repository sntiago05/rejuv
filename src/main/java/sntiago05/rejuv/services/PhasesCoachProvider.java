package sntiago05.rejuv.services;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.dto.PerformanceCurvePoint;
import sntiago05.rejuv.domain.PlayerTelemetry;
import sntiago05.rejuv.domain.StatSnapshot;

/**
 * Coach layer "phases": compares player economy and damage against the rank/hero curve
 * at fixed game checkpoints (6, 12, 20, 30 min) and evaluates phase transitions (laning, mid, late).
 */
@Component
public class PhasesCoachProvider implements CoachInsightProvider {

    private static final Logger log = LoggerFactory.getLogger(PhasesCoachProvider.class);

    public static final String KEY = "phases";

    /**
     * Checkpoint game times in seconds: 6m (360s), 12m (720s), 20m (1200s), 30m (1800s).
     */
    static final int[] CHECKPOINTS_S = {360, 720, 1200, 1800};

    /**
     * Threshold for major significance (standard score >= 2.0 or phase change >= 2.0).
     * Two standard deviations away from the mean is statistically rare (~5% of players)
     * and represents an extreme lead or deficit.
     */
    public static final double SIGNIFICANCE_MAJOR_THRESHOLD = 2.0;

    /**
     * Threshold for notable significance (standard score >= 1.0 or phase change >= 1.0).
     * One standard deviation away from the mean is common (~32% of players)
     * and represents a clear advantage or disadvantage.
     */
    public static final double SIGNIFICANCE_NOTABLE_THRESHOLD = 1.0;

    /**
     * Threshold for phase swing (phase change >= 1.0 for strongest, <= -1.0 for weakest).
     * A swing of 1.0 standard deviation indicates the game dynamic shifted significantly.
     */
    public static final double PHASE_SWING_Z = 1.0;

    private static final int MIN_RANK_TIER = 0;
    private static final int MAX_RANK_TIER = 11;
    private static final int MIN_CHECKPOINTS = 2;

    private final DeadlockApiClient deadlockApiClient;

    public PhasesCoachProvider(DeadlockApiClient deadlockApiClient) {
        this.deadlockApiClient = deadlockApiClient;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public Optional<CoachInsight> provide(CoachRequest request) {
        if (request == null || request.telemetry() == null || request.player() == null || request.rankTier() == null) {
            return Optional.empty();
        }
        if (request.rankTier() < MIN_RANK_TIER || request.rankTier() > MAX_RANK_TIER) {
            return Optional.empty();
        }

        int heroId = request.match() != null ? request.match().heroId() : request.player().heroId();
        List<PerformanceCurvePoint> curve = deadlockApiClient.getPerformanceCurve(heroId, request.rankTier());
        if (curve == null || curve.isEmpty()) {
            return Optional.empty();
        }

        Map<Integer, PerformanceCurvePoint> curveByTime = new HashMap<>();
        for (PerformanceCurvePoint pt : curve) {
            curveByTime.put(pt.gameTime(), pt);
        }

        Map<Integer, StatSnapshot> playerSnapshots = new HashMap<>();
        if (request.player().snapshots() != null) {
            for (StatSnapshot s : request.player().snapshots()) {
                playerSnapshots.put(s.timeS(), s);
            }
        }

        PlayerTelemetry self = request.player();
        List<PlayerTelemetry> teammates = new ArrayList<>();
        if (request.telemetry().players() != null) {
            for (PlayerTelemetry p : request.telemetry().players()) {
                if (p != self && (self.accountId() == 0 || p.accountId() != self.accountId()) && p.team() == self.team()) {
                    teammates.add(p);
                }
            }
        }

        int durationS = request.telemetry().durationS();
        List<Checkpoint> checkpoints = new ArrayList<>();
        Map<Integer, Checkpoint> checkpointByTime = new HashMap<>();

        for (int checkpointTimeS : CHECKPOINTS_S) {
            if (durationS < checkpointTimeS) {
                continue;
            }
            StatSnapshot snapshot = playerSnapshots.get(checkpointTimeS);
            if (snapshot == null) {
                continue;
            }
            PerformanceCurvePoint curvePt = curveByTime.get(checkpointTimeS);
            if (curvePt == null || curvePt.netWorthStd() <= 0) {
                continue;
            }

            int minute = checkpointTimeS / 60;
            long netWorth = snapshot.netWorth();
            long netWorthAvg = Math.round(curvePt.netWorthAvg());
            double netWorthZ = round((netWorth - curvePt.netWorthAvg()) / curvePt.netWorthStd());

            Double damageZ = null;
            if (curvePt.playerDamageStd() > 0) {
                damageZ = round((snapshot.playerDamage() - curvePt.playerDamageAvg()) / curvePt.playerDamageStd());
            }

            Double farmZ = null;
            if (snapshot.detail() != null && curvePt.creepKillsStd() > 0) {
                farmZ = round((snapshot.detail().creepKills() - curvePt.creepKillsAvg()) / curvePt.creepKillsStd());
            }

            Double vsTeammatesPct = null;
            long teammateNetWorthSum = 0;
            int teammateCount = 0;
            for (PlayerTelemetry t : teammates) {
                if (t.snapshots() != null) {
                    for (StatSnapshot s : t.snapshots()) {
                        if (s.timeS() == checkpointTimeS) {
                            teammateNetWorthSum += s.netWorth();
                            teammateCount++;
                            break;
                        }
                    }
                }
            }
            if (teammateCount > 0) {
                double avgTeammateNetWorth = (double) teammateNetWorthSum / teammateCount;
                if (avgTeammateNetWorth > 0) {
                    vsTeammatesPct = round(((double) netWorth - avgTeammateNetWorth) / avgTeammateNetWorth * 100.0);
                }
            }

            Checkpoint cp = new Checkpoint(minute, netWorth, netWorthAvg, netWorthZ, damageZ, farmZ, vsTeammatesPct);
            checkpoints.add(cp);
            checkpointByTime.put(checkpointTimeS, cp);
        }

        if (checkpoints.size() < MIN_CHECKPOINTS) {
            return Optional.empty();
        }

        List<Phase> phases = new ArrayList<>();
        Checkpoint cp360 = checkpointByTime.get(360);
        Checkpoint cp720 = checkpointByTime.get(720);
        Checkpoint cp1200 = checkpointByTime.get(1200);
        Checkpoint cp1800 = checkpointByTime.get(1800);

        if (cp360 != null && cp720 != null) {
            phases.add(new Phase("laning", round(cp720.netWorthZ() - cp360.netWorthZ())));
        }
        if (cp720 != null && cp1200 != null) {
            phases.add(new Phase("mid", round(cp1200.netWorthZ() - cp720.netWorthZ())));
        }
        if (cp1200 != null && cp1800 != null) {
            phases.add(new Phase("late", round(cp1800.netWorthZ() - cp1200.netWorthZ())));
        }

        String weakestPhase = null;
        double minChange = Double.POSITIVE_INFINITY;
        for (Phase p : phases) {
            if (p.change() < minChange) {
                minChange = p.change();
                weakestPhase = p.name();
            }
        }
        if (minChange > -PHASE_SWING_Z) {
            weakestPhase = null;
        }

        String strongestPhase = null;
        double maxChange = Double.NEGATIVE_INFINITY;
        for (Phase p : phases) {
            if (p.change() > maxChange) {
                maxChange = p.change();
                strongestPhase = p.name();
            }
        }
        if (maxChange < PHASE_SWING_Z) {
            strongestPhase = null;
        }

        boolean hasMajor = false;
        boolean hasNotable = false;
        for (Checkpoint cp : checkpoints) {
            double absZ = Math.abs(cp.netWorthZ());
            if (absZ >= SIGNIFICANCE_MAJOR_THRESHOLD) {
                hasMajor = true;
            } else if (absZ >= SIGNIFICANCE_NOTABLE_THRESHOLD) {
                hasNotable = true;
            }
        }
        for (Phase p : phases) {
            double absChange = Math.abs(p.change());
            if (absChange >= SIGNIFICANCE_MAJOR_THRESHOLD) {
                hasMajor = true;
            } else if (absChange >= SIGNIFICANCE_NOTABLE_THRESHOLD) {
                hasNotable = true;
            }
        }

        String significance = hasMajor ? "major" : (hasNotable ? "notable" : "none");

        Payload payload = new Payload(significance, checkpoints, phases, weakestPhase, strongestPhase);
        log.debug("Built phases coach insight: significance={}, checkpoints={}, phases={}",
                significance, checkpoints.size(), phases.size());

        return Optional.of(new CoachInsight(KEY, payload));
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"significance", "checkpoints", "phases", "weakest_phase", "strongest_phase"})
    public record Payload(
            @JsonProperty("significance") String significance,
            @JsonProperty("checkpoints") List<Checkpoint> checkpoints,
            @JsonProperty("phases") List<Phase> phases,
            @JsonProperty("weakest_phase") String weakestPhase,
            @JsonProperty("strongest_phase") String strongestPhase
    ) {
        public Payload {
            checkpoints = checkpoints == null ? List.of() : List.copyOf(checkpoints.stream().limit(4).toList());
            phases = phases == null ? List.of() : List.copyOf(phases.stream().limit(3).toList());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"minute", "net_worth", "net_worth_avg", "net_worth_z", "damage_z", "farm_z", "vs_teammates_pct"})
    public record Checkpoint(
            @JsonProperty("minute") int minute,
            @JsonProperty("net_worth") long netWorth,
            @JsonProperty("net_worth_avg") long netWorthAvg,
            @JsonProperty("net_worth_z") double netWorthZ,
            @JsonProperty("damage_z") Double damageZ,
            @JsonProperty("farm_z") Double farmZ,
            @JsonProperty("vs_teammates_pct") Double vsTeammatesPct
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"name", "change"})
    public record Phase(
            @JsonProperty("name") String name,
            @JsonProperty("change") double change
    ) {
    }
}
