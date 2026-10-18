package sntiago05.rejuv.clients;

import java.util.Comparator;
import java.util.List;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import sntiago05.rejuv.clients.DeadlockApiRequests.BodyReader;
import sntiago05.rejuv.clients.dto.MatchMetadataResponse;
import sntiago05.rejuv.config.CacheConfig;
import sntiago05.rejuv.config.DeadlockApiProperties;
import sntiago05.rejuv.domain.DeathEvent;
import sntiago05.rejuv.domain.ItemPurchase;
import sntiago05.rejuv.domain.MatchTelemetry;
import sntiago05.rejuv.domain.MidBossEvent;
import sntiago05.rejuv.domain.PlayerTelemetry;
import sntiago05.rejuv.domain.PowerUpBuff;
import sntiago05.rejuv.domain.SnapshotDetail;
import sntiago05.rejuv.domain.StatSnapshot;

/**
 * Reads match telemetry from the deadlock-api JSON metadata endpoint ({@code GET /v1/matches/{match_id}/metadata}).
 */
@Component
public class JsonMatchMetadataSource implements MatchMetadataSource {

    private static final String METADATA_PATH = "/v1/matches/{match_id}/metadata";

    private static final BodyReader<MatchMetadataResponse> METADATA_READER =
            DeadlockApiRequests.json(new ParameterizedTypeReference<>() { });

    private final DeadlockApiRequests requests;

    public JsonMatchMetadataSource(RestClient deadlockApiRestClient, DeadlockApiProperties properties, Sleeper sleeper) {
        this.requests = new DeadlockApiRequests(deadlockApiRestClient, properties, sleeper);
    }

    @Override
    @Cacheable(cacheNames = CacheConfig.DEADLOCK_MATCH_METADATA, sync = true)
    public MatchTelemetry getMatch(long matchId) {
        MatchMetadataResponse response = requests.get(METADATA_PATH,
                uri -> uri.path(METADATA_PATH).build(matchId), METADATA_READER, false,
                () -> new MatchMetadataNotFoundException("GET " + METADATA_PATH + " returned HTTP 404"));
        return mapToTelemetry(matchId, response);
    }

    private static MatchTelemetry mapToTelemetry(long matchId, MatchMetadataResponse response) {
        if (response == null || response.matchInfo() == null) {
            throw new DeadlockApiException("Invalid body: match_info is missing");
        }
        MatchMetadataResponse.MatchInfo matchInfo = response.matchInfo();
        if (matchInfo.matchId() == null || matchInfo.durationS() == null) {
            throw new DeadlockApiException("Invalid body: match_id or duration_s is missing in match_info");
        }
        if (matchInfo.matchId() != matchId) {
            throw new DeadlockApiException("Match id mismatch: requested " + matchId + " but body had " + matchInfo.matchId());
        }
        if (matchInfo.players() == null || matchInfo.players().isEmpty()) {
            throw new DeadlockApiException("Invalid body: players is missing or empty");
        }

        List<PlayerTelemetry> playerTelemetries = matchInfo.players().stream()
                .map(JsonMatchMetadataSource::mapPlayer)
                .toList();

        List<MidBossEvent> midBossEvents = matchInfo.midBoss() == null ? List.of() : matchInfo.midBoss().stream()
                .filter(mb -> mb != null && mb.destroyedTimeS() != null && mb.teamKilled() != null && mb.teamClaimed() != null)
                .map(mb -> new MidBossEvent(mb.destroyedTimeS(), mb.teamKilled(), mb.teamClaimed()))
                .toList();

        Integer averageBadge = computeAverageBadge(matchInfo.averageBadgeTeam0(), matchInfo.averageBadgeTeam1());
        return new MatchTelemetry(matchInfo.matchId(), matchInfo.durationS(), playerTelemetries, averageBadge, midBossEvents);
    }

    static Integer computeAverageBadge(Integer team0, Integer team1) {
        boolean valid0 = team0 != null && team0 > 0;
        boolean valid1 = team1 != null && team1 > 0;
        if (valid0 && valid1) {
            return (team0 + team1) / 2;
        } else if (valid0) {
            return team0;
        } else if (valid1) {
            return team1;
        } else {
            return null;
        }
    }

    private static PlayerTelemetry mapPlayer(MatchMetadataResponse.Player player) {
        required(player, "players[]");
        long accountId = required(player.accountId(), "account_id");
        int team = required(player.team(), "team");
        int heroId = required(player.heroId(), "hero_id");

        List<ItemPurchase> items = player.items() == null ? List.of() : player.items().stream()
                .map(item -> required(item, "items[]"))
                .map(item -> new ItemPurchase(
                        required(item.itemId(), "item_id"),
                        required(item.gameTimeS(), "game_time_s"),
                        item.soldTimeS() != null && item.soldTimeS() > 0 ? item.soldTimeS() : null))
                .sorted(Comparator.comparingInt(ItemPurchase::boughtAtS))
                .toList();

        List<StatSnapshot> snapshots = player.stats() == null ? List.of() : player.stats().stream()
                .map(stat -> required(stat, "stats[]"))
                .map(stat -> new StatSnapshot(
                        required(stat.timeStampS(), "time_stamp_s"),
                        required(stat.netWorth(), "net_worth"),
                        required(stat.playerDamage(), "player_damage"),
                        required(stat.playerHealing(), "player_healing"),
                        new SnapshotDetail(
                                zeroIfNull(stat.kills()),
                                zeroIfNull(stat.deaths()),
                                zeroIfNull(stat.assists()),
                                zeroIfNull(stat.creepKills()),
                                zeroIfNull(stat.possibleCreeps()),
                                zeroIfNull(stat.shotsHit()),
                                zeroIfNull(stat.shotsMissed()),
                                zeroIfNull(stat.denies()),
                                zeroIfNull(stat.playerDamageTaken()),
                                zeroIfNull(stat.goldLaneCreep()),
                                zeroIfNull(stat.goldNeutralCreep()),
                                zeroIfNull(stat.goldDenied()),
                                zeroIfNull(stat.goldDeathLoss()))))
                .sorted(Comparator.comparingInt(StatSnapshot::timeS))
                .toList();

        List<DeathEvent> deaths = player.deathDetails() == null ? List.of() : player.deathDetails().stream()
                .map(death -> required(death, "death_details[]"))
                .map(death -> new DeathEvent(
                        required(death.gameTimeS(), "death_details[].game_time_s"),
                        death.killerPlayerSlot(),
                        death.timeToKillS(),
                        death.deathDurationS() != null ? death.deathDurationS() : 0))
                .sorted(Comparator.comparingInt(DeathEvent::gameTimeS))
                .toList();

        List<PowerUpBuff> buffs = player.powerUpBuffs() == null ? List.of() : player.powerUpBuffs().stream()
                .filter(buff -> buff != null && buff.type() != null)
                .map(buff -> {
                    List<Integer> pickupTimes = buff.pickupTimesS() == null
                            ? List.of()
                            : buff.pickupTimesS().stream().sorted().toList();
                    return new PowerUpBuff(buff.type(), Boolean.TRUE.equals(buff.isPermanent()), pickupTimes);
                })
                .toList();

        Long bossDamage = null;
        if (player.stats() != null && !player.stats().isEmpty()) {
            bossDamage = player.stats().stream()
                    .filter(stat -> stat != null && stat.timeStampS() != null)
                    .max(Comparator.comparingInt(MatchMetadataResponse.Stat::timeStampS))
                    .map(MatchMetadataResponse.Stat::bossDamage)
                    .orElse(null);
        }

        return new PlayerTelemetry(accountId, team, heroId, items, snapshots, buffs,
                player.playerSlot(), player.assignedLane(), deaths, bossDamage);
    }

    private static int zeroIfNull(Integer value) {
        return value != null ? value : 0;
    }

    private static long zeroIfNull(Long value) {
        return value != null ? value : 0L;
    }

    private static <T> T required(T value, String field) {
        if (value == null) {
            throw new DeadlockApiException("Invalid body: " + field + " is missing");
        }
        return value;
    }
}
