package sntiago05.rejuv.clients.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record HeroStat(int heroId,
                       long matches,
                       long wins,
                       long losses,
                       long totalKills,
                       long totalDeaths,
                       long totalAssists) {

    @JsonCreator
    public HeroStat(@JsonProperty("hero_id") int heroId,
                    @JsonProperty("matches") long matches,
                    @JsonProperty("wins") long wins,
                    @JsonProperty("losses") long losses,
                    @JsonProperty("total_kills") Long totalKills,
                    @JsonProperty("total_deaths") Long totalDeaths,
                    @JsonProperty("total_assists") Long totalAssists) {
        this(heroId, matches, wins, losses,
                totalKills != null ? totalKills : 0L,
                totalDeaths != null ? totalDeaths : 0L,
                totalAssists != null ? totalAssists : 0L);
    }

    /** Win and loss counts only; the kill, death and assist totals are 0. Keeps existing callers and tests compiling. */
    public HeroStat(int heroId, long matches, long wins, long losses) {
        this(heroId, matches, wins, losses, 0, 0, 0);
    }
}
