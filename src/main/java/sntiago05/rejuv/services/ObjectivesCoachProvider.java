package sntiago05.rejuv.services;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import sntiago05.rejuv.domain.MatchTelemetry;
import sntiago05.rejuv.domain.MidBossEvent;
import sntiago05.rejuv.domain.PlayerTelemetry;

/**
 * Coach insight provider for the "objectives" layer (Task 043).
 * Analyzes Mid Boss claims, Rejuvenator steals, and boss damage contribution.
 */
@Component
public class ObjectivesCoachProvider implements CoachInsightProvider {

    private static final Logger log = LoggerFactory.getLogger(ObjectivesCoachProvider.class);

    public static final String KEY = "objectives";

    /**
     * Conservative minimum match duration (12 minutes) before Mid Boss can matter.
     * Note: conservative choice, not an observed spawn time.
     */
    static final int MIN_DURATION_S = 720;

    /** Enemy team claiming 2 or more Mid Bosses is a major objective loss. */
    static final int MAJOR_MID_BOSS_ENEMY = 2;

    /** Enemy stealing the Rejuvenator after our team killed Mid Boss is a major tactical disaster. */
    static final int MAJOR_STOLEN_BY_ENEMY = 1;

    /** In a lost match, even 1 enemy Mid Boss claim contributed notably to defeat. */
    static final int NOTABLE_MID_BOSS_ENEMY_LOSS = 1;

    /** Stealing the Rejuvenator from the enemy team is a notable tactical swing. */
    static final int NOTABLE_STOLEN_BY_TEAM = 1;

    /** At least 1 team Mid Boss claim required for strength-based notable significance. */
    static final int NOTABLE_MID_BOSS_TEAM = 1;

    /**
     * Securing 25.0% or more of team boss damage during a team Mid Boss claim
     * indicates significant objective contribution (strength signal).
     */
    static final double STRENGTH_SHARE_PCT = 25.0;

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public Optional<CoachInsight> provide(CoachRequest request) {
        if (request == null || request.match() == null || request.telemetry() == null || request.player() == null) {
            return Optional.empty();
        }

        MatchTelemetry telemetry = request.telemetry();
        PlayerTelemetry player = request.player();

        // Raw protobuf fallback leaves midBoss null; skip without insight.
        if (telemetry.midBoss() == null) {
            return Optional.empty();
        }

        // Conservative duration threshold: nothing to say before Mid Boss can matter.
        if (telemetry.durationS() < MIN_DURATION_S) {
            return Optional.empty();
        }

        int playerTeam = player.team();
        boolean win = request.match().matchResult() == request.match().playerTeam();
        List<MidBossEvent> midBossEvents = telemetry.midBoss();

        int midBossTeam = 0;
        int midBossEnemy = 0;
        int rejuvenatorStolenByEnemy = 0;
        int rejuvenatorStolenByTeam = 0;

        for (MidBossEvent event : midBossEvents) {
            if (event.teamClaimed() == playerTeam) {
                midBossTeam++;
            } else {
                midBossEnemy++;
            }

            if (event.teamKilled() == playerTeam && event.teamClaimed() != playerTeam) {
                rejuvenatorStolenByEnemy++;
            } else if (event.teamKilled() != playerTeam && event.teamClaimed() == playerTeam) {
                rejuvenatorStolenByTeam++;
            }
        }

        Double firstMidBossMin = midBossEvents.isEmpty()
                ? null
                : round(midBossEvents.getFirst().timeS() / 60.0);

        Long playerBossDamage = player.bossDamage();
        Double bossDamageSharePct = null;
        if (playerBossDamage != null) {
            long teamTotalBossDamage = 0L;
            if (telemetry.players() != null) {
                for (PlayerTelemetry p : telemetry.players()) {
                    if (p.team() == playerTeam && p.bossDamage() != null) {
                        teamTotalBossDamage += p.bossDamage();
                    }
                }
            }
            if (teamTotalBossDamage > 0) {
                bossDamageSharePct = round(((double) playerBossDamage / teamTotalBossDamage) * 100.0);
            }
        }

        String significance;
        if (midBossEnemy >= MAJOR_MID_BOSS_ENEMY || rejuvenatorStolenByEnemy >= MAJOR_STOLEN_BY_ENEMY) {
            significance = "major";
        } else if ((!win && midBossEnemy >= NOTABLE_MID_BOSS_ENEMY_LOSS)
                || rejuvenatorStolenByTeam >= NOTABLE_STOLEN_BY_TEAM
                || (midBossTeam >= NOTABLE_MID_BOSS_TEAM && bossDamageSharePct != null && bossDamageSharePct >= STRENGTH_SHARE_PCT)) {
            significance = "notable";
        } else {
            significance = "none";
        }

        ObjectivesInsight payload = new ObjectivesInsight(
                significance,
                midBossTeam,
                midBossEnemy,
                rejuvenatorStolenByEnemy,
                rejuvenatorStolenByTeam,
                playerBossDamage,
                bossDamageSharePct,
                firstMidBossMin
        );

        log.debug("Evaluated objectives insight for match {}", request.match().matchId());
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
            "mid_boss_team",
            "mid_boss_enemy",
            "rejuvenator_stolen_by_enemy",
            "rejuvenator_stolen_by_team",
            "player_boss_damage",
            "boss_damage_share_pct",
            "first_mid_boss_min"
    })
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ObjectivesInsight(
            @JsonProperty("significance") String significance,
            @JsonProperty("mid_boss_team") int midBossTeam,
            @JsonProperty("mid_boss_enemy") int midBossEnemy,
            @JsonProperty("rejuvenator_stolen_by_enemy") int rejuvenatorStolenByEnemy,
            @JsonProperty("rejuvenator_stolen_by_team") int rejuvenatorStolenByTeam,
            @JsonProperty("player_boss_damage") Long playerBossDamage,
            @JsonProperty("boss_damage_share_pct") Double bossDamageSharePct,
            @JsonProperty("first_mid_boss_min") Double firstMidBossMin
    ) { }
}
