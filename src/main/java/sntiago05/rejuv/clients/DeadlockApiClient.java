package sntiago05.rejuv.clients;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;
import sntiago05.rejuv.clients.DeadlockApiRequests.BodyReader;
import sntiago05.rejuv.clients.dto.AbilityOrderStat;
import sntiago05.rejuv.clients.dto.AccountRank;
import sntiago05.rejuv.clients.dto.HeroAsset;
import sntiago05.rejuv.clients.dto.HeroCounterStat;
import sntiago05.rejuv.clients.dto.HeroStat;
import sntiago05.rejuv.clients.dto.ItemAsset;
import sntiago05.rejuv.clients.dto.ItemFlowStats;
import sntiago05.rejuv.clients.dto.ItemStat;
import sntiago05.rejuv.clients.dto.MatchHistoryEntry;
import sntiago05.rejuv.clients.dto.MateStat;
import sntiago05.rejuv.clients.dto.MetricPercentiles;
import sntiago05.rejuv.clients.dto.PlayerHeroStat;
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

    private static final int MIN_RANK_TIER = 0;
    private static final int MAX_RANK_TIER = 11;

    private static final String MATCH_HISTORY_PATH = "/v1/players/{account_id}/match-history";
    private static final String PLAYER_RANK_PATH = "/v1/players/{account_id}/rank";
    private static final String PLAYER_RANKS_PATH = "/v1/players/rank";
    private static final String METRICS_PATH = "/v1/analytics/player-stats/metrics";
    private static final String ITEM_STATS_PATH = "/v1/analytics/item-stats";
    private static final String HERO_STATS_PATH = "/v1/analytics/hero-stats";
    private static final String PLAYER_HERO_STATS_PATH = "/v1/players/hero-stats";
    private static final String MATE_STATS_PATH = "/v1/players/{account_id}/mate-stats";
    private static final String HERO_COUNTERS_PATH = "/v1/analytics/hero-counter-stats";
    private static final String ITEM_FLOW_PATH = "/v1/analytics/item-flow-stats";
    private static final String ABILITY_ORDER_PATH = "/v1/analytics/ability-order-stats";
    private static final String RANKS_PATH = "/v1/assets/ranks";
    private static final String HEROES_PATH = "/v1/assets/heroes";
    private static final String ITEMS_PATH = "/v1/assets/items";

    // Same mapper configuration as the RestClient's default JSON converter, so the DTOs are read identically.
    private static final BodyReader<List<MatchHistoryEntry>> HISTORY_READER = DeadlockApiRequests.jsonArrayPrefix(
            new JacksonJsonHttpMessageConverter().getMapper().readerFor(MatchHistoryEntry.class), MAX_HISTORY_ENTRIES);
    private static final BodyReader<PlayerRank> RANK_READER = DeadlockApiRequests.json(new ParameterizedTypeReference<>() { });
    private static final BodyReader<List<AccountRank>> PLAYER_RANKS_READER = DeadlockApiRequests.json(new ParameterizedTypeReference<>() { });
    private static final BodyReader<Map<String, MetricPercentiles>> METRICS_READER = DeadlockApiRequests.json(new ParameterizedTypeReference<>() { });
    private static final BodyReader<List<ItemStat>> ITEM_STATS_READER = DeadlockApiRequests.json(new ParameterizedTypeReference<>() { });
    private static final BodyReader<List<HeroStat>> HERO_STATS_READER = DeadlockApiRequests.json(new ParameterizedTypeReference<>() { });
    private static final BodyReader<List<PlayerHeroStat>> PLAYER_HERO_STATS_READER = DeadlockApiRequests.json(new ParameterizedTypeReference<>() { });
    private static final BodyReader<List<MateStat>> MATE_STATS_READER = DeadlockApiRequests.json(new ParameterizedTypeReference<>() { });
    private static final BodyReader<List<HeroCounterStat>> HERO_COUNTERS_READER = DeadlockApiRequests.json(new ParameterizedTypeReference<>() { });
    private static final BodyReader<ItemFlowStats> ITEM_FLOW_READER = DeadlockApiRequests.json(new ParameterizedTypeReference<>() { });
    private static final BodyReader<List<AbilityOrderStat>> ABILITY_ORDER_READER = DeadlockApiRequests.json(new ParameterizedTypeReference<>() { });
    private static final BodyReader<List<RankAsset>> RANKS_READER = DeadlockApiRequests.json(new ParameterizedTypeReference<>() { });
    private static final BodyReader<List<HeroAsset>> HEROES_READER = DeadlockApiRequests.json(new ParameterizedTypeReference<>() { });
    private static final BodyReader<List<ItemAsset>> ITEMS_READER = DeadlockApiRequests.json(new ParameterizedTypeReference<>() { });

    private final DeadlockApiRequests requests;

    public DeadlockApiClient(RestClient deadlockApiRestClient, DeadlockApiProperties properties, Sleeper sleeper) {
        this.requests = new DeadlockApiRequests(deadlockApiRestClient, properties, sleeper);
    }

    /**
     * Matches of the account, newest first, capped to {@link #MAX_HISTORY_ENTRIES}.
     * An empty list means the API has no matches for the account (the "account check").
     * Only the first entries of the body are read; the rest is not downloaded.
     */
    @Cacheable(cacheNames = CacheConfig.DEADLOCK_MATCH_HISTORY, sync = true)
    public List<MatchHistoryEntry> getMatchHistory(long accountId) {
        return List.copyOf(requests.get(MATCH_HISTORY_PATH,
                uri -> uri.path(MATCH_HISTORY_PATH).build(accountId), HISTORY_READER, true, null));
    }

    /**
     * The player's current rank. An unranked player is returned as {@code PlayerRank(0, 0, 0)}.
     */
    @Cacheable(cacheNames = CacheConfig.DEADLOCK_PLAYER_RANK, sync = true)
    public PlayerRank getPlayerRank(long accountId) {
        return requests.get(PLAYER_RANK_PATH, uri -> uri.path(PLAYER_RANK_PATH).build(accountId), RANK_READER);
    }

    /**
     * Current rank of many accounts in one request, keyed by account id. Accounts the API does not return are absent
     * from the map; an account without ranked data has badge 0. Callers pass a sorted, distinct, immutable list so the
     * cache key is stable. Callers must not call the analytics-style methods in parallel for different uncached keys.
     *
     * @throws IllegalArgumentException if the list is empty, has more than 1000 ids, or contains a negative id (before any HTTP call)
     */
    @Cacheable(cacheNames = CacheConfig.DEADLOCK_PLAYER_RANKS, sync = true, key = "#accountIds")
    public Map<Long, AccountRank> getPlayerRanks(List<Long> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            throw new IllegalArgumentException("accountIds must not be empty");
        }
        if (accountIds.size() > 1000) {
            throw new IllegalArgumentException("accountIds must not contain more than 1000 entries");
        }
        for (Long accountId : accountIds) {
            if (accountId == null || accountId < 0) {
                throw new IllegalArgumentException("accountId must not be negative");
            }
        }
        String joined = accountIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        List<AccountRank> ranks = requests.get(PLAYER_RANKS_PATH,
                uri -> uri.path(PLAYER_RANKS_PATH).queryParam("account_ids", joined).build(),
                PLAYER_RANKS_READER);
        return ranks.stream()
                .collect(Collectors.toUnmodifiableMap(AccountRank::accountId, Function.identity(), (first, second) -> first));
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
        Map<String, MetricPercentiles> metrics = requests.get(METRICS_PATH,
                uri -> badgeRange(uri.path(METRICS_PATH).queryParam("hero_ids", heroId), rankTier), METRICS_READER);
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
        List<ItemStat> stats = requests.get(ITEM_STATS_PATH,
                uri -> badgeRange(uri.path(ITEM_STATS_PATH).queryParam("hero_ids", heroId), rankTier), ITEM_STATS_READER);
        return stats.stream()
                .collect(Collectors.toUnmodifiableMap(ItemStat::itemId, Function.identity(), (first, second) -> first));
    }

    @Cacheable(cacheNames = CacheConfig.DEADLOCK_ASSETS, sync = true, key = "'ranks'")
    public List<RankAsset> getRanks() {
        return List.copyOf(requests.get(RANKS_PATH, uri -> uri.path(RANKS_PATH).build(), RANKS_READER));
    }

    @Cacheable(cacheNames = CacheConfig.DEADLOCK_ASSETS, sync = true, key = "'heroes'")
    public List<HeroAsset> getHeroes() {
        return List.copyOf(requests.get(HEROES_PATH, uri -> uri.path(HEROES_PATH).build(), HEROES_READER));
    }

    @Cacheable(cacheNames = CacheConfig.DEADLOCK_ASSETS, sync = true, key = "'items'")
    public List<ItemAsset> getItems() {
        return List.copyOf(requests.get(ITEMS_PATH, uri -> uri.path(ITEMS_PATH).build(), ITEMS_READER));
    }

    /**
     * Per-hero win and match counts since minUnixTimestamp. rankTier null = all ranks (no badge filter).
     * Callers must not call the analytics methods in parallel for different uncached keys.
     *
     * @throws IllegalArgumentException if rankTier is non-null and not in 0..11 (before any HTTP call)
     */
    @Cacheable(cacheNames = CacheConfig.DEADLOCK_HERO_STATS, sync = true, key = "#rankTier + ':' + #minUnixTimestamp")
    public List<HeroStat> getHeroStats(Integer rankTier, long minUnixTimestamp) {
        requireRankTierIfPresent(rankTier);
        List<HeroStat> stats = requests.get(HERO_STATS_PATH,
                uri -> badgeRange(uri.path(HERO_STATS_PATH).queryParam("min_unix_timestamp", minUnixTimestamp), rankTier),
                HERO_STATS_READER);
        return List.copyOf(stats);
    }

    /**
     * Per-hero totals of one player for matches that started at or after minUnixTimestamp (game mode normal,
     * API defaults otherwise). An empty list means no matches in the window.
     * Callers must not call the analytics-style methods in parallel for different uncached keys.
     */
    @Cacheable(cacheNames = CacheConfig.DEADLOCK_PLAYER_HERO_STATS, sync = true, key = "#accountId + ':' + #minUnixTimestamp")
    public List<PlayerHeroStat> getPlayerHeroStats(long accountId, long minUnixTimestamp) {
        List<PlayerHeroStat> stats = requests.get(PLAYER_HERO_STATS_PATH,
                uri -> uri.path(PLAYER_HERO_STATS_PATH)
                        .queryParam("account_ids", accountId)
                        .queryParam("min_unix_timestamp", minUnixTimestamp)
                        .build(),
                PLAYER_HERO_STATS_READER);
        return List.copyOf(stats);
    }

    /**
     * Teammates of one player (matches that started at or after minUnixTimestamp). Empty list = no data.
     * Callers must not call the analytics-style methods in parallel for different uncached keys.
     */
    @Cacheable(cacheNames = CacheConfig.DEADLOCK_MATE_STATS, sync = true, key = "#accountId + ':' + #minUnixTimestamp")
    public List<MateStat> getMateStats(long accountId, long minUnixTimestamp) {
        List<MateStat> stats = requests.get(MATE_STATS_PATH,
                uri -> uri.path(MATE_STATS_PATH)
                        .queryParam("min_unix_timestamp", minUnixTimestamp)
                        .build(accountId),
                MATE_STATS_READER);
        return List.copyOf(stats);
    }

    /**
     * Item build flow of one hero since minUnixTimestamp. rankTier null = all ranks (no badge filter).
     * Callers must not call the analytics methods in parallel for different uncached keys.
     * @throws IllegalArgumentException if rankTier is non-null and not in 0..11 (before any HTTP call)
     */
    @Cacheable(cacheNames = CacheConfig.DEADLOCK_ITEM_FLOW, sync = true,
            key = "#heroId + ':' + #rankTier + ':' + #minUnixTimestamp")
    public ItemFlowStats getItemFlowStats(int heroId, Integer rankTier, long minUnixTimestamp) {
        requireRankTierIfPresent(rankTier);
        return requests.get(ITEM_FLOW_PATH,
                uri -> badgeRange(uri.path(ITEM_FLOW_PATH)
                        .queryParam("hero_ids", heroId)
                        .queryParam("min_unix_timestamp", minUnixTimestamp), rankTier),
                ITEM_FLOW_READER);
    }

    /** Ability orders of one hero (one entry per distinct order). Same rank, window and error rules. */
    @Cacheable(cacheNames = CacheConfig.DEADLOCK_ABILITY_ORDER, sync = true,
            key = "#heroId + ':' + #rankTier + ':' + #minUnixTimestamp")
    public List<AbilityOrderStat> getAbilityOrderStats(int heroId, Integer rankTier, long minUnixTimestamp) {
        requireRankTierIfPresent(rankTier);
        List<AbilityOrderStat> stats = requests.get(ABILITY_ORDER_PATH,
                uri -> badgeRange(uri.path(ABILITY_ORDER_PATH)
                        .queryParam("hero_id", heroId)
                        .queryParam("min_unix_timestamp", minUnixTimestamp), rankTier),
                ABILITY_ORDER_READER);
        return List.copyOf(stats);
    }

    /**
     * Every hero-versus-hero pair since minUnixTimestamp. rankTier null = all ranks (no badge filter).
     * Callers must not call the analytics methods in parallel for different uncached keys.
     * @throws IllegalArgumentException if rankTier is non-null and not in 0..11 (before any HTTP call)
     */
    @Cacheable(cacheNames = CacheConfig.DEADLOCK_HERO_COUNTERS, sync = true, key = "#rankTier + ':' + #minUnixTimestamp")
    public List<HeroCounterStat> getHeroCounterStats(Integer rankTier, long minUnixTimestamp) {
        requireRankTierIfPresent(rankTier);
        List<HeroCounterStat> stats = requests.get(HERO_COUNTERS_PATH,
                uri -> badgeRange(uri.path(HERO_COUNTERS_PATH).queryParam("min_unix_timestamp", minUnixTimestamp), rankTier),
                HERO_COUNTERS_READER);
        return List.copyOf(stats);
    }

    /**
     * Win and match counts per item when the enemy team has enemyHeroId. Same rank, window and error rules.
     * One row per item. Not hero-specific: it covers the items bought by any hero facing enemyHeroId.
     */
    @Cacheable(cacheNames = CacheConfig.DEADLOCK_COUNTER_ITEMS, sync = true,
            key = "#enemyHeroId + ':' + #rankTier + ':' + #minUnixTimestamp")
    public List<ItemStat> getItemStatsAgainst(int enemyHeroId, Integer rankTier, long minUnixTimestamp) {
        requireRankTierIfPresent(rankTier);
        List<ItemStat> stats = requests.get(ITEM_STATS_PATH,
                uri -> badgeRange(uri.path(ITEM_STATS_PATH)
                        .queryParam("enemy_hero_ids", enemyHeroId)
                        .queryParam("min_unix_timestamp", minUnixTimestamp), rankTier),
                ITEM_STATS_READER);
        return List.copyOf(stats);
    }

    private static void requireRankTier(int rankTier) {
        if (rankTier < MIN_RANK_TIER || rankTier > MAX_RANK_TIER) {
            throw new IllegalArgumentException("rankTier must be in " + MIN_RANK_TIER + ".." + MAX_RANK_TIER
                    + ", was " + rankTier);
        }
    }

    private static void requireRankTierIfPresent(Integer rankTier) {
        if (rankTier != null) {
            requireRankTier(rankTier);
        }
    }

    /** Adds the badge range of the tier ({@code tier*10 .. tier*10+9}); a null tier adds no badge filter. */
    private static URI badgeRange(UriBuilder uri, Integer rankTier) {
        if (rankTier != null) {
            uri.queryParam("min_average_badge", rankTier * 10)
                    .queryParam("max_average_badge", rankTier * 10 + 9);
        }
        return uri.build();
    }
}
