package sntiago05.rejuv.services;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import sntiago05.rejuv.domain.PlayerTelemetry;
import sntiago05.rejuv.domain.SnapshotDetail;
import sntiago05.rejuv.domain.StatSnapshot;

/**
 * Coach layer provider for the "aim_mechanics" key (Task 045).
 * Analyzes shot accuracy and crit rate for gun-dominant heroes from the final stat snapshot.
 */
@Component
public class AimMechanicsCoachProvider implements CoachInsightProvider {

    private static final Logger log = LoggerFactory.getLogger(AimMechanicsCoachProvider.class);

    public static final String KEY = "aim_mechanics";

    /**
     * Gun-dominant heroes (median weapon damage share >= 50% and median shots >= 700 in calibration):
     * Haze (13), Warden (25), Vyper (58), Silver (80, N=4, inconclusive in verification; owner chose to include), Rat King (84).
     */
    static final Set<Integer> GUN_HEROES = Set.of(13, 25, 58, 80, 84);

    /** Below 500 shots, the sample size is too small to judge aim mechanics. */
    static final int MIN_SHOTS_NOTABLE = 500;

    /** Major significance requires a larger sample size of at least 1,000 shots. */
    static final int MIN_SHOTS_MAJOR = 1000;

    /** Notable accuracy threshold (48.0%), about the p25 of gun carries in calibration. */
    static final double NOTABLE_ACCURACY_PCT = 48.0;

    /** Major accuracy threshold (32.0%), far below the p10 (40.9%) of gun carries. */
    static final double MAJOR_ACCURACY_PCT = 32.0;

    /** Crit rate is only meaningful with at least 250 hero bullets hit. */
    static final long MIN_BULLETS_FOR_CRIT = 250;

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public Optional<CoachInsight> provide(CoachRequest request) {
        if (request == null || request.telemetry() == null || request.player() == null) {
            return Optional.empty();
        }

        PlayerTelemetry player = request.player();
        if (!GUN_HEROES.contains(player.heroId())) {
            return Optional.empty();
        }

        List<StatSnapshot> snapshots = player.snapshots();
        if (snapshots == null || snapshots.isEmpty()) {
            return Optional.empty();
        }

        StatSnapshot finalSnapshot = snapshots.stream()
                .filter(Objects::nonNull)
                .max(Comparator.comparingInt(StatSnapshot::timeS))
                .orElse(null);

        if (finalSnapshot == null || finalSnapshot.detail() == null) {
            return Optional.empty();
        }

        SnapshotDetail detail = finalSnapshot.detail();
        int shots = detail.shotsHit() + detail.shotsMissed();
        if (shots < MIN_SHOTS_NOTABLE) {
            return Optional.empty();
        }

        double accuracyPct = round(((double) detail.shotsHit() / shots) * 100.0);

        Double critPct = null;
        Long heroBulletsHit = detail.heroBulletsHit();
        Long heroBulletsHitCrit = detail.heroBulletsHitCrit();
        if (heroBulletsHit != null && heroBulletsHitCrit != null && heroBulletsHit >= MIN_BULLETS_FOR_CRIT) {
            critPct = round(((double) heroBulletsHitCrit / heroBulletsHit) * 100.0);
        }

        String significance;
        if (accuracyPct < MAJOR_ACCURACY_PCT && shots >= MIN_SHOTS_MAJOR) {
            significance = "major";
        } else if (accuracyPct < NOTABLE_ACCURACY_PCT) {
            significance = "notable";
        } else {
            significance = "none";
        }

        AimMechanicsInsight payload = new AimMechanicsInsight(
                significance,
                accuracyPct,
                shots,
                critPct
        );

        log.debug("Evaluated aim mechanics insight for match {}", request.telemetry().matchId());
        return Optional.of(new CoachInsight(KEY, payload));
    }

    private static double round(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    @JsonPropertyOrder({
            "significance",
            "accuracy_pct",
            "shots",
            "crit_pct"
    })
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AimMechanicsInsight(
            @JsonProperty("significance") String significance,
            @JsonProperty("accuracy_pct") double accuracyPct,
            @JsonProperty("shots") int shots,
            @JsonProperty("crit_pct") Double critPct
    ) {
    }
}
