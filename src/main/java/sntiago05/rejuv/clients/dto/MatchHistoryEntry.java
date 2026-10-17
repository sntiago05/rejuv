package sntiago05.rejuv.clients.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MatchHistoryEntry(@JsonProperty("match_id") long matchId,
                                @JsonProperty("hero_id") int heroId,
                                @JsonProperty("hero_level") int heroLevel,
                                @JsonProperty("start_time") long startTime,
                                @JsonProperty("match_duration_s") int matchDurationS,
                                @JsonProperty("match_mode") int matchMode,
                                @JsonProperty("match_result") int matchResult,
                                @JsonProperty("player_team") int playerTeam,
                                @JsonProperty("player_match_outcome") int playerMatchOutcome,
                                @JsonProperty("player_kills") int playerKills,
                                @JsonProperty("player_deaths") int playerDeaths,
                                @JsonProperty("player_assists") int playerAssists,
                                @JsonProperty("net_worth") long netWorth,
                                @JsonProperty("last_hits") int lastHits,
                                @JsonProperty("denies") int denies,
                                @JsonProperty("ranked_display_badge") Integer rankedDisplayBadge) {
}
