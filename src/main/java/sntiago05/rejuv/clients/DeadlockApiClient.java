package sntiago05.rejuv.clients;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;
import sntiago05.rejuv.clients.dto.HeroAsset;
import sntiago05.rejuv.clients.dto.HeroStat;
import sntiago05.rejuv.clients.dto.ItemAsset;
import sntiago05.rejuv.clients.dto.ItemStat;
import sntiago05.rejuv.clients.dto.MatchHistoryEntry;
import sntiago05.rejuv.clients.dto.MetricPercentiles;
import sntiago05.rejuv.clients.dto.PlayerRank;
import sntiago05.rejuv.clients.dto.RankAsset;
import sntiago05.rejuv.config.CacheConfig;
import sntiago05.rejuv.config.DeadlockApiProperties;

/**
 * Cached gateway to deadlock-api.com (everything except match metadata).
 * Returns API-shaped data; callers interpret it.
 */
@Component
public class DeadlockApiClient {

    public static final int MAX_HISTORY_ENTRIES = 200;

    private static final Logger log = LoggerFactory.getLogger(DeadlockApiClient.class);

    private static final int MIN_RANK_TIER = 0;
    private static final int MAX_RANK_TIER = 11;

    private static final String MATCH_HISTORY_PATH = "/v1/players/{account_id}/match-history";
    private static final String PLAYER_RANK_PATH = "/v1/players/{account_id}/rank";
    private static final String METRICS_PATH = "/v1/analytics/player-stats/metrics";
    private static final String ITEM_STATS_PATH = "/v1/analytics/item-stats";
    private static final String HERO_STATS_PATH = "/v1/analytics/hero-stats";
    private static final String RANKS_PATH = "/v1/assets/ranks";
    private static final String HEROES_PATH = "/v1/assets/heroes";
    private static final String ITEMS_PATH = "/v1/assets/items";

    private static final ParameterizedTypeReference<List<MatchHistoryEntry>> HISTORY_TYPE = new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<PlayerRank> RANK_TYPE = new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<Map<String, MetricPercentiles>> METRICS_TYPE = new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<List<ItemStat>> ITEM_STATS_TYPE = new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<List<HeroStat>> HERO_STATS_TYPE = new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<List<RankAsset>> RANKS_TYPE = new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<List<HeroAsset>> HEROES_TYPE = new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<List<ItemAsset>> ITEMS_TYPE = new ParameterizedTypeReference<>() { };

    private final RestClient restClient;
    private final DeadlockApiProperties properties;
    private final Sleeper sleeper;

    public DeadlockApiClient(RestClient deadlockApiRestClient, DeadlockApiProperties properties, Sleeper sleeper) {
        this.restClient = deadlockApiRestClient;
        this.properties = properties;
        this.sleeper = sleeper;
    }

    /**
     * Matches of the account, newest first, capped to {@link #MAX_HISTORY_ENTRIES}.
     * An empty list means the API has no matches for the account (the "account check").
     */
    @Cacheable(cacheNames = CacheConfig.DEADLOCK_MATCH_HISTORY, sync = true)
    public List<MatchHistoryEntry> getMatchHistory(long accountId) {
        List<MatchHistoryEntry> entries = get(MATCH_HISTORY_PATH,
                uri -> uri.path(MATCH_HISTORY_PATH).build(accountId), HISTORY_TYPE, true);
        return List.copyOf(entries.subList(0, Math.min(entries.size(), MAX_HISTORY_ENTRIES)));
    }

    /**
     * The player's current rank. An unranked player is returned as {@code PlayerRank(0, 0, 0)}.
     */
    @Cacheable(cacheNames = CacheConfig.DEADLOCK_PLAYER_RANK, sync = true)
    public PlayerRank getPlayerRank(long accountId) {
        return get(PLAYER_RANK_PATH, uri -> uri.path(PLAYER_RANK_PATH).build(accountId), RANK_TYPE, false);
    }

    /**
     * Percentile benchmarks keyed by the API metric name, for one hero and rank tier (last 30 days, API default).
     * Callers must not call the analytics methods in parallel for different uncached keys.
     *
     * @throws IllegalArgumentException if {@code rankTier} is not in 0..11
     */
    @Cacheable(cacheNames = CacheConfig.DEADLOCK_BENCHMARKS, sync = true, key = "#heroId + ':' + #rankTier")
    public Map<String, MetricPercentiles> getBenchmarks(int heroId, int rankTier) {
        requireRankTier(rankTier);
        Map<String, MetricPercentiles> metrics = get(METRICS_PATH,
                uri -> heroAndBadgeFilter(uri.path(METRICS_PATH), heroId, rankTier), METRICS_TYPE, false);
        return Map.copyOf(metrics);
    }

    /**
     * Average item buy times keyed by item id, for one hero and rank tier (averages only, no percentiles).
     * Callers must not call the analytics methods in parallel for different uncached keys.
     *
     * @throws IllegalArgumentException if {@code rankTier} is not in 0..11
     */
    @Cacheable(cacheNames = CacheConfig.DEADLOCK_ITEM_STATS, sync = true, key = "#heroId + ':' + #rankTier")
    public Map<Long, ItemStat> getItemStats(int heroId, int rankTier) {
        requireRankTier(rankTier);
        List<ItemStat> stats = get(ITEM_STATS_PATH,
                uri -> heroAndBadgeFilter(uri.path(ITEM_STATS_PATH), heroId, rankTier), ITEM_STATS_TYPE, false);
        return stats.stream()
                .collect(Collectors.toUnmodifiableMap(ItemStat::itemId, Function.identity(), (first, second) -> first));
    }

    @Cacheable(cacheNames = CacheConfig.DEADLOCK_ASSETS, sync = true, key = "'ranks'")
    public List<RankAsset> getRanks() {
        return List.copyOf(get(RANKS_PATH, uri -> uri.path(RANKS_PATH).build(), RANKS_TYPE, false));
    }

    @Cacheable(cacheNames = CacheConfig.DEADLOCK_ASSETS, sync = true, key = "'heroes'")
    public List<HeroAsset> getHeroes() {
        return List.copyOf(get(HEROES_PATH, uri -> uri.path(HEROES_PATH).build(), HEROES_TYPE, false));
    }

    @Cacheable(cacheNames = CacheConfig.DEADLOCK_ASSETS, sync = true, key = "'items'")
    public List<ItemAsset> getItems() {
        return List.copyOf(get(ITEMS_PATH, uri -> uri.path(ITEMS_PATH).build(), ITEMS_TYPE, false));
    }

    /**
     * Per-hero win and match counts since minUnixTimestamp. rankTier null = all ranks (no badge filter).
     * Callers must not call the analytics methods in parallel for different uncached keys.
     *
     * @throws IllegalArgumentException if rankTier is non-null and not in 0..11 (before any HTTP call)
     */
    @Cacheable(cacheNames = CacheConfig.DEADLOCK_HERO_STATS, sync = true, key = "#rankTier + ':' + #minUnixTimestamp")
    public List<HeroStat> getHeroStats(Integer rankTier, long minUnixTimestamp) {
        if (rankTier != null) {
            requireRankTier(rankTier);
        }
        List<HeroStat> stats = get(HERO_STATS_PATH,
                uri -> heroStatsFilter(uri.path(HERO_STATS_PATH), rankTier, minUnixTimestamp),
                HERO_STATS_TYPE, false);
        return List.copyOf(stats);
    }

    private static void requireRankTier(int rankTier) {
        if (rankTier < MIN_RANK_TIER || rankTier > MAX_RANK_TIER) {
            throw new IllegalArgumentException("rankTier must be in " + MIN_RANK_TIER + ".." + MAX_RANK_TIER
                    + ", was " + rankTier);
        }
    }

    private static URI heroAndBadgeFilter(UriBuilder uri, int heroId, int rankTier) {
        return uri.queryParam("hero_ids", heroId)
                .queryParam("min_average_badge", rankTier * 10)
                .queryParam("max_average_badge", rankTier * 10 + 9)
                .build();
    }

    private static URI heroStatsFilter(UriBuilder uri, Integer rankTier, long minUnixTimestamp) {
        uri.queryParam("min_unix_timestamp", minUnixTimestamp);
        if (rankTier != null) {
            uri.queryParam("min_average_badge", rankTier * 10)
                    .queryParam("max_average_badge", rankTier * 10 + 9);
        }
        return uri.build();
    }

    /**
     * Sends the request and retries once on HTTP 503 when the {@code Retry-After} wait is within the cap.
     */
    private <T> T get(String path, Function<UriBuilder, URI> uri, ParameterizedTypeReference<T> type,
                      boolean rateLimitedHistoryIsSuccess) {
        Response<T> response = exchange(path, uri, type, rateLimitedHistoryIsSuccess);
        if (response.status() == HttpStatus.SERVICE_UNAVAILABLE.value()) {
            Duration wait = parseRetryAfter(response.retryAfter()).orElse(properties.defaultRetryAfter());
            if (wait.compareTo(properties.maxRetryAfter()) > 0) {
                log.warn("deadlock-api {} returned HTTP 503, wait {} s is above the cap, not retrying",
                        path, wait.toSeconds());
                throw new DeadlockApiUnavailableException("GET " + path + " returned HTTP 503", wait, null);
            }
            log.warn("deadlock-api {} returned HTTP 503, retrying once after {} ms", path, wait.toMillis());
            sleep(path, wait);
            response = exchange(path, uri, type, rateLimitedHistoryIsSuccess);
            if (!response.success()) {
                Duration retryAfter = parseRetryAfter(response.retryAfter()).orElse(properties.defaultRetryAfter());
                log.warn("deadlock-api {} returned HTTP {} after one retry", path, response.status());
                throw new DeadlockApiUnavailableException(
                        "GET " + path + " returned HTTP " + response.status() + " after one retry", retryAfter, null);
            }
        }
        if (response.success()) {
            return response.body();
        }
        if (response.status() == HttpStatus.TOO_MANY_REQUESTS.value()) {
            log.warn("deadlock-api {} returned HTTP 429, not retrying", path);
            throw new DeadlockApiUnavailableException("GET " + path + " returned HTTP 429",
                    parseRetryAfter(response.retryAfter()).orElse(null), null);
        }
        throw new DeadlockApiException("GET " + path + " returned HTTP " + response.status());
    }

    private <T> Response<T> exchange(String path, Function<UriBuilder, URI> uri, ParameterizedTypeReference<T> type,
                                     boolean rateLimitedHistoryIsSuccess) {
        log.debug("deadlock-api GET {}", path);
        try {
            return restClient.get().uri(uri).exchange((request, response) -> {
                int status = response.getStatusCode().value();
                if (response.getStatusCode().is2xxSuccessful()) {
                    return Response.success(status, readBody(path, status, response, type));
                }
                if (status == HttpStatus.TOO_MANY_REQUESTS.value() && rateLimitedHistoryIsSuccess) {
                    return Response.success(status, readRateLimitedHistory(path, response, type));
                }
                return Response.failure(status, response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
            });
        } catch (ResourceAccessException e) {
            log.warn("deadlock-api {} failed with an I/O error or timeout", path);
            throw new DeadlockApiUnavailableException("GET " + path + " failed with an I/O error or timeout", null, e);
        } catch (RestClientException e) {
            throw new DeadlockApiException("GET " + path + " failed", e);
        }
    }

    private static <T> T readBody(String path, int status, ConvertibleClientHttpResponse response,
                                  ParameterizedTypeReference<T> type) {
        T body;
        try {
            body = response.bodyTo(type);
        } catch (RestClientException e) {
            if (e.getCause() instanceof IOException || e.getCause() instanceof UncheckedIOException) {
                log.warn("deadlock-api {} failed with an I/O error while reading the body", path);
                throw new DeadlockApiUnavailableException("GET " + path + " failed while reading the body", null, e);
            }
            throw new DeadlockApiException("GET " + path + " returned an unreadable body (HTTP " + status + ")", e);
        }
        if (body == null) {
            throw new DeadlockApiException("GET " + path + " returned an empty body (HTTP " + status + ")");
        }
        return body;
    }

    /** On HTTP 429 the history endpoint returns the stored history as the body. */
    private static <T> T readRateLimitedHistory(String path, ConvertibleClientHttpResponse response,
                                                ParameterizedTypeReference<T> type) {
        Duration retryAfter = parseRetryAfter(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).orElse(null);
        T body;
        try {
            body = response.bodyTo(type);
        } catch (RestClientException e) {
            log.warn("deadlock-api {} returned HTTP 429 without a readable stored history", path);
            throw new DeadlockApiUnavailableException("GET " + path + " returned HTTP 429", retryAfter, e);
        }
        if (body == null) {
            log.warn("deadlock-api {} returned HTTP 429 without a stored history", path);
            throw new DeadlockApiUnavailableException("GET " + path + " returned HTTP 429", retryAfter, null);
        }
        log.warn("deadlock-api {} returned HTTP 429, using the stored history from the body", path);
        return body;
    }

    private void sleep(String path, Duration wait) {
        try {
            sleeper.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeadlockApiUnavailableException("GET " + path + " was interrupted while waiting to retry",
                    wait, e);
        }
    }

    /** An integer number of seconds greater than zero; anything else is treated as missing. */
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

    private record Response<T>(int status, boolean success, T body, String retryAfter) {

        static <T> Response<T> success(int status, T body) {
            return new Response<>(status, true, body, null);
        }

        static <T> Response<T> failure(int status, String retryAfter) {
            return new Response<>(status, false, null, retryAfter);
        }
    }
}
