package sntiago05.rejuv.clients;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse;
import org.springframework.web.client.RestClientException;
import sntiago05.rejuv.clients.dto.MatchMetadataResponse;
import sntiago05.rejuv.config.CacheConfig;
import sntiago05.rejuv.config.DeadlockApiProperties;
import sntiago05.rejuv.domain.ItemPurchase;
import sntiago05.rejuv.domain.MatchTelemetry;
import sntiago05.rejuv.domain.PlayerTelemetry;
import sntiago05.rejuv.domain.PowerUpBuff;
import sntiago05.rejuv.domain.StatSnapshot;

/**
 * Reads match telemetry from the deadlock-api JSON metadata endpoint ({@code GET /v1/matches/{match_id}/metadata}).
 */
@Component
public class JsonMatchMetadataSource implements MatchMetadataSource {

    private static final Logger log = LoggerFactory.getLogger(JsonMatchMetadataSource.class);

    private static final String METADATA_PATH = "/v1/matches/{match_id}/metadata";

    private final RestClient restClient;
    private final DeadlockApiProperties properties;
    private final Sleeper sleeper;

    public JsonMatchMetadataSource(RestClient deadlockApiRestClient, DeadlockApiProperties properties, Sleeper sleeper) {
        this.restClient = deadlockApiRestClient;
        this.properties = properties;
        this.sleeper = sleeper;
    }

    @Override
    @Cacheable(cacheNames = CacheConfig.DEADLOCK_MATCH_METADATA, sync = true)
    public MatchTelemetry getMatch(long matchId) {
        MatchMetadataResponse response = get(matchId);
        return mapToTelemetry(matchId, response);
    }

    private MatchMetadataResponse get(long matchId) {
        Response response = exchange(matchId);
        if (response.status() == HttpStatus.SERVICE_UNAVAILABLE.value()) {
            Duration wait = parseRetryAfter(response.retryAfter()).orElse(properties.defaultRetryAfter());
            if (wait.compareTo(properties.maxRetryAfter()) > 0) {
                log.warn("deadlock-api {} returned HTTP 503, wait {} s is above the cap, not retrying",
                        METADATA_PATH, wait.toSeconds());
                throw new DeadlockApiUnavailableException("GET " + METADATA_PATH + " returned HTTP 503", wait, null);
            }
            log.warn("deadlock-api {} returned HTTP 503, retrying once after {} ms", METADATA_PATH, wait.toMillis());
            sleep(wait);
            response = exchange(matchId);
            if (!response.success()) {
                Duration retryAfter = parseRetryAfter(response.retryAfter()).orElse(properties.defaultRetryAfter());
                log.warn("deadlock-api {} returned HTTP {} after one retry", METADATA_PATH, response.status());
                throw new DeadlockApiUnavailableException(
                        "GET " + METADATA_PATH + " returned HTTP " + response.status() + " after one retry", retryAfter, null);
            }
        }
        if (response.success()) {
            return response.body();
        }
        if (response.status() == HttpStatus.TOO_MANY_REQUESTS.value()) {
            log.warn("deadlock-api {} returned HTTP 429, not retrying", METADATA_PATH);
            throw new DeadlockApiUnavailableException("GET " + METADATA_PATH + " returned HTTP 429",
                    parseRetryAfter(response.retryAfter()).orElse(null), null);
        }
        if (response.status() == HttpStatus.NOT_FOUND.value()) {
            log.warn("deadlock-api {} returned HTTP 404, not retrying", METADATA_PATH);
            throw new MatchMetadataNotFoundException("GET " + METADATA_PATH + " returned HTTP 404");
        }
        throw new DeadlockApiException("GET " + METADATA_PATH + " returned HTTP " + response.status());
    }

    private Response exchange(long matchId) {
        log.debug("deadlock-api GET /v1/matches/{}/metadata", matchId);
        try {
            return restClient.get()
                    .uri(METADATA_PATH, matchId)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (response.getStatusCode().is2xxSuccessful()) {
                            return Response.success(status, readBody(status, response));
                        }
                        return Response.failure(status, response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
                    });
        } catch (ResourceAccessException e) {
            log.warn("deadlock-api {} failed with an I/O error or timeout", METADATA_PATH);
            throw new DeadlockApiUnavailableException("GET " + METADATA_PATH + " failed with an I/O error or timeout", null, e);
        } catch (RestClientException e) {
            throw new DeadlockApiException("GET " + METADATA_PATH + " failed", e);
        }
    }

    private static MatchMetadataResponse readBody(int status, ConvertibleClientHttpResponse response) {
        MatchMetadataResponse body;
        try {
            body = response.bodyTo(MatchMetadataResponse.class);
        } catch (RestClientException e) {
            if (e.getCause() instanceof IOException || e.getCause() instanceof UncheckedIOException) {
                log.warn("deadlock-api {} failed with an I/O error while reading the body", METADATA_PATH);
                throw new DeadlockApiUnavailableException("GET " + METADATA_PATH + " failed while reading the body", null, e);
            }
            throw new DeadlockApiException("GET " + METADATA_PATH + " returned an unreadable body (HTTP " + status + ")", e);
        }
        if (body == null) {
            throw new DeadlockApiException("GET " + METADATA_PATH + " returned an empty body (HTTP " + status + ")");
        }
        return body;
    }

    private void sleep(Duration wait) {
        try {
            sleeper.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeadlockApiUnavailableException("GET " + METADATA_PATH + " was interrupted while waiting to retry",
                    wait, e);
        }
    }

    private static Optional<Duration> parseRetryAfter(String header) {
        if (header == null) {
            return Optional.empty();
        }
        try {
            long seconds = Long.parseLong(header.trim());
            return seconds > 0 ? Optional.of(Duration.ofSeconds(seconds)) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
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

        return new MatchTelemetry(matchInfo.matchId(), matchInfo.durationS(), playerTelemetries);
    }

    private static PlayerTelemetry mapPlayer(MatchMetadataResponse.Player player) {
        long accountId = player.accountId() != null ? player.accountId() : 0L;
        int team = player.team() != null ? player.team() : 0;
        int heroId = player.heroId() != null ? player.heroId() : 0;

        List<ItemPurchase> items = player.items() == null ? List.of() : player.items().stream()
                .map(item -> new ItemPurchase(
                        item.itemId() != null ? item.itemId() : 0L,
                        item.gameTimeS() != null ? item.gameTimeS() : 0,
                        item.soldTimeS() != null && item.soldTimeS() > 0 ? item.soldTimeS() : null))
                .sorted(Comparator.comparingInt(ItemPurchase::boughtAtS))
                .toList();

        List<StatSnapshot> snapshots = player.stats() == null ? List.of() : player.stats().stream()
                .map(stat -> new StatSnapshot(
                        stat.timeStampS() != null ? stat.timeStampS() : 0,
                        stat.netWorth() != null ? stat.netWorth() : 0L,
                        stat.playerDamage() != null ? stat.playerDamage() : 0L,
                        stat.playerHealing() != null ? stat.playerHealing() : 0L))
                .sorted(Comparator.comparingInt(StatSnapshot::timeS))
                .toList();

        List<PowerUpBuff> buffs = player.powerUpBuffs() == null ? List.of() : player.powerUpBuffs().stream()
                .filter(buff -> buff.type() != null)
                .map(buff -> {
                    List<Integer> pickupTimes = buff.pickupTimesS() == null
                            ? List.of()
                            : buff.pickupTimesS().stream().sorted().toList();
                    return new PowerUpBuff(buff.type(), Boolean.TRUE.equals(buff.isPermanent()), pickupTimes);
                })
                .toList();

        return new PlayerTelemetry(accountId, team, heroId, items, snapshots, buffs);
    }

    private record Response(int status, boolean success, MatchMetadataResponse body, String retryAfter) {

        static Response success(int status, MatchMetadataResponse body) {
            return new Response(status, true, body, null);
        }

        static Response failure(int status, String retryAfter) {
            return new Response(status, false, null, retryAfter);
        }
    }
}
