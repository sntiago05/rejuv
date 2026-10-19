package sntiago05.rejuv.clients.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Raw API response payload from {@code GET /v1/matches/{match_id}/metadata}.
 * Only the fields consumed by Rejuv are mapped; all other upstream fields are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MatchMetadataResponse(
        @JsonProperty("match_info") MatchInfo matchInfo
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MatchInfo(
            @JsonProperty("match_id") Long matchId,
            @JsonProperty("duration_s") Integer durationS,
            @JsonProperty("players") List<Player> players,
            @JsonProperty("average_badge_team0") Integer averageBadgeTeam0,
            @JsonProperty("average_badge_team1") Integer averageBadgeTeam1,
            @JsonProperty("mid_boss") List<MidBoss> midBoss
    ) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MidBoss(
            @JsonProperty("team_killed") Integer teamKilled,
            @JsonProperty("team_claimed") Integer teamClaimed,
            @JsonProperty("destroyed_time_s") Integer destroyedTimeS
    ) {
        public Integer team_killed() {
            return teamKilled;
        }

        public Integer team_claimed() {
            return teamClaimed;
        }

        public Integer destroyed_time_s() {
            return destroyedTimeS;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Player(
            @JsonProperty("account_id") Long accountId,
            @JsonProperty("team") Integer team,
            @JsonProperty("hero_id") Integer heroId,
            @JsonProperty("player_slot") Integer playerSlot,
            @JsonProperty("assigned_lane") Integer assignedLane,
            @JsonProperty("items") List<Item> items,
            @JsonProperty("stats") List<Stat> stats,
            @JsonProperty("death_details") List<DeathDetail> deathDetails,
            @JsonProperty("power_up_buffs") List<PowerUpBuff> powerUpBuffs
    ) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(
            @JsonProperty("item_id") Long itemId,
            @JsonProperty("game_time_s") Integer gameTimeS,
            @JsonProperty("sold_time_s") Integer soldTimeS
    ) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DeathDetail(
            @JsonProperty("game_time_s") Integer gameTimeS,
            @JsonProperty("killer_player_slot") Integer killerPlayerSlot,
            @JsonProperty("time_to_kill_s") Double timeToKillS,
            @JsonProperty("death_duration_s") Integer deathDurationS
    ) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Stat(
            @JsonProperty("time_stamp_s") Integer timeStampS,
            @JsonProperty("net_worth") Long netWorth,
            @JsonProperty("player_damage") Long playerDamage,
            @JsonProperty("player_healing") Long playerHealing,
            @JsonProperty("kills") Integer kills,
            @JsonProperty("deaths") Integer deaths,
            @JsonProperty("assists") Integer assists,
            @JsonProperty("creep_kills") Integer creepKills,
            @JsonProperty("possible_creeps") Integer possibleCreeps,
            @JsonProperty("shots_hit") Integer shotsHit,
            @JsonProperty("shots_missed") Integer shotsMissed,
            @JsonProperty("denies") Integer denies,
            @JsonProperty("player_damage_taken") Long playerDamageTaken,
            @JsonProperty("gold_lane_creep") Long goldLaneCreep,
            @JsonProperty("gold_neutral_creep") Long goldNeutralCreep,
            @JsonProperty("gold_denied") Long goldDenied,
            @JsonProperty("gold_death_loss") Long goldDeathLoss,
            @JsonProperty("boss_damage") Long bossDamage,
            @JsonProperty("hero_bullets_hit") Long heroBulletsHit,
            @JsonProperty("hero_bullets_hit_crit") Long heroBulletsHitCrit
    ) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PowerUpBuff(
            @JsonProperty("type") String type,
            @JsonProperty("is_permanent") Boolean isPermanent,
            @JsonProperty("pickup_times_s") List<Integer> pickupTimesS
    ) { }
}
