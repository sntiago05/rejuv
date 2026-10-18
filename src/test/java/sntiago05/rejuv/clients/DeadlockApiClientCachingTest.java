package sntiago05.rejuv.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static sntiago05.rejuv.clients.DeadlockApiClientTest.BASE_URL;
import static sntiago05.rejuv.clients.DeadlockApiClientTest.fixture;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.github.benmanes.caffeine.cache.Policy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import sntiago05.rejuv.config.CacheConfig;
import sntiago05.rejuv.config.DeadlockApiConfig;
import sntiago05.rejuv.config.DeadlockApiProperties;

@SpringJUnitConfig(DeadlockApiClientCachingTest.TestConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class DeadlockApiClientCachingTest {

    @Configuration
    @Import({CacheConfig.class, DeadlockApiClient.class})
    static class TestConfig {

        private final RestClient.Builder builder = DeadlockApiConfig.restClientBuilder(DeadlockApiClientTest.PROPERTIES);

        @Bean
        MockRestServiceServer mockRestServiceServer() {
            return MockRestServiceServer.bindTo(builder).build();
        }

        @Bean
        RestClient deadlockApiRestClient(MockRestServiceServer mockRestServiceServer) {
            return builder.build();
        }

        @Bean
        DeadlockApiProperties deadlockApiProperties() {
            return DeadlockApiClientTest.PROPERTIES;
        }

        @Bean
        Sleeper sleeper() {
            return duration -> { };
        }
    }

    @Autowired
    private DeadlockApiClient client;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private MockRestServiceServer server;

    @BeforeEach
    void resetServer() {
        server.reset();
    }

    @AfterEach
    void verifyServer() {
        server.verify();
    }

    @Test
    void secondCallWithSameArgumentsHitsCache() {
        server.expect(once(), requestTo(BASE_URL + "/v1/players/1/match-history"))
                .andRespond(withSuccess(fixture("match-history.json"), MediaType.APPLICATION_JSON));

        assertThat(client.getMatchHistory(1)).hasSize(3);
        assertThat(client.getMatchHistory(1)).hasSize(3);
    }

    @Test
    void differentArgumentsAreCachedSeparately() {
        server.expect(once(), requestTo(BASE_URL + "/v1/players/1/match-history"))
                .andRespond(withSuccess(fixture("match-history.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE_URL + "/v1/players/2/match-history"))
                .andRespond(withSuccess(fixture("match-history-empty.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE_URL + "/v1/players/1/rank"))
                .andRespond(withSuccess(fixture("player-rank.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE_URL + "/v1/players/2/rank"))
                .andRespond(withSuccess(fixture("player-rank-unranked.json"), MediaType.APPLICATION_JSON));
        expectAnalytics("/v1/analytics/player-stats/metrics", "5", "70", "player-stats-metrics.json");
        expectAnalytics("/v1/analytics/player-stats/metrics", "5", "80", "player-stats-metrics.json");
        expectAnalytics("/v1/analytics/player-stats/metrics", "6", "70", "player-stats-metrics.json");
        expectAnalytics("/v1/analytics/item-stats", "5", "70", "item-stats.json");
        expectAnalytics("/v1/analytics/item-stats", "5", "80", "item-stats.json");
        expectAnalytics("/v1/analytics/item-stats", "6", "70", "item-stats.json");

        for (int round = 0; round < 2; round++) {
            assertThat(client.getMatchHistory(1)).hasSize(3);
            assertThat(client.getMatchHistory(2)).isEmpty();
            assertThat(client.getPlayerRank(1).badge()).isEqualTo(74);
            assertThat(client.getPlayerRank(2).badge()).isZero();
            client.getBenchmarks(5, 7);
            client.getBenchmarks(5, 8);
            client.getBenchmarks(6, 7);
            client.getItemStats(5, 7);
            client.getItemStats(5, 8);
            client.getItemStats(6, 7);
        }
    }

    @Test
    void assetsDoNotCollide() {
        server.expect(once(), requestTo(BASE_URL + "/v1/assets/ranks"))
                .andRespond(withSuccess(fixture("assets-ranks.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE_URL + "/v1/assets/heroes"))
                .andRespond(withSuccess(fixture("assets-heroes.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE_URL + "/v1/assets/items"))
                .andRespond(withSuccess(fixture("assets-items.json"), MediaType.APPLICATION_JSON));

        for (int round = 0; round < 2; round++) {
            assertThat(client.getRanks().get(0).name()).isEqualTo("Obscurus");
            assertThat(client.getHeroes().get(0).className()).isEqualTo("hero_inferno");
            assertThat(client.getItems().get(0).className()).isEqualTo("upgrade_clip_size");
        }
    }

    @Test
    void failuresAreNotCached() {
        server.expect(once(), requestTo(BASE_URL + "/v1/assets/ranks")).andRespond(withServerError());
        server.expect(once(), requestTo(BASE_URL + "/v1/assets/ranks"))
                .andRespond(withSuccess(fixture("assets-ranks.json"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.getRanks()).isInstanceOf(DeadlockApiException.class);
        assertThat(client.getRanks()).hasSize(3);
    }

    @Test
    void heroStatsAreCachedPerTierAndTimestamp() {
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/analytics/hero-stats?")))
                .andExpect(queryParam("min_average_badge", "70"))
                .andExpect(queryParam("max_average_badge", "79"))
                .andExpect(queryParam("min_unix_timestamp", "1000"))
                .andRespond(withSuccess(fixture("hero-stats.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/analytics/hero-stats?")))
                .andExpect(queryParam("min_average_badge", "80"))
                .andExpect(queryParam("max_average_badge", "89"))
                .andExpect(queryParam("min_unix_timestamp", "1000"))
                .andRespond(withSuccess(fixture("hero-stats.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/analytics/hero-stats?")))
                .andExpect(queryParam("min_average_badge", "70"))
                .andExpect(queryParam("max_average_badge", "79"))
                .andExpect(queryParam("min_unix_timestamp", "2000"))
                .andRespond(withSuccess(fixture("hero-stats.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/analytics/hero-stats?")))
                .andExpect(DeadlockApiClientTest.noQueryParam("min_average_badge"))
                .andExpect(queryParam("min_unix_timestamp", "1000"))
                .andRespond(withSuccess(fixture("hero-stats.json"), MediaType.APPLICATION_JSON));

        for (int round = 0; round < 2; round++) {
            assertThat(client.getHeroStats(7, 1000L)).hasSize(5);
            assertThat(client.getHeroStats(8, 1000L)).hasSize(5);
            assertThat(client.getHeroStats(7, 2000L)).hasSize(5);
            assertThat(client.getHeroStats(null, 1000L)).hasSize(5);
        }
    }

    @Test
    void itemFlowIsCachedPerHeroTierAndTimestamp() {
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/analytics/item-flow-stats?")))
                .andExpect(queryParam("hero_ids", "61"))
                .andExpect(queryParam("min_average_badge", "70"))
                .andExpect(queryParam("max_average_badge", "79"))
                .andExpect(queryParam("min_unix_timestamp", "1000"))
                .andRespond(withSuccess(fixture("item-flow-stats.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/analytics/item-flow-stats?")))
                .andExpect(queryParam("hero_ids", "62"))
                .andExpect(queryParam("min_average_badge", "70"))
                .andExpect(queryParam("max_average_badge", "79"))
                .andExpect(queryParam("min_unix_timestamp", "1000"))
                .andRespond(withSuccess(fixture("item-flow-stats.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/analytics/item-flow-stats?")))
                .andExpect(queryParam("hero_ids", "61"))
                .andExpect(queryParam("min_average_badge", "80"))
                .andExpect(queryParam("max_average_badge", "89"))
                .andExpect(queryParam("min_unix_timestamp", "1000"))
                .andRespond(withSuccess(fixture("item-flow-stats.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/analytics/item-flow-stats?")))
                .andExpect(queryParam("hero_ids", "61"))
                .andExpect(queryParam("min_average_badge", "70"))
                .andExpect(queryParam("max_average_badge", "79"))
                .andExpect(queryParam("min_unix_timestamp", "2000"))
                .andRespond(withSuccess(fixture("item-flow-stats.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/analytics/item-flow-stats?")))
                .andExpect(queryParam("hero_ids", "61"))
                .andExpect(DeadlockApiClientTest.noQueryParam("min_average_badge"))
                .andExpect(queryParam("min_unix_timestamp", "1000"))
                .andRespond(withSuccess(fixture("item-flow-stats.json"), MediaType.APPLICATION_JSON));

        for (int round = 0; round < 2; round++) {
            assertThat(client.getItemFlowStats(61, 7, 1000L).nodes()).hasSize(6);
            assertThat(client.getItemFlowStats(62, 7, 1000L).nodes()).hasSize(6);
            assertThat(client.getItemFlowStats(61, 8, 1000L).nodes()).hasSize(6);
            assertThat(client.getItemFlowStats(61, 7, 2000L).nodes()).hasSize(6);
            assertThat(client.getItemFlowStats(61, null, 1000L).nodes()).hasSize(6);
        }
    }

    @Test
    void abilityOrderIsCachedPerHeroTierAndTimestamp() {
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/analytics/ability-order-stats?")))
                .andExpect(queryParam("hero_id", "61"))
                .andExpect(queryParam("min_average_badge", "70"))
                .andExpect(queryParam("max_average_badge", "79"))
                .andExpect(queryParam("min_unix_timestamp", "1000"))
                .andRespond(withSuccess(fixture("ability-order-stats.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/analytics/ability-order-stats?")))
                .andExpect(queryParam("hero_id", "62"))
                .andExpect(queryParam("min_average_badge", "70"))
                .andExpect(queryParam("max_average_badge", "79"))
                .andExpect(queryParam("min_unix_timestamp", "1000"))
                .andRespond(withSuccess(fixture("ability-order-stats.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/analytics/ability-order-stats?")))
                .andExpect(queryParam("hero_id", "61"))
                .andExpect(queryParam("min_average_badge", "80"))
                .andExpect(queryParam("max_average_badge", "89"))
                .andExpect(queryParam("min_unix_timestamp", "1000"))
                .andRespond(withSuccess(fixture("ability-order-stats.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/analytics/ability-order-stats?")))
                .andExpect(queryParam("hero_id", "61"))
                .andExpect(queryParam("min_average_badge", "70"))
                .andExpect(queryParam("max_average_badge", "79"))
                .andExpect(queryParam("min_unix_timestamp", "2000"))
                .andRespond(withSuccess(fixture("ability-order-stats.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/analytics/ability-order-stats?")))
                .andExpect(queryParam("hero_id", "61"))
                .andExpect(DeadlockApiClientTest.noQueryParam("min_average_badge"))
                .andExpect(queryParam("min_unix_timestamp", "1000"))
                .andRespond(withSuccess(fixture("ability-order-stats.json"), MediaType.APPLICATION_JSON));

        for (int round = 0; round < 2; round++) {
            assertThat(client.getAbilityOrderStats(61, 7, 1000L)).hasSize(3);
            assertThat(client.getAbilityOrderStats(62, 7, 1000L)).hasSize(3);
            assertThat(client.getAbilityOrderStats(61, 8, 1000L)).hasSize(3);
            assertThat(client.getAbilityOrderStats(61, 7, 2000L)).hasSize(3);
            assertThat(client.getAbilityOrderStats(61, null, 1000L)).hasSize(3);
        }
    }

    @Test
    void heroCountersAreCachedPerTierAndTimestamp() {
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/analytics/hero-counter-stats?")))
                .andExpect(queryParam("min_average_badge", "70"))
                .andExpect(queryParam("max_average_badge", "79"))
                .andExpect(queryParam("min_unix_timestamp", "1000"))
                .andRespond(withSuccess(fixture("hero-counter-stats.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/analytics/hero-counter-stats?")))
                .andExpect(queryParam("min_average_badge", "80"))
                .andExpect(queryParam("max_average_badge", "89"))
                .andExpect(queryParam("min_unix_timestamp", "1000"))
                .andRespond(withSuccess(fixture("hero-counter-stats.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/analytics/hero-counter-stats?")))
                .andExpect(queryParam("min_average_badge", "70"))
                .andExpect(queryParam("max_average_badge", "79"))
                .andExpect(queryParam("min_unix_timestamp", "2000"))
                .andRespond(withSuccess(fixture("hero-counter-stats.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/analytics/hero-counter-stats?")))
                .andExpect(DeadlockApiClientTest.noQueryParam("min_average_badge"))
                .andExpect(queryParam("min_unix_timestamp", "1000"))
                .andRespond(withSuccess(fixture("hero-counter-stats.json"), MediaType.APPLICATION_JSON));

        for (int round = 0; round < 2; round++) {
            assertThat(client.getHeroCounterStats(7, 1000L)).hasSize(9);
            assertThat(client.getHeroCounterStats(8, 1000L)).hasSize(9);
            assertThat(client.getHeroCounterStats(7, 2000L)).hasSize(9);
            assertThat(client.getHeroCounterStats(null, 1000L)).hasSize(9);
        }
    }

    @Test
    void itemStatsAgainstAreCachedPerHeroTierAndTimestamp() {
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/analytics/item-stats?")))
                .andExpect(queryParam("enemy_hero_ids", "61"))
                .andExpect(queryParam("min_average_badge", "70"))
                .andExpect(queryParam("max_average_badge", "79"))
                .andExpect(queryParam("min_unix_timestamp", "1000"))
                .andRespond(withSuccess(fixture("item-stats.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/analytics/item-stats?")))
                .andExpect(queryParam("enemy_hero_ids", "62"))
                .andExpect(queryParam("min_average_badge", "70"))
                .andExpect(queryParam("max_average_badge", "79"))
                .andExpect(queryParam("min_unix_timestamp", "1000"))
                .andRespond(withSuccess(fixture("item-stats.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/analytics/item-stats?")))
                .andExpect(queryParam("enemy_hero_ids", "61"))
                .andExpect(queryParam("min_average_badge", "80"))
                .andExpect(queryParam("max_average_badge", "89"))
                .andExpect(queryParam("min_unix_timestamp", "1000"))
                .andRespond(withSuccess(fixture("item-stats.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/analytics/item-stats?")))
                .andExpect(queryParam("enemy_hero_ids", "61"))
                .andExpect(queryParam("min_average_badge", "70"))
                .andExpect(queryParam("max_average_badge", "79"))
                .andExpect(queryParam("min_unix_timestamp", "2000"))
                .andRespond(withSuccess(fixture("item-stats.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/analytics/item-stats?")))
                .andExpect(queryParam("enemy_hero_ids", "61"))
                .andExpect(DeadlockApiClientTest.noQueryParam("min_average_badge"))
                .andExpect(queryParam("min_unix_timestamp", "1000"))
                .andRespond(withSuccess(fixture("item-stats.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/analytics/item-stats?hero_ids=61&min_average_badge=70")))
                .andExpect(queryParam("hero_ids", "61"))
                .andExpect(queryParam("min_average_badge", "70"))
                .andExpect(queryParam("max_average_badge", "79"))
                .andExpect(DeadlockApiClientTest.noQueryParam("min_unix_timestamp"))
                .andRespond(withSuccess(fixture("item-stats.json"), MediaType.APPLICATION_JSON));

        for (int round = 0; round < 2; round++) {
            assertThat(client.getItemStatsAgainst(61, 7, 1000L)).isNotEmpty();
            assertThat(client.getItemStatsAgainst(62, 7, 1000L)).isNotEmpty();
            assertThat(client.getItemStatsAgainst(61, 8, 1000L)).isNotEmpty();
            assertThat(client.getItemStatsAgainst(61, 7, 2000L)).isNotEmpty();
            assertThat(client.getItemStatsAgainst(61, null, 1000L)).isNotEmpty();
            assertThat(client.getItemStats(61, 7)).isNotEmpty();
        }
    }

    @Test
    void playerNamesAreCachedAndFailuresAreNotCached() {
        server.expect(once(), requestTo(BASE_URL + "/v1/players/steam?account_ids=1,2"))
                .andRespond(withSuccess(fixture("players-steam.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE_URL + "/v1/players/steam?account_ids=4"))
                .andRespond(withServerError());
        server.expect(once(), requestTo(BASE_URL + "/v1/players/steam?account_ids=4"))
                .andRespond(withSuccess(fixture("players-steam.json"), MediaType.APPLICATION_JSON));

        assertThat(client.getPlayerNames(List.of(1L, 2L))).hasSize(2);
        assertThat(client.getPlayerNames(List.of(1L, 2L))).hasSize(2);

        assertThatThrownBy(() -> client.getPlayerNames(List.of(4L))).isInstanceOf(DeadlockApiException.class);
        assertThat(client.getPlayerNames(List.of(4L))).hasSize(2);
    }

    @Test
    void playerRanksAreCachedBySortedIdListAndFailuresAreNotCached() {
        server.expect(once(), requestTo(BASE_URL + "/v1/players/rank?account_ids=1,2"))
                .andRespond(withSuccess(fixture("player-ranks.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE_URL + "/v1/players/rank?account_ids=1,3"))
                .andRespond(withSuccess(fixture("player-ranks.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE_URL + "/v1/players/rank?account_ids=4"))
                .andRespond(withServerError());
        server.expect(once(), requestTo(BASE_URL + "/v1/players/rank?account_ids=4"))
                .andRespond(withSuccess(fixture("player-ranks.json"), MediaType.APPLICATION_JSON));

        for (int round = 0; round < 2; round++) {
            assertThat(client.getPlayerRanks(List.of(1L, 2L))).hasSize(4);
            assertThat(client.getPlayerRanks(List.of(1L, 3L))).hasSize(4);
        }

        assertThatThrownBy(() -> client.getPlayerRanks(List.of(4L))).isInstanceOf(DeadlockApiException.class);
        assertThat(client.getPlayerRanks(List.of(4L))).hasSize(4);
    }

    @Test
    void playerHeroStatsAreCachedPerAccountAndTimestamp() {
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/players/hero-stats?")))
                .andExpect(queryParam("account_ids", "1"))
                .andExpect(queryParam("min_unix_timestamp", "1000"))
                .andRespond(withSuccess(fixture("player-hero-stats.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/players/hero-stats?")))
                .andExpect(queryParam("account_ids", "2"))
                .andExpect(queryParam("min_unix_timestamp", "1000"))
                .andRespond(withSuccess(fixture("player-hero-stats.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/players/hero-stats?")))
                .andExpect(queryParam("account_ids", "1"))
                .andExpect(queryParam("min_unix_timestamp", "2000"))
                .andRespond(withSuccess(fixture("player-hero-stats.json"), MediaType.APPLICATION_JSON));

        for (int round = 0; round < 2; round++) {
            assertThat(client.getPlayerHeroStats(1, 1000L)).hasSize(4);
            assertThat(client.getPlayerHeroStats(2, 1000L)).hasSize(4);
            assertThat(client.getPlayerHeroStats(1, 2000L)).hasSize(4);
        }
    }

    @Test
    void playerHeroStatsFailureIsNotCached() {
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/players/hero-stats?")))
                .andRespond(withServerError());
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/players/hero-stats?")))
                .andRespond(withSuccess(fixture("player-hero-stats.json"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.getPlayerHeroStats(1, 1000L))
                .isInstanceOf(DeadlockApiException.class);

        assertThat(client.getPlayerHeroStats(1, 1000L)).hasSize(4);
    }

    @Test
    void mateStatsAreCachedPerAccountAndTimestamp() {
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/players/1/mate-stats?")))
                .andExpect(queryParam("min_unix_timestamp", "1000"))
                .andRespond(withSuccess(fixture("mate-stats.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/players/2/mate-stats?")))
                .andExpect(queryParam("min_unix_timestamp", "1000"))
                .andRespond(withSuccess(fixture("mate-stats.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/players/1/mate-stats?")))
                .andExpect(queryParam("min_unix_timestamp", "2000"))
                .andRespond(withSuccess(fixture("mate-stats.json"), MediaType.APPLICATION_JSON));

        for (int i = 0; i < 2; i++) {
            assertThat(client.getMateStats(1, 1000L)).hasSize(3);
            assertThat(client.getMateStats(2, 1000L)).hasSize(3);
            assertThat(client.getMateStats(1, 2000L)).hasSize(3);
        }
    }

    @Test
    void mateStatsFailureIsNotCached() {
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/players/1/mate-stats?")))
                .andRespond(withServerError());
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/players/1/mate-stats?")))
                .andRespond(withSuccess(fixture("mate-stats.json"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.getMateStats(1, 1000L))
                .isInstanceOf(DeadlockApiException.class);

        assertThat(client.getMateStats(1, 1000L)).hasSize(3);
    }

    @Test
    void cacheTtlsAndSizesMatchTheDesign() {
        Map<String, Duration> ttls = Map.ofEntries(
                Map.entry(CacheConfig.DEADLOCK_ASSETS, Duration.ofHours(24)),
                Map.entry(CacheConfig.DEADLOCK_BENCHMARKS, Duration.ofHours(6)),
                Map.entry(CacheConfig.DEADLOCK_ITEM_STATS, Duration.ofHours(6)),
                Map.entry(CacheConfig.DEADLOCK_MATCH_HISTORY, Duration.ofMinutes(2)),
                Map.entry(CacheConfig.DEADLOCK_PLAYER_RANK, Duration.ofMinutes(10)),
                Map.entry(CacheConfig.DEADLOCK_PLAYER_RANKS, Duration.ofMinutes(10)),
                Map.entry(CacheConfig.DEADLOCK_PLAYER_NAMES, Duration.ofHours(1)),
                Map.entry(CacheConfig.DEADLOCK_MATCH_METADATA, Duration.ofHours(24)),
                Map.entry(CacheConfig.PROFILE_SUMMARY, Duration.ofMinutes(10)),
                Map.entry(CacheConfig.DEADLOCK_HERO_STATS, Duration.ofHours(6)),
                Map.entry(CacheConfig.DEADLOCK_ITEM_FLOW, Duration.ofHours(6)),
                Map.entry(CacheConfig.DEADLOCK_ABILITY_ORDER, Duration.ofHours(6)),
                Map.entry(CacheConfig.DEADLOCK_HERO_COUNTERS, Duration.ofHours(6)),
                Map.entry(CacheConfig.DEADLOCK_COUNTER_ITEMS, Duration.ofHours(6)),
                Map.entry(CacheConfig.DEADLOCK_PLAYER_HERO_STATS, Duration.ofMinutes(10)),
                Map.entry(CacheConfig.DEADLOCK_MATE_STATS, Duration.ofMinutes(10)));
        Map<String, Long> sizes = Map.ofEntries(
                Map.entry(CacheConfig.DEADLOCK_ASSETS, 10L),
                Map.entry(CacheConfig.DEADLOCK_BENCHMARKS, 500L),
                Map.entry(CacheConfig.DEADLOCK_ITEM_STATS, 500L),
                Map.entry(CacheConfig.DEADLOCK_MATCH_HISTORY, 1000L),
                Map.entry(CacheConfig.DEADLOCK_PLAYER_RANK, 1000L),
                Map.entry(CacheConfig.DEADLOCK_PLAYER_RANKS, 100L),
                Map.entry(CacheConfig.DEADLOCK_PLAYER_NAMES, 500L),
                Map.entry(CacheConfig.DEADLOCK_MATCH_METADATA, 300L),
                Map.entry(CacheConfig.PROFILE_SUMMARY, 1000L),
                Map.entry(CacheConfig.DEADLOCK_HERO_STATS, 50L),
                Map.entry(CacheConfig.DEADLOCK_ITEM_FLOW, 200L),
                Map.entry(CacheConfig.DEADLOCK_ABILITY_ORDER, 200L),
                Map.entry(CacheConfig.DEADLOCK_HERO_COUNTERS, 50L),
                Map.entry(CacheConfig.DEADLOCK_COUNTER_ITEMS, 200L),
                Map.entry(CacheConfig.DEADLOCK_PLAYER_HERO_STATS, 200L),
                Map.entry(CacheConfig.DEADLOCK_MATE_STATS, 200L));

        assertThat(cacheManager.getCacheNames()).containsExactlyInAnyOrderElementsOf(ttls.keySet());
        for (String name : ttls.keySet()) {
            Policy<Object, Object> policy = ((CaffeineCache) cacheManager.getCache(name)).getNativeCache().policy();
            assertThat(policy.expireAfterWrite()).as(name).hasValueSatisfying(
                    expiration -> assertThat(expiration.getExpiresAfter()).isEqualTo(ttls.get(name)));
            assertThat(policy.eviction()).as(name).hasValueSatisfying(
                    eviction -> assertThat(eviction.getMaximum()).isEqualTo(sizes.get(name)));
        }
    }

    @Test
    void unknownCacheNamesAreNotCreatedDynamically() {
        assertThat(cacheManager.getCache("anything")).isNull();
    }

    private void expectAnalytics(String path, String heroId, String minBadge, String fixtureName) {
        server.expect(once(), requestTo(startsWith(BASE_URL + path + "?hero_ids=" + heroId + "&min_average_badge=" + minBadge)))
                .andExpect(queryParam("hero_ids", heroId))
                .andExpect(queryParam("min_average_badge", minBadge))
                .andRespond(withSuccess(fixture(fixtureName), MediaType.APPLICATION_JSON));
    }
}
