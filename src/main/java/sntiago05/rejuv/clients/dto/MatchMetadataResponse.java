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
            @JsonProperty("players") List<Player> players
    ) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Player(
            @JsonProperty("account_id") Long accountId,
            @JsonProperty("team") Integer team,
            @JsonProperty("hero_id") Integer heroId,
            @JsonProperty("items") List<Item> items,
            @JsonProperty("stats") List<Stat> stats,
            @JsonProperty("power_up_buffs") List<PowerUpBuff> powerUpBuffs
    ) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(
            @JsonProperty("item_id") Long itemId,
            @JsonProperty("game_time_s") Integer gameTimeS,
            @JsonProperty("sold_time_s") Integer soldTimeS
    ) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Stat(
            @JsonProperty("time_stamp_s") Integer timeStampS,
            @JsonProperty("net_worth") Long netWorth,
            @JsonProperty("player_damage") Long playerDamage,
            @JsonProperty("player_healing") Long playerHealing
    ) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PowerUpBuff(
            @JsonProperty("type") String type,
            @JsonProperty("is_permanent") Boolean isPermanent,
            @JsonProperty("pickup_times_s") List<Integer> pickupTimesS
    ) { }
}
