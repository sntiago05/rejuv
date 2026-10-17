package sntiago05.rejuv.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.twice;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;
import sntiago05.rejuv.clients.dto.AbilityOrderStat;
import sntiago05.rejuv.clients.dto.HeroAsset;
import sntiago05.rejuv.clients.dto.HeroCounterStat;
import sntiago05.rejuv.clients.dto.HeroImages;
import sntiago05.rejuv.clients.dto.HeroStat;
import sntiago05.rejuv.clients.dto.ItemAsset;
import sntiago05.rejuv.clients.dto.ItemFlowNode;
import sntiago05.rejuv.clients.dto.ItemFlowStats;
import sntiago05.rejuv.clients.dto.ItemStat;
import sntiago05.rejuv.clients.dto.MatchHistoryEntry;
import sntiago05.rejuv.clients.dto.MetricPercentiles;
import sntiago05.rejuv.clients.dto.PlayerRank;
import sntiago05.rejuv.clients.dto.RankAsset;
import sntiago05.rejuv.config.DeadlockApiConfig;
import sntiago05.rejuv.config.DeadlockApiProperties;

class DeadlockApiClientTest {

    static final String BASE_URL = "https://deadlock-api.test";
    static final long ACCOUNT_ID = 1000001L;
    static final String HISTORY_URL = BASE_URL + "/v1/players/" + ACCOUNT_ID + "/match-history";
    static final String RANK_URL = BASE_URL + "/v1/players/" + ACCOUNT_ID + "/rank";
    static final String RANKS_URL = BASE_URL + "/v1/assets/ranks";

    static final DeadlockApiProperties PROPERTIES = new DeadlockApiProperties(BASE_URL, Duration.ofSeconds(3),
            Duration.ofSeconds(10), Duration.ofSeconds(2), Duration.ofSeconds(10));

    private final List<Duration> sleeps = new ArrayList<>();
    private MockRestServiceServer server;
    private DeadlockApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = DeadlockApiConfig.restClientBuilder(PROPERTIES);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new DeadlockApiClient(builder.build(), PROPERTIES, sleeps::add);
    }

    @AfterEach
    void verifyServer() {
        server.verify();
    }

    static ClassPathResource fixture(String name) {
        return new ClassPathResource("fixtures/deadlock-api/" + name);
    }

    static RequestMatcher noQueryParam(String name) {
        return request -> {
            String query = request.getURI().getQuery();
            assertThat(query == null ? "" : query).doesNotContain(name);
        };
    }

    @Test
    void historyParsesEntriesAndIgnoresUnknownFields() {
        server.expect(once(), requestTo(HISTORY_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(noQueryParam("force_refetch"))
                .andRespond(withSuccess(fixture("match-history.json"), MediaType.APPLICATION_JSON));

        List<MatchHistoryEntry> history = client.getMatchHistory(ACCOUNT_ID);

        assertThat(history).hasSize(3);
        assertThat(history.get(0)).isEqualTo(new MatchHistoryEntry(900000003L, 1, 32, 1789000000L, 2104,
                4, 1, 1, 1, 9, 4, 12, 41250L, 233, 21, 74));
        assertThat(history.get(1).rankedDisplayBadge()).isNull();
        assertThat(history.get(1).matchMode()).isEqualTo(1);
        assertThat(history.get(2).matchId()).isEqualTo(900000001L);
        assertThat(history.get(2).playerMatchOutcome()).isZero();
    }

    @Test
    void historyEmptyArrayReturnsEmptyList() {
        server.expect(once(), requestTo(HISTORY_URL))
                .andRespond(withSuccess(fixture("match-history-empty.json"), MediaType.APPLICATION_JSON));

        assertThat(client.getMatchHistory(ACCOUNT_ID)).isEmpty();
    }

    @Test
    void historyIsCappedToMostRecentEntries() {
        String body = IntStream.range(0, 250)
                .mapToObj(i -> historyEntryJson(900000001L + i))
                .collect(Collectors.joining(",", "[", "]"));
        server.expect(once(), requestTo(HISTORY_URL))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<MatchHistoryEntry> history = client.getMatchHistory(ACCOUNT_ID);

        assertThat(history).hasSize(DeadlockApiClient.MAX_HISTORY_ENTRIES);
        assertThat(history.get(0).matchId()).isEqualTo(900000001L);
        assertThat(history.get(DeadlockApiClient.MAX_HISTORY_ENTRIES - 1).matchId())
                .isEqualTo(900000001L + DeadlockApiClient.MAX_HISTORY_ENTRIES - 1);
    }

    @Test
    void historyStopsReadingAfterTheCap() {
        // Everything after the 200th entry is invalid JSON: it must never be parsed.
        String body = IntStream.range(0, DeadlockApiClient.MAX_HISTORY_ENTRIES)
                .mapToObj(i -> historyEntryJson(900000001L + i))
                .collect(Collectors.joining(",", "[", ",{\"match_id\":"));
        server.expect(once(), requestTo(HISTORY_URL))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThat(client.getMatchHistory(ACCOUNT_ID)).hasSize(DeadlockApiClient.MAX_HISTORY_ENTRIES);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{}", "[{\"match_id\":", "[1]"})
    void historyWithEmptyOrInvalidBodyThrowsApiException(String body) {
        server.expect(once(), requestTo(HISTORY_URL))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.getMatchHistory(ACCOUNT_ID))
                .isInstanceOf(DeadlockApiException.class)
                .isNotInstanceOf(DeadlockApiUnavailableException.class);
    }

    @Test
    void history429WithArrayBodyIsTreatedAsSuccess() {
        server.expect(once(), requestTo(HISTORY_URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(fixture("match-history.json")));

        List<MatchHistoryEntry> history = client.getMatchHistory(ACCOUNT_ID);

        assertThat(history).extracting(MatchHistoryEntry::matchId)
                .containsExactly(900000003L, 900000002L, 900000001L);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void history429WithoutArrayBodyThrowsUnavailable() {
        server.expect(once(), requestTo(HISTORY_URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON).body("{}"));
        server.expect(once(), requestTo(HISTORY_URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.getMatchHistory(ACCOUNT_ID))
                .isInstanceOf(DeadlockApiUnavailableException.class);
        assertThatThrownBy(() -> client.getMatchHistory(ACCOUNT_ID))
                .isInstanceOf(DeadlockApiUnavailableException.class);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void historyNotFoundThrowsApiException() {
        server.expect(once(), requestTo(HISTORY_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.getMatchHistory(ACCOUNT_ID))
                .isInstanceOf(DeadlockApiException.class)
                .isNotInstanceOf(DeadlockApiUnavailableException.class);
    }

    @Test
    void benchmarksSendFiltersAndParsePercentiles() {
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/analytics/player-stats/metrics?")))
                .andExpect(queryParam("hero_ids", "5"))
                .andExpect(queryParam("min_average_badge", "70"))
                .andExpect(queryParam("max_average_badge", "79"))
                .andExpect(noQueryParam("min_unix_timestamp"))
                .andExpect(noQueryParam("include_buff_metrics"))
                .andRespond(withSuccess(fixture("player-stats-metrics.json"), MediaType.APPLICATION_JSON));

        Map<String, MetricPercentiles> benchmarks = client.getBenchmarks(5, 7);

        assertThat(benchmarks).containsOnlyKeys("net_worth_per_min", "player_damage_per_min", "last_hits");
        assertThat(benchmarks.get("net_worth_per_min")).isEqualTo(new MetricPercentiles(
                1155.8600623917873, 1043.3392628236145, 1153.0720547568835, 1274.3459494306755));
        assertThat(benchmarks.get("last_hits").p50()).isEqualTo(194.44039714993383);
    }

    @Test
    void benchmarksRejectInvalidTierWithoutHttpCall() {
        assertThatThrownBy(() -> client.getBenchmarks(5, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.getBenchmarks(5, 12)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void itemStatsSendFiltersAndParseByItemId() {
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/analytics/item-stats?")))
                .andExpect(queryParam("hero_ids", "5"))
                .andExpect(queryParam("min_average_badge", "70"))
                .andExpect(queryParam("max_average_badge", "79"))
                .andExpect(noQueryParam("hero_id="))
                .andRespond(withSuccess(fixture("item-stats.json"), MediaType.APPLICATION_JSON));

        Map<Long, ItemStat> stats = client.getItemStats(5, 7);

        assertThat(stats).containsOnlyKeys(7409189L, 26002154L, 84321454L);
        assertThat(stats.get(7409189L)).isEqualTo(new ItemStat(7409189L, 20522, 9634, 10888,
                388.6502290225124, 18.52731996580492));
        assertThat(stats.get(84321454L).avgBuyTimeS()).isEqualTo(790.7136480686695);
    }

    @Test
    void itemStatsRejectInvalidTierWithoutHttpCall() {
        assertThatThrownBy(() -> client.getItemStats(5, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.getItemStats(5, 12)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void heroStatsSendsBadgeRangeAndTimestamp() {
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/analytics/hero-stats?")))
                .andExpect(queryParam("min_average_badge", "70"))
                .andExpect(queryParam("max_average_badge", "79"))
                .andExpect(queryParam("min_unix_timestamp", "1789000000"))
                .andExpect(noQueryParam("hero_id"))
                .andExpect(noQueryParam("hero_ids"))
                .andExpect(noQueryParam("bucket"))
                .andRespond(withSuccess(fixture("hero-stats.json"), MediaType.APPLICATION_JSON));

        List<HeroStat> stats = client.getHeroStats(7, 1789000000L);

        assertThat(stats).hasSize(5);
        assertThat(stats.get(0)).isEqualTo(new HeroStat(1, 1500, 810, 690));
        assertThat(stats.get(1)).isEqualTo(new HeroStat(2, 1200, 624, 576));
    }

    @Test
    void heroStatsWithoutRankSendsNoBadgeFilter() {
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/analytics/hero-stats?")))
                .andExpect(queryParam("min_unix_timestamp", "1789000000"))
                .andExpect(noQueryParam("min_average_badge"))
                .andExpect(noQueryParam("max_average_badge"))
                .andExpect(noQueryParam("hero_id"))
                .andExpect(noQueryParam("hero_ids"))
                .andExpect(noQueryParam("bucket"))
                .andRespond(withSuccess(fixture("hero-stats.json"), MediaType.APPLICATION_JSON));

        List<HeroStat> stats = client.getHeroStats(null, 1789000000L);

        assertThat(stats).hasSize(5);
    }

    @Test
    void heroStatsRejectsTierOutOfRange() {
        assertThatThrownBy(() -> client.getHeroStats(-1, 1789000000L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.getHeroStats(12, 1789000000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void heroStatsServiceUnavailableIsMapped() {
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/analytics/hero-stats?")))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "60"));

        assertThatThrownBy(() -> client.getHeroStats(7, 1789000000L))
                .isInstanceOfSatisfying(DeadlockApiUnavailableException.class,
                        e -> assertThat(e.retryAfter()).contains(Duration.ofSeconds(60)));
        assertThat(sleeps).isEmpty();
    }

    @Test
    void itemFlowSendsHeroBadgeRangeAndTimestamp() {
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/analytics/item-flow-stats?")))
                .andExpect(queryParam("hero_ids", "61"))
                .andExpect(queryParam("min_average_badge", "70"))
                .andExpect(queryParam("max_average_badge", "79"))
                .andExpect(queryParam("min_unix_timestamp", "1789000000"))
                .andExpect(noQueryParam("hero_id="))
                .andRespond(withSuccess(fixture("item-flow-stats.json"), MediaType.APPLICATION_JSON));

        ItemFlowStats flow = client.getItemFlowStats(61, 7, 1789000000L);

        assertThat(flow.nodes()).hasSize(6);
        assertThat(flow.reachedPerColumn()).containsExactly(1000L, 800L);
        assertThat(flow.nodes().get(0)).isEqualTo(new ItemFlowNode(0, 1001L, 500L, 275L));
    }

    @Test
    void itemFlowWithoutRankSendsNoBadgeFilter() {
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/analytics/item-flow-stats?")))
                .andExpect(queryParam("hero_ids", "61"))
                .andExpect(queryParam("min_unix_timestamp", "1789000000"))
                .andExpect(noQueryParam("min_average_badge"))
                .andExpect(noQueryParam("max_average_badge"))
                .andExpect(noQueryParam("hero_id="))
                .andRespond(withSuccess(fixture("item-flow-stats.json"), MediaType.APPLICATION_JSON));

        ItemFlowStats flow = client.getItemFlowStats(61, null, 1789000000L);

        assertThat(flow.nodes()).hasSize(6);
    }

    @Test
    void itemFlowRejectsTierOutOfRange() {
        assertThatThrownBy(() -> client.getItemFlowStats(61, -1, 1789000000L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.getItemFlowStats(61, 12, 1789000000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void abilityOrderSendsHeroIdBadgeRangeAndTimestamp() {
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/analytics/ability-order-stats?")))
                .andExpect(queryParam("hero_id", "61"))
                .andExpect(queryParam("min_average_badge", "70"))
                .andExpect(queryParam("max_average_badge", "79"))
                .andExpect(queryParam("min_unix_timestamp", "1789000000"))
                .andExpect(noQueryParam("hero_ids"))
                .andRespond(withSuccess(fixture("ability-order-stats.json"), MediaType.APPLICATION_JSON));

        List<AbilityOrderStat> orders = client.getAbilityOrderStats(61, 7, 1789000000L);

        assertThat(orders).hasSize(3);
        assertThat(orders.get(0)).isEqualTo(new AbilityOrderStat(
                List.of(3001L, 3002L, 3001L, 3003L, 3004L), 1200L, 650L));
    }

    @Test
    void abilityOrderWithoutRankSendsNoBadgeFilter() {
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/analytics/ability-order-stats?")))
                .andExpect(queryParam("hero_id", "61"))
                .andExpect(queryParam("min_unix_timestamp", "1789000000"))
                .andExpect(noQueryParam("min_average_badge"))
                .andExpect(noQueryParam("max_average_badge"))
                .andExpect(noQueryParam("hero_ids"))
                .andRespond(withSuccess(fixture("ability-order-stats.json"), MediaType.APPLICATION_JSON));

        List<AbilityOrderStat> orders = client.getAbilityOrderStats(61, null, 1789000000L);

        assertThat(orders).hasSize(3);
    }

    @Test
    void abilityOrderRejectsTierOutOfRange() {
        assertThatThrownBy(() -> client.getAbilityOrderStats(61, -1, 1789000000L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.getAbilityOrderStats(61, 12, 1789000000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void itemFlowServiceUnavailableIsMapped() {
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/analytics/item-flow-stats?")))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "60"));

        assertThatThrownBy(() -> client.getItemFlowStats(61, 7, 1789000000L))
                .isInstanceOfSatisfying(DeadlockApiUnavailableException.class,
                        e -> assertThat(e.retryAfter()).contains(Duration.ofSeconds(60)));
        assertThat(sleeps).isEmpty();
    }

    @Test
    void abilityOrderServiceUnavailableIsMapped() {
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/analytics/ability-order-stats?")))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "60"));

        assertThatThrownBy(() -> client.getAbilityOrderStats(61, 7, 1789000000L))
                .isInstanceOfSatisfying(DeadlockApiUnavailableException.class,
                        e -> assertThat(e.retryAfter()).contains(Duration.ofSeconds(60)));
        assertThat(sleeps).isEmpty();
    }

    @Test
    void heroCountersSendBadgeRangeAndTimestamp() {
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/analytics/hero-counter-stats?")))
                .andExpect(queryParam("min_average_badge", "70"))
                .andExpect(queryParam("max_average_badge", "79"))
                .andExpect(queryParam("min_unix_timestamp", "1789000000"))
                .andExpect(noQueryParam("hero_id"))
                .andExpect(noQueryParam("hero_ids"))
                .andExpect(noQueryParam("bucket"))
                .andRespond(withSuccess(fixture("hero-counter-stats.json"), MediaType.APPLICATION_JSON));

        List<HeroCounterStat> stats = client.getHeroCounterStats(7, 1789000000L);

        assertThat(stats).hasSize(9);
        assertThat(stats.get(0)).isEqualTo(new HeroCounterStat(1, 2, 60, 100));
        assertThat(stats.get(1)).isEqualTo(new HeroCounterStat(2, 1, 40, 100));
    }

    @Test
    void heroCountersWithoutRankSendNoBadgeFilter() {
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/analytics/hero-counter-stats?")))
                .andExpect(queryParam("min_unix_timestamp", "1789000000"))
                .andExpect(noQueryParam("min_average_badge"))
                .andExpect(noQueryParam("max_average_badge"))
                .andExpect(noQueryParam("hero_id"))
                .andExpect(noQueryParam("hero_ids"))
                .andExpect(noQueryParam("bucket"))
                .andRespond(withSuccess(fixture("hero-counter-stats.json"), MediaType.APPLICATION_JSON));

        List<HeroCounterStat> stats = client.getHeroCounterStats(null, 1789000000L);

        assertThat(stats).hasSize(9);
    }

    @Test
    void heroCountersRejectTierOutOfRange() {
        assertThatThrownBy(() -> client.getHeroCounterStats(-1, 1789000000L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.getHeroCounterStats(12, 1789000000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void heroCountersServiceUnavailableIsMapped() {
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/analytics/hero-counter-stats?")))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "60"));

        assertThatThrownBy(() -> client.getHeroCounterStats(7, 1789000000L))
                .isInstanceOfSatisfying(DeadlockApiUnavailableException.class,
                        e -> assertThat(e.retryAfter()).contains(Duration.ofSeconds(60)));
        assertThat(sleeps).isEmpty();
    }

    @Test
    void itemStatsAgainstSendsEnemyHeroBadgeRangeAndTimestamp() {
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/analytics/item-stats?")))
                .andExpect(queryParam("enemy_hero_ids", "61"))
                .andExpect(queryParam("min_average_badge", "70"))
                .andExpect(queryParam("max_average_badge", "79"))
                .andExpect(queryParam("min_unix_timestamp", "1789000000"))
                .andExpect(noQueryParamNamed("hero_id"))
                .andExpect(noQueryParamNamed("hero_ids"))
                .andExpect(noQueryParam("bucket"))
                .andRespond(withSuccess(fixture("item-stats.json"), MediaType.APPLICATION_JSON));

        List<ItemStat> stats = client.getItemStatsAgainst(61, 7, 1789000000L);

        assertThat(stats).isNotEmpty();
        assertThat(stats.get(0)).isEqualTo(new ItemStat(7409189L, 20522, 9634, 10888,
                388.6502290225124, 18.52731996580492));
    }

    @Test
    void itemStatsAgainstWithoutRankSendsNoBadgeFilter() {
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/analytics/item-stats?")))
                .andExpect(queryParam("enemy_hero_ids", "61"))
                .andExpect(queryParam("min_unix_timestamp", "1789000000"))
                .andExpect(noQueryParam("min_average_badge"))
                .andExpect(noQueryParam("max_average_badge"))
                .andExpect(noQueryParamNamed("hero_id"))
                .andExpect(noQueryParamNamed("hero_ids"))
                .andExpect(noQueryParam("bucket"))
                .andRespond(withSuccess(fixture("item-stats.json"), MediaType.APPLICATION_JSON));

        List<ItemStat> stats = client.getItemStatsAgainst(61, null, 1789000000L);

        assertThat(stats).isNotEmpty();
    }

    private static RequestMatcher noQueryParamNamed(String name) {
        return request -> {
            String query = request.getURI().getQuery();
            if (query != null) {
                for (String pair : query.split("&")) {
                    String key = pair.split("=")[0];
                    assertThat(key).isNotEqualTo(name);
                }
            }
        };
    }

    @Test
    void itemStatsAgainstRejectsTierOutOfRange() {
        assertThatThrownBy(() -> client.getItemStatsAgainst(61, -1, 1789000000L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.getItemStatsAgainst(61, 12, 1789000000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void itemStatsAgainstServiceUnavailableIsMapped() {
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/analytics/item-stats?")))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "60"));

        assertThatThrownBy(() -> client.getItemStatsAgainst(61, 7, 1789000000L))
                .isInstanceOfSatisfying(DeadlockApiUnavailableException.class,
                        e -> assertThat(e.retryAfter()).contains(Duration.ofSeconds(60)));
        assertThat(sleeps).isEmpty();
    }

    @Test
    void itemFlowWithMissingListsIsEmpty() {
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/analytics/item-flow-stats?")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        ItemFlowStats flow = client.getItemFlowStats(61, null, 1789000000L);

        assertThat(flow.nodes()).isEmpty();
        assertThat(flow.reachedPerColumn()).isEmpty();
    }

    @Test
    void playerRankIsParsed() {
        server.expect(once(), requestTo(RANK_URL))
                .andRespond(withSuccess(fixture("player-rank.json"), MediaType.APPLICATION_JSON));

        assertThat(client.getPlayerRank(ACCOUNT_ID)).isEqualTo(new PlayerRank(74, 7, 4));
    }

    @Test
    void playerRankUnrankedReturnsZeros() {
        server.expect(once(), requestTo(RANK_URL))
                .andRespond(withSuccess(fixture("player-rank-unranked.json"), MediaType.APPLICATION_JSON));

        assertThat(client.getPlayerRank(ACCOUNT_ID)).isEqualTo(new PlayerRank(0, 0, 0));
    }

    @Test
    void ranksAreParsed() {
        server.expect(once(), requestTo(RANKS_URL))
                .andRespond(withSuccess(fixture("assets-ranks.json"), MediaType.APPLICATION_JSON));

        assertThat(client.getRanks()).containsExactly(
                new RankAsset(0, "Obscurus", Map.of("large", "https://api.deadlock-api.com/v1/assets/ranks/0/large.png")),
                new RankAsset(1, "Initiate", Map.of(
                        "large", "https://api.deadlock-api.com/v1/assets/ranks/1/large.png",
                        "large_subrank1", "https://api.deadlock-api.com/v1/assets/ranks/1/1/image",
                        "large_subrank2", "https://api.deadlock-api.com/v1/assets/ranks/1/2/image")),
                new RankAsset(2, "Seeker"));
    }

    @Test
    void heroesAreParsedWithClassName() {
        server.expect(once(), requestTo(BASE_URL + "/v1/assets/heroes"))
                .andRespond(withSuccess(fixture("assets-heroes.json"), MediaType.APPLICATION_JSON));

        assertThat(client.getHeroes()).containsExactly(
                new HeroAsset(1, "hero_inferno", "Infernus",
                        new HeroImages("https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/inferno_sm.png",
                                "https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/inferno_card.png")),
                new HeroAsset(2, "hero_gigawatt", "Seven",
                        new HeroImages("https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/seven_sm.png",
                                "https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/seven_card.png")),
                new HeroAsset(3, "hero_hornet", "Vindicta"));
    }

    @Test
    void itemsAreParsedToMinimalRecords() {
        server.expect(once(), requestTo(BASE_URL + "/v1/assets/items"))
                .andRespond(withSuccess(fixture("assets-items.json"), MediaType.APPLICATION_JSON));

        List<ItemAsset> items = client.getItems();

        assertThat(items).hasSize(3);
        assertThat(items.get(0)).isEqualTo(new ItemAsset(1548066885L, "upgrade_clip_size", "Extended Magazine",
                "upgrade", 1, "weapon", 800,
                "https://assets-bucket.deadlock-api.com/assets-api-res/images/upgrades/mods_weapon/clip_size.png"));
        assertThat(items.get(1)).isEqualTo(new ItemAsset(2521299219L, "ability_golden_idol", "Soul Urn",
                "ability", null, null, null));
    }

    @Test
    void retriesOnceOn503WithRetryAfterAndSucceeds() {
        server.expect(once(), requestTo(RANKS_URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "1"));
        server.expect(once(), requestTo(RANKS_URL))
                .andRespond(withSuccess(fixture("assets-ranks.json"), MediaType.APPLICATION_JSON));

        assertThat(client.getRanks()).hasSize(3);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
    }

    @Test
    void failsAfterSecond503WithUnavailableException() {
        server.expect(twice(), requestTo(RANKS_URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "1"));

        assertThatThrownBy(() -> client.getRanks())
                .isInstanceOfSatisfying(DeadlockApiUnavailableException.class,
                        e -> assertThat(e.retryAfter()).isPresent());
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
    }

    @Test
    void doesNotSleepWhenRetryAfterExceedsCap() {
        server.expect(once(), requestTo(RANKS_URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "60"));

        assertThatThrownBy(() -> client.getRanks())
                .isInstanceOfSatisfying(DeadlockApiUnavailableException.class,
                        e -> assertThat(e.retryAfter()).contains(Duration.ofSeconds(60)));
        assertThat(sleeps).isEmpty();
    }

    @Test
    void usesDefaultRetryAfterWhenHeaderMissingOrInvalid() {
        server.expect(once(), requestTo(RANKS_URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(once(), requestTo(RANKS_URL))
                .andRespond(withSuccess(fixture("assets-ranks.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(RANKS_URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "abc"));
        server.expect(once(), requestTo(RANKS_URL))
                .andRespond(withSuccess(fixture("assets-ranks.json"), MediaType.APPLICATION_JSON));

        client.getRanks();
        client.getRanks();

        assertThat(sleeps).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(2));
    }

    @Test
    void rateLimit429OutsideHistoryIsNotRetried() {
        server.expect(once(), requestTo(RANK_URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "5"));

        assertThatThrownBy(() -> client.getPlayerRank(ACCOUNT_ID))
                .isInstanceOfSatisfying(DeadlockApiUnavailableException.class,
                        e -> assertThat(e.retryAfter()).contains(Duration.ofSeconds(5)));
        assertThat(sleeps).isEmpty();
    }

    @Test
    void serverError500ThrowsApiExceptionWithoutRetry() {
        server.expect(once(), requestTo(RANKS_URL)).andRespond(withServerError());

        assertThatThrownBy(() -> client.getRanks())
                .isInstanceOf(DeadlockApiException.class)
                .isNotInstanceOf(DeadlockApiUnavailableException.class);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void ioErrorBecomesUnavailable() {
        server.expect(once(), requestTo(RANKS_URL)).andRespond(withException(new IOException("connection reset")));

        assertThatThrownBy(() -> client.getRanks())
                .isInstanceOf(DeadlockApiUnavailableException.class)
                .hasCauseInstanceOf(Exception.class);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void malformedJsonThrowsApiException() {
        server.expect(once(), requestTo(RANKS_URL)).andRespond(withSuccess("[{\"tier\":", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.getRanks())
                .isInstanceOf(DeadlockApiException.class)
                .isNotInstanceOf(DeadlockApiUnavailableException.class);
    }

    @Test
    void sendsAcceptAndUserAgentHeaders() {
        server.expect(once(), requestTo(startsWith(BASE_URL)))
                .andExpect(header("Accept", "application/json"))
                .andExpect(header("User-Agent", "Rejuv-Discord-Bot"))
                .andRespond(withSuccess(fixture("assets-ranks.json"), MediaType.APPLICATION_JSON));

        client.getRanks();
    }

    static String historyEntryJson(long matchId) {
        return """
                {"match_id":%d,"hero_id":1,"hero_level":30,"start_time":1789000000,"match_duration_s":1900,\
                "match_mode":1,"match_result":0,"player_team":0,"player_match_outcome":1,"player_kills":5,\
                "player_deaths":5,"player_assists":5,"net_worth":30000,"last_hits":150,"denies":10,\
                "ranked_display_badge":null}""".formatted(matchId);
    }
}
