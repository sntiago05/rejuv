package sntiago05.rejuv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import sntiago05.rejuv.clients.AnalysisClient;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.FallbackMatchMetadataSource;
import sntiago05.rejuv.clients.JsonMatchMetadataSource;
import sntiago05.rejuv.clients.RawMatchMetadataSource;
import sntiago05.rejuv.clients.Sleeper;
import sntiago05.rejuv.config.AnthropicPricingProperties;
import sntiago05.rejuv.config.CacheConfig;
import sntiago05.rejuv.config.DeadlockApiConfig;
import sntiago05.rejuv.config.DeadlockApiProperties;
import sntiago05.rejuv.domain.AnalysisOutcome;
import sntiago05.rejuv.domain.AnalysisReport;
import sntiago05.rejuv.domain.AnalysisResult;
import sntiago05.rejuv.domain.LinkedAccount;
import sntiago05.rejuv.domain.MatchAnalysis;
import sntiago05.rejuv.domain.MatchFilter;
import sntiago05.rejuv.repositories.LinkedAccountRepository;
import sntiago05.rejuv.repositories.MatchAnalysisRepository;
import sntiago05.rejuv.services.AnalyzeService;
import sntiago05.rejuv.services.AssetImages;
import sntiago05.rejuv.services.BuildCoachProvider;
import sntiago05.rejuv.services.BuildService;
import sntiago05.rejuv.services.CoachContextService;
import sntiago05.rejuv.services.CompareService;
import sntiago05.rejuv.services.DeathsCoachProvider;
import sntiago05.rejuv.services.FormCoachProvider;
import sntiago05.rejuv.services.GameAssets;
import sntiago05.rejuv.services.HeroResolver;
import sntiago05.rejuv.services.ItemsService;
import sntiago05.rejuv.services.MatchSearchService;
import sntiago05.rejuv.services.MatchVisualsService;
import sntiago05.rejuv.services.MatchupCoachProvider;
import sntiago05.rejuv.services.MetricsService;
import sntiago05.rejuv.services.PhasesCoachProvider;
import sntiago05.rejuv.services.ProfileService;
import sntiago05.rejuv.services.UsageService;

/**
 * Task 039: counts the deadlock-api HTTP requests that the service calls behind each command make, cold (empty
 * caches) and warm (same command again), through the real Caffeine caches. Mirrors the command's own asset calls
 * (thumbnail/badge). Disabled by default; run with
 * {@code ./mvnw test -Dtest=UpstreamCallCountBenchmarkTest -Dbenchmark=true}.
 */
@EnabledIfSystemProperty(named = "benchmark", matches = "true")
@SpringJUnitConfig(UpstreamCallCountBenchmarkTest.TestConfig.class)
class UpstreamCallCountBenchmarkTest {

    static final String BASE_URL = "https://deadlock-api.test";
    static final long USER_ID = 42L;
    static final long ACCOUNT_ID = 1000001L;
    static final long MATCH_ID = 900000003L;

    /** Counts requests by path template; the body comes from the fixtures. */
    static final Map<String, Integer> CALLS = new TreeMap<>();
    static final AtomicReference<MatchAnalysis> STORED = new AtomicReference<>();

    @Configuration
    @Import({CacheConfig.class, DeadlockApiClient.class, JsonMatchMetadataSource.class, RawMatchMetadataSource.class,
            FallbackMatchMetadataSource.class, GameAssets.class, HeroResolver.class, MatchSearchService.class,
            ProfileService.class, CompareService.class, ItemsService.class, MetricsService.class,
            MatchVisualsService.class, CoachContextService.class, BuildService.class, PhasesCoachProvider.class,
            DeathsCoachProvider.class, BuildCoachProvider.class, FormCoachProvider.class, MatchupCoachProvider.class,
            AnalyzeService.class, AssetImages.class})
    static class TestConfig {

        private final DeadlockApiProperties properties = new DeadlockApiProperties(BASE_URL, Duration.ofSeconds(3),
                Duration.ofSeconds(10), Duration.ofSeconds(2), Duration.ofSeconds(10));
        private final RestClient.Builder builder = DeadlockApiConfig.restClientBuilder(properties);

        @Bean
        MockRestServiceServer mockRestServiceServer() {
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
            server.expect(manyTimes(), request -> { }).andRespond(request -> {
                String path = request.getURI().getPath().replaceAll("/\\d+", "/{id}");
                String query = request.getURI().getQuery() != null ? request.getURI().getQuery() : "";
                CALLS.merge(path, 1, Integer::sum);
                MockClientHttpResponse response = new MockClientHttpResponse(
                        body(path, query).getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
                response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                return response;
            });
            return server;
        }

        @Bean
        RestClient deadlockApiRestClient(MockRestServiceServer mockRestServiceServer) {
            return builder.build();
        }

        @Bean
        DeadlockApiProperties deadlockApiProperties() {
            return properties;
        }

        @Bean
        Sleeper sleeper() {
            return duration -> { };
        }

        @Bean
        AnthropicPricingProperties anthropicPricingProperties() {
            return new AnthropicPricingProperties(0, 0);
        }

        @Bean
        AnalysisClient analysisClient() {
            AnalysisClient client = mock(AnalysisClient.class);
            when(client.analyze(any())).thenAnswer(invocation -> {
                CALLS.merge("(anthropic) /v1/messages", 1, Integer::sum);
                return new AnalysisOutcome(new AnalysisResult("s", "l", "a", List.of("k")), "claude-haiku-5-5", 1, 1, 1);
            });
            return client;
        }

        @Bean
        MatchAnalysisRepository matchAnalysisRepository() {
            MatchAnalysisRepository repository = mock(MatchAnalysisRepository.class);
            when(repository.findByAccountIdAndMatchId(anyLong(), anyLong()))
                    .thenAnswer(invocation -> Optional.ofNullable(STORED.get()));
            when(repository.save(any(MatchAnalysis.class))).thenAnswer(invocation -> {
                STORED.set(invocation.getArgument(0));
                return invocation.getArgument(0);
            });
            when(repository.findAnalyzedMatchIds(anyLong(), anyCollection())).thenReturn(List.of());
            return repository;
        }

        @Bean
        LinkedAccountRepository linkedAccountRepository() {
            LinkedAccountRepository repository = mock(LinkedAccountRepository.class);
            when(repository.findById(USER_ID)).thenReturn(Optional.of(new LinkedAccount(USER_ID, ACCOUNT_ID, null)));
            return repository;
        }

        @Bean
        UsageService usageService() {
            return mock(UsageService.class);
        }
    }

    @Autowired
    private CacheManager cacheManager;
    @Autowired
    private AnalyzeService analyzeService;
    @Autowired
    private ProfileService profileService;
    @Autowired
    private MatchSearchService matchSearchService;
    @Autowired
    private CompareService compareService;
    @Autowired
    private ItemsService itemsService;
    @Autowired
    private AssetImages assetImages;

    @BeforeEach
    void emptyCaches() {
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
        CALLS.clear();
        STORED.set(null);
    }

    @Test
    void analyze() {
        Runnable command = () -> {
            AnalysisReport report = analyzeService.analyze(USER_ID, ACCOUNT_ID, MatchFilter.LATEST);
            assetImages.heroCardUrl(report.heroId());
        };
        measure("/analyze (fresh, then stored)", command);
    }

    @Test
    void profile() {
        measure("/profile", () -> {
            var summary = profileService.getProfile(ACCOUNT_ID);
            assetImages.rankBadgeUrl(summary.rankTier(), summary.subrank());
        });
    }

    @Test
    void matches() {
        measure("/matches", () -> matchSearchService.find(ACCOUNT_ID, MatchFilter.LATEST, 10));
    }

    @Test
    void compare() {
        measure("/compare", () -> {
            var report = compareService.compare(ACCOUNT_ID, null);
            assetImages.heroCardUrl(report.heroId());
        });
    }

    @Test
    void items() {
        measure("/items", () -> {
            var report = itemsService.compare(ACCOUNT_ID, "Infernus", null);
            assetImages.heroCardUrl(report.heroId());
        });
    }

    private static void measure(String name, Runnable command) {
        command.run();
        Map<String, Integer> cold = Map.copyOf(CALLS);
        CALLS.clear();
        command.run();
        Map<String, Integer> warm = Map.copyOf(CALLS);
        System.out.printf("[benchmark] %s: cold %d HTTP calls %s; warm %d HTTP calls %s%n", name,
                total(cold), new TreeMap<>(cold), total(warm), new TreeMap<>(warm));
        assertThat(total(cold)).isPositive();
    }

    private static int total(Map<String, Integer> calls) {
        return calls.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static String body(String path, String query) {
        return switch (path) {
            case "/v1/players/{id}/match-history" -> history();
            case "/v1/players/{id}/rank" -> fixture("player-rank.json");
            case "/v1/analytics/player-stats/metrics" -> fixture("player-stats-metrics.json");
            case "/v1/analytics/item-stats" -> query.contains("account_id")
                    ? fixture("player-item-stats.json") : fixture("item-stats.json");
            case "/v1/analytics/hero-counter-stats" -> fixture("hero-counter-stats.json");
            case "/v1/analytics/item-flow-stats" -> fixture("item-flow-stats.json");
            case "/v1/analytics/ability-order-stats" -> fixture("ability-order-stats.json");
            case "/v1/analytics/player-performance-curve" -> fixture("player-performance-curve.json");
            case "/v1/matches/{id}/metadata" -> fixture("match-metadata-coach.json");
            case "/v1/assets/ranks" -> fixture("assets-ranks.json");
            case "/v1/assets/heroes" -> fixture("assets-heroes.json");
            // Adds the shop upgrade bought in the coach fixture, so the build coach layer runs as in a real match.
            case "/v1/assets/items" -> fixture("assets-items.json").replaceFirst("\\[", """
                    [{"id":1001,"class_name":"upgrade_test","name":"Test Upgrade","type":"upgrade","item_tier":1,\
                    "item_slot_type":"weapon","cost":800},""");
            default -> throw new IllegalStateException("No fixture for " + path);
        };
    }

    /** 200 entries, newest first; the newest is the fixture match (hero 1, ranked badge 74), most are hero 1. */
    private static String history() {
        return IntStream.range(0, 200)
                .mapToObj(i -> """
                        {"account_id":%d,"match_id":%d,"hero_id":%d,"hero_level":30,"start_time":%d,"game_mode":1,\
                        "match_mode":4,"match_duration_s":%d,"match_result":1,"player_team":%d,"player_match_outcome":1,\
                        "player_kills":7,"player_deaths":5,"player_assists":10,"denies":14,"net_worth":35120,\
                        "last_hits":198,"ranked_display_badge":74}"""
                        .formatted(ACCOUNT_ID, MATCH_ID - i, i % 4 == 3 ? 2 : 1, 1_789_000_000L - i * 3_600L,
                                1_952 - i % 7, i % 2))
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static String fixture(String name) {
        try {
            return Files.readString(Path.of("src/test/resources/fixtures/deadlock-api", name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
