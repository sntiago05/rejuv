package sntiago05.rejuv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.dto.ItemAsset;
import sntiago05.rejuv.clients.dto.MatchHistoryEntry;
import sntiago05.rejuv.config.DeadlockApiConfig;
import sntiago05.rejuv.config.DeadlockApiProperties;
import sntiago05.rejuv.services.GameAssets;

/**
 * Before/after timings of the refactored hot paths. Disabled by default; run with
 * {@code ./mvnw test -Dtest=PerformanceBenchmarkTest -Dbenchmark=true}. Prints medians; asserts only correctness.
 */
@EnabledIfSystemProperty(named = "benchmark", matches = "true")
class PerformanceBenchmarkTest {

    private static final String BASE_URL = "https://deadlock-api.test";
    private static final long ACCOUNT_ID = 1000001L;
    private static final String HISTORY_URL = BASE_URL + "/v1/players/" + ACCOUNT_ID + "/match-history";
    private static final DeadlockApiProperties PROPERTIES = new DeadlockApiProperties(BASE_URL, Duration.ofSeconds(3),
            Duration.ofSeconds(10), Duration.ofSeconds(2), Duration.ofSeconds(10));

    // Largest history observed live: 6,842 matches, about 5 MB (docs/PROJECT_CONTEXT.md).
    private static final int LIVE_HISTORY_SIZE = 6_842;
    private static final int LIVE_ITEMS_SIZE = 764;
    private static final int WARMUP = 15;
    private static final int RUNS = 40;

    @Test
    void matchHistoryFullParseVersusPrefixRead() {
        String body = IntStream.range(0, LIVE_HISTORY_SIZE)
                .mapToObj(i -> historyEntryJson(900000001L + i))
                .collect(Collectors.joining(",", "[", "]"));

        RestClient.Builder builder = DeadlockApiConfig.restClientBuilder(PROPERTIES);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(manyTimes(), requestTo(HISTORY_URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        RestClient restClient = builder.build();
        DeadlockApiClient client = new DeadlockApiClient(restClient, PROPERTIES, wait -> { });

        // Previous implementation: deserialize the whole array, then keep the first 200 entries.
        Supplier<List<MatchHistoryEntry>> before = () -> {
            List<MatchHistoryEntry> all = restClient.get().uri(HISTORY_URL).retrieve()
                    .body(new ParameterizedTypeReference<List<MatchHistoryEntry>>() { });
            return List.copyOf(all.subList(0, Math.min(all.size(), DeadlockApiClient.MAX_HISTORY_ENTRIES)));
        };
        Supplier<List<MatchHistoryEntry>> after = () -> client.getMatchHistory(ACCOUNT_ID);

        assertThat(after.get()).isEqualTo(before.get());
        report("match history " + LIVE_HISTORY_SIZE + " entries (" + body.length() / 1024 + " KiB)",
                medianMillis(before), medianMillis(after));
    }

    @Test
    void itemLookupRebuiltPerCommandVersusCachedIndex() {
        List<ItemAsset> items = IntStream.range(0, LIVE_ITEMS_SIZE)
                .mapToObj(i -> new ItemAsset(i, "item_" + i, "Item " + i, "upgrade", 1 + i % 5, "weapon", 500))
                .toList();
        // Plain stub instead of a Mockito mock, so mock overhead does not hide the difference.
        DeadlockApiClient client = new DeadlockApiClient(RestClient.create(), PROPERTIES, wait -> { }) {
            @Override
            public List<ItemAsset> getItems() {
                return items;
            }
        };
        GameAssets assets = new GameAssets(client);

        // Previous implementation: BuildService and CounterService each built this map on every command.
        Supplier<Map<Long, String>> before = () -> {
            Map<Long, String> names = new HashMap<>(Math.max((int) (items.size() / 0.75f) + 1, 16));
            for (ItemAsset item : items) {
                if (item.name() != null) {
                    names.putIfAbsent(item.id(), item.name());
                }
            }
            return names;
        };
        Supplier<Map<Long, String>> after = assets::itemNames;

        assertThat(after.get()).isEqualTo(before.get());
        report("item names index (" + LIVE_ITEMS_SIZE + " items)", medianMillis(before), medianMillis(after));
    }

    private static double medianMillis(Supplier<?> task) {
        for (int i = 0; i < WARMUP; i++) {
            task.get();
        }
        double[] millis = new double[RUNS];
        for (int i = 0; i < RUNS; i++) {
            long start = System.nanoTime();
            task.get();
            millis[i] = (System.nanoTime() - start) / 1_000_000.0;
        }
        Arrays.sort(millis);
        return millis[RUNS / 2];
    }

    private static void report(String name, double beforeMs, double afterMs) {
        System.out.printf("[benchmark] %s: before %.3f ms, after %.3f ms, %.1f%% less%n",
                name, beforeMs, afterMs, (1 - afterMs / beforeMs) * 100);
    }

    // Padded with fields the DTO ignores so each entry is close to the ~730 bytes of a live entry.
    private static String historyEntryJson(long matchId) {
        return """
                {"account_id":1000001,"match_id":%d,"hero_id":1,"hero_level":30,"start_time":1789000000,\
                "game_mode":1,"match_mode":1,"match_duration_s":1900,"objectives_mask_team0":12345,\
                "objectives_mask_team1":54321,"match_result":0,"player_team":0,"player_match_outcome":1,\
                "player_kills":5,"player_deaths":5,"player_assists":5,"denies":10,"net_worth":30000,\
                "last_hits":150,"team_abandoned":null,"abandoned_time_s":null,"ranked_display_badge":null,\
                "brawl_score_team0":null,"brawl_score_team1":null,"brawl_avg_round_time_s":null,\
                "player_level":30,"hero_damage":25000,"hero_healing":3000,"self_healing":4000,\
                "objective_damage":8000,"neutral_damage":12000,"boss_damage":2000,"max_health":2500,\
                "ability_points":32,"shots_hit":900,"shots_missed":1200,"hero_bullets_hit":400,\
                "hero_bullets_hit_crit":80,"gold_player":12000,"gold_lane_creep":9000,\
                "gold_neutral_creep":4000,"gold_boss":1500,"gold_treasure":300,"gold_denied":200,\
                "gold_death_loss":900,"gold_orbs":1100,"username":null,"comment":"padding"}"""
                .formatted(matchId);
    }
}
