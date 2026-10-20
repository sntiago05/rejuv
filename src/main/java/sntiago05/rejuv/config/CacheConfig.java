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
@EnableCaching(proxyTargetClass = true)
public class CacheConfig {

    public static final String DEADLOCK_ASSETS = "deadlockAssets";               // 2 h, max 10
    public static final String DEADLOCK_BENCHMARKS = "deadlockBenchmarks";       // 3 h, max 500
    public static final String DEADLOCK_ITEM_STATS = "deadlockItemStats";        // 3 h, max 500
    public static final String DEADLOCK_MATCH_HISTORY = "deadlockMatchHistory";  // 45 s, max 1000
    public static final String DEADLOCK_PLAYER_RANK = "deadlockPlayerRank";      // 5 min, max 1000
    public static final String DEADLOCK_PLAYER_RANKS = "deadlockPlayerRanks";    // 5 min, max 200
    public static final String DEADLOCK_PLAYER_NAMES = "deadlockPlayerNames";    // 1 h, max 1000
    public static final String DEADLOCK_MATCH_METADATA = "deadlockMatchMetadata"; // 24 h, max 1000
    public static final String DEADLOCK_MATCH_METADATA_RAW = "deadlockMatchMetadataRaw"; // 2 min, max 100
    public static final String PROFILE_SUMMARY = "profileSummary";               // 5 min, max 1000
    public static final String DEADLOCK_HERO_STATS = "deadlockHeroStats";        // 3 h, max 50
    public static final String DEADLOCK_ITEM_FLOW = "deadlockItemFlow";           // 3 h, max 200
    public static final String DEADLOCK_ABILITY_ORDER = "deadlockAbilityOrder";   // 3 h, max 200
    public static final String DEADLOCK_HERO_COUNTERS = "deadlockHeroCounters";   // 3 h, max 50
    public static final String DEADLOCK_HERO_SYNERGIES = "deadlockHeroSynergies"; // 3 h, max 50
    public static final String DEADLOCK_HERO_DETAIL = "deadlockHeroDetail";       // 2 h, max 100
    public static final String DEADLOCK_COUNTER_ITEMS = "deadlockCounterItems";   // 3 h, max 200
    public static final String DEADLOCK_PLAYER_HERO_STATS = "deadlockPlayerHeroStats"; // 5 min, max 500
    public static final String DEADLOCK_MATE_STATS = "deadlockMateStats";               // 5 min, max 500
    public static final String DEADLOCK_PLAYER_ITEM_STATS = "deadlockPlayerItemStats"; // 5 min, max 500
    public static final String DEADLOCK_PERFORMANCE_CURVE = "deadlockPerformanceCurve"; // 3 h, max 500

    @Bean
    CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        // An explicit (empty) name list switches the manager to static mode: unknown names return null.
        cacheManager.setCacheNames(List.of());
        register(cacheManager, DEADLOCK_ASSETS, Duration.ofHours(2), 10);
        register(cacheManager, DEADLOCK_BENCHMARKS, Duration.ofHours(3), 500);
        register(cacheManager, DEADLOCK_ITEM_STATS, Duration.ofHours(3), 500);
        register(cacheManager, DEADLOCK_MATCH_HISTORY, Duration.ofSeconds(45), 1000);
        register(cacheManager, DEADLOCK_PLAYER_RANK, Duration.ofMinutes(5), 1000);
        register(cacheManager, DEADLOCK_PLAYER_RANKS, Duration.ofMinutes(5), 200);
        register(cacheManager, DEADLOCK_PLAYER_NAMES, Duration.ofHours(1), 1000);
        register(cacheManager, DEADLOCK_MATCH_METADATA, Duration.ofHours(24), 1000);
        register(cacheManager, DEADLOCK_MATCH_METADATA_RAW, Duration.ofMinutes(2), 100);
        register(cacheManager, PROFILE_SUMMARY, Duration.ofMinutes(5), 1000);
        register(cacheManager, DEADLOCK_HERO_STATS, Duration.ofHours(3), 50);
        register(cacheManager, DEADLOCK_ITEM_FLOW, Duration.ofHours(3), 200);
        register(cacheManager, DEADLOCK_ABILITY_ORDER, Duration.ofHours(3), 200);
        register(cacheManager, DEADLOCK_HERO_COUNTERS, Duration.ofHours(3), 50);
        register(cacheManager, DEADLOCK_HERO_SYNERGIES, Duration.ofHours(3), 50);
        register(cacheManager, DEADLOCK_HERO_DETAIL, Duration.ofHours(2), 100);
        register(cacheManager, DEADLOCK_COUNTER_ITEMS, Duration.ofHours(3), 200);
        register(cacheManager, DEADLOCK_PLAYER_HERO_STATS, Duration.ofMinutes(5), 500);
        register(cacheManager, DEADLOCK_MATE_STATS, Duration.ofMinutes(5), 500);
        register(cacheManager, DEADLOCK_PLAYER_ITEM_STATS, Duration.ofMinutes(5), 500);
        register(cacheManager, DEADLOCK_PERFORMANCE_CURVE, Duration.ofHours(3), 500);
        return cacheManager;
    }

    private static void register(CaffeineCacheManager cacheManager, String name, Duration ttl, long maximumSize) {
        cacheManager.registerCustomCache(name, Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(maximumSize)
                .recordStats()
                .build());
    }
}
