package sntiago05.rejuv.config;

import java.time.Duration;
import java.util.List;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableCaching
public class CacheConfig {

    public static final String DEADLOCK_ASSETS = "deadlockAssets";               // 24 h, max 10
    public static final String DEADLOCK_BENCHMARKS = "deadlockBenchmarks";       // 6 h, max 500
    public static final String DEADLOCK_ITEM_STATS = "deadlockItemStats";        // 6 h, max 500
    public static final String DEADLOCK_MATCH_HISTORY = "deadlockMatchHistory";  // 2 min, max 1000
    public static final String DEADLOCK_PLAYER_RANK = "deadlockPlayerRank";      // 10 min, max 1000
    public static final String DEADLOCK_PLAYER_RANKS = "deadlockPlayerRanks";    // 10 min, max 100
    public static final String DEADLOCK_PLAYER_NAMES = "deadlockPlayerNames";    // 1 h, max 500
    public static final String DEADLOCK_MATCH_METADATA = "deadlockMatchMetadata"; // 24 h, max 300
    public static final String PROFILE_SUMMARY = "profileSummary";               // 10 min, max 1000
    public static final String DEADLOCK_HERO_STATS = "deadlockHeroStats";        // 6 h, max 50
    public static final String DEADLOCK_ITEM_FLOW = "deadlockItemFlow";           // 6 h, max 200
    public static final String DEADLOCK_ABILITY_ORDER = "deadlockAbilityOrder";   // 6 h, max 200
    public static final String DEADLOCK_HERO_COUNTERS = "deadlockHeroCounters";   // 6 h, max 50
    public static final String DEADLOCK_COUNTER_ITEMS = "deadlockCounterItems";   // 6 h, max 200
    public static final String DEADLOCK_PLAYER_HERO_STATS = "deadlockPlayerHeroStats"; // 10 min, max 200
    public static final String DEADLOCK_MATE_STATS = "deadlockMateStats";               // 10 min, max 200
    public static final String DEADLOCK_PLAYER_ITEM_STATS = "deadlockPlayerItemStats"; // 10 min, max 200
    public static final String DEADLOCK_PERFORMANCE_CURVE = "deadlockPerformanceCurve"; // 6 h, max 500

    @Bean
    CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        // An explicit (empty) name list switches the manager to static mode: unknown names return null.
        cacheManager.setCacheNames(List.of());
        register(cacheManager, DEADLOCK_ASSETS, Duration.ofHours(24), 10);
        register(cacheManager, DEADLOCK_BENCHMARKS, Duration.ofHours(6), 500);
        register(cacheManager, DEADLOCK_ITEM_STATS, Duration.ofHours(6), 500);
        register(cacheManager, DEADLOCK_MATCH_HISTORY, Duration.ofMinutes(2), 1000);
        register(cacheManager, DEADLOCK_PLAYER_RANK, Duration.ofMinutes(10), 1000);
        register(cacheManager, DEADLOCK_PLAYER_RANKS, Duration.ofMinutes(10), 100);
        register(cacheManager, DEADLOCK_PLAYER_NAMES, Duration.ofHours(1), 500);
        register(cacheManager, DEADLOCK_MATCH_METADATA, Duration.ofHours(24), 300);
        register(cacheManager, PROFILE_SUMMARY, Duration.ofMinutes(10), 1000);
        register(cacheManager, DEADLOCK_HERO_STATS, Duration.ofHours(6), 50);
        register(cacheManager, DEADLOCK_ITEM_FLOW, Duration.ofHours(6), 200);
        register(cacheManager, DEADLOCK_ABILITY_ORDER, Duration.ofHours(6), 200);
        register(cacheManager, DEADLOCK_HERO_COUNTERS, Duration.ofHours(6), 50);
        register(cacheManager, DEADLOCK_COUNTER_ITEMS, Duration.ofHours(6), 200);
        register(cacheManager, DEADLOCK_PLAYER_HERO_STATS, Duration.ofMinutes(10), 200);
        register(cacheManager, DEADLOCK_MATE_STATS, Duration.ofMinutes(10), 200);
        register(cacheManager, DEADLOCK_PLAYER_ITEM_STATS, Duration.ofMinutes(10), 200);
        register(cacheManager, DEADLOCK_PERFORMANCE_CURVE, Duration.ofHours(6), 500);
        return cacheManager;
    }

    private static void register(CaffeineCacheManager cacheManager, String name, Duration ttl, long maximumSize) {
        cacheManager.registerCustomCache(name, Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(maximumSize)
                .build());
    }
}
