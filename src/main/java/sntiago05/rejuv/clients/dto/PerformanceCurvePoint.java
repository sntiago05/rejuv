package sntiago05.rejuv.clients.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PerformanceCurvePoint(
        @JsonProperty("game_time") int gameTime,
        @JsonProperty("net_worth_avg") double netWorthAvg,
        @JsonProperty("net_worth_std") double netWorthStd,
        @JsonProperty("kills_avg") double killsAvg,
        @JsonProperty("kills_std") double killsStd,
        @JsonProperty("deaths_avg") double deathsAvg,
        @JsonProperty("deaths_std") double deathsStd,
        @JsonProperty("assists_avg") double assistsAvg,
        @JsonProperty("assists_std") double assistsStd,
        @JsonProperty("player_damage_avg") double playerDamageAvg,
        @JsonProperty("player_damage_std") double playerDamageStd,
        @JsonProperty("denies_avg") double deniesAvg,
        @JsonProperty("denies_std") double deniesStd,
        @JsonProperty("creep_kills_avg") double creepKillsAvg,
        @JsonProperty("creep_kills_std") double creepKillsStd
) {
}
