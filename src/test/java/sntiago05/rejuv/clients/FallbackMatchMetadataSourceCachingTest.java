package sntiago05.rejuv.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static sntiago05.rejuv.clients.JsonMatchMetadataSourceTest.BASE_URL;
import static sntiago05.rejuv.clients.JsonMatchMetadataSourceTest.MATCH_ID;
import static sntiago05.rejuv.clients.JsonMatchMetadataSourceTest.METADATA_URL;
import static sntiago05.rejuv.clients.JsonMatchMetadataSourceTest.fixture;
import static sntiago05.rejuv.clients.RawMatchMetadataSourceTest.METADATA_RAW_URL;

import java.time.Duration;

import com.github.benmanes.caffeine.cache.Policy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.context.ApplicationContext;
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
import sntiago05.rejuv.domain.MatchTelemetry;

@SpringJUnitConfig(FallbackMatchMetadataSourceCachingTest.TestConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class FallbackMatchMetadataSourceCachingTest {

    @Configuration
    @Import({
            CacheConfig.class,
            JsonMatchMetadataSource.class,
            RawMatchMetadataSource.class,
            FallbackMatchMetadataSource.class
    })
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
    private MatchMetadataSource source;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private MockRestServiceServer server;

    @Autowired
    private ApplicationContext context;

    @BeforeEach
    void resetServer() {
        server.reset();
    }

    @AfterEach
    void verifyServer() {
        server.verify();
    }

    @Test
    void secondCallForSameMatchIsCached() {
        server.expect(once(), requestTo(METADATA_URL))
                .andRespond(withSuccess(fixture("match-metadata.json"), MediaType.APPLICATION_JSON));

        MatchTelemetry first = source.getMatch(MATCH_ID);
        MatchTelemetry second = source.getMatch(MATCH_ID);

        assertThat(first).isEqualTo(second);
        assertThat(first.matchId()).isEqualTo(MATCH_ID);
        assertThat(cacheManager.getCache(CacheConfig.DEADLOCK_MATCH_METADATA).get(MATCH_ID)).isNotNull();
        assertThat(cacheManager.getCache(CacheConfig.DEADLOCK_MATCH_METADATA_RAW).get(MATCH_ID)).isNull();
    }

    @Test
    void fallbackResultIsCachedInRawCacheAndSecondCallRetriesJson() {
        byte[] compressed = RawMetadataTestData.compress(RawMetadataTestData.defaultValidPayload());
        // First call: JSON fails, raw succeeds
        server.expect(once(), requestTo(METADATA_URL)).andRespond(withServerError());
        server.expect(once(), requestTo(METADATA_RAW_URL))
                .andRespond(withSuccess(compressed, MediaType.APPLICATION_OCTET_STREAM));
        // Second call: JSON is retried (fails again), but raw is served from cache (no HTTP call to raw)
        server.expect(once(), requestTo(METADATA_URL)).andRespond(withServerError());

        MatchTelemetry first = source.getMatch(MATCH_ID);
        assertThat(cacheManager.getCache(CacheConfig.DEADLOCK_MATCH_METADATA).get(MATCH_ID)).isNull();
        assertThat(cacheManager.getCache(CacheConfig.DEADLOCK_MATCH_METADATA_RAW).get(MATCH_ID)).isNotNull();

        MatchTelemetry second = source.getMatch(MATCH_ID);

        assertThat(first).isEqualTo(second);
        assertThat(first.matchId()).isEqualTo(MATCH_ID);
        assertThat(cacheManager.getCache(CacheConfig.DEADLOCK_MATCH_METADATA).get(MATCH_ID)).isNull();
        assertThat(cacheManager.getCache(CacheConfig.DEADLOCK_MATCH_METADATA_RAW).get(MATCH_ID)).isNotNull();
    }

    @Test
    void fallbackResultNotCachedInJsonCacheAndSecondCallRecoversWhenJsonSucceeds() {
        byte[] compressed = RawMetadataTestData.compress(RawMetadataTestData.defaultValidPayload());
        server.expect(once(), requestTo(METADATA_URL)).andRespond(withServerError());
        server.expect(once(), requestTo(METADATA_RAW_URL))
                .andRespond(withSuccess(compressed, MediaType.APPLICATION_OCTET_STREAM));
        server.expect(once(), requestTo(METADATA_URL))
                .andRespond(withSuccess(fixture("match-metadata.json"), MediaType.APPLICATION_JSON));

        MatchTelemetry first = source.getMatch(MATCH_ID);
        assertThat(first.durationS()).isEqualTo(1800);
        assertThat(cacheManager.getCache(CacheConfig.DEADLOCK_MATCH_METADATA).get(MATCH_ID)).isNull();
        assertThat(cacheManager.getCache(CacheConfig.DEADLOCK_MATCH_METADATA_RAW).get(MATCH_ID)).isNotNull();

        MatchTelemetry second = source.getMatch(MATCH_ID);
        assertThat(second.durationS()).isEqualTo(1952);
        assertThat(cacheManager.getCache(CacheConfig.DEADLOCK_MATCH_METADATA).get(MATCH_ID)).isNotNull();
    }

    @Test
    void notFoundNeverFallsBackAndNothingIsCached() {
        server.expect(once(), requestTo(METADATA_URL))
                .andRespond(withResourceNotFound());

        assertThatThrownBy(() -> source.getMatch(MATCH_ID))
                .isInstanceOf(MatchMetadataNotFoundException.class);

        assertThat(cacheManager.getCache(CacheConfig.DEADLOCK_MATCH_METADATA).get(MATCH_ID)).isNull();
        assertThat(cacheManager.getCache(CacheConfig.DEADLOCK_MATCH_METADATA_RAW).get(MATCH_ID)).isNull();
    }

    @Test
    void differentMatchesAreCachedSeparately() {
        long matchId2 = 900000004L;
        String metadataUrl2 = BASE_URL + "/v1/matches/" + matchId2 + "/metadata";
        String match2Json = """
                {"match_info":{"match_id":%d,"duration_s":1200,"players":[{"account_id":1000003,"team":0,"hero_id":5}]}}"""
                .formatted(matchId2);

        server.expect(once(), requestTo(METADATA_URL))
                .andRespond(withSuccess(fixture("match-metadata.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(metadataUrl2))
                .andRespond(withSuccess(match2Json, MediaType.APPLICATION_JSON));

        for (int round = 0; round < 2; round++) {
            MatchTelemetry m1 = source.getMatch(MATCH_ID);
            MatchTelemetry m2 = source.getMatch(matchId2);

            assertThat(m1.matchId()).isEqualTo(MATCH_ID);
            assertThat(m2.matchId()).isEqualTo(matchId2);
        }
    }

    @Test
    void failuresAreNotCached() {
        server.expect(once(), requestTo(METADATA_URL)).andRespond(withServerError());
        server.expect(once(), requestTo(METADATA_RAW_URL)).andRespond(withServerError());
        server.expect(once(), requestTo(METADATA_URL))
                .andRespond(withSuccess(fixture("match-metadata.json"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> source.getMatch(MATCH_ID)).isInstanceOf(DeadlockApiException.class);

        assertThat(source.getMatch(MATCH_ID).matchId()).isEqualTo(MATCH_ID);
    }

    @Test
    void cacheHasConfiguredTtlAndSize() {
        CaffeineCache jsonCache = (CaffeineCache) cacheManager.getCache(CacheConfig.DEADLOCK_MATCH_METADATA);
        assertThat(jsonCache).isNotNull();

        Policy<Object, Object> jsonPolicy = jsonCache.getNativeCache().policy();
        assertThat(jsonPolicy.expireAfterWrite()).hasValueSatisfying(
                expiration -> assertThat(expiration.getExpiresAfter()).isEqualTo(Duration.ofHours(24)));
        assertThat(jsonPolicy.eviction()).hasValueSatisfying(
                eviction -> assertThat(eviction.getMaximum()).isEqualTo(1000L));

        CaffeineCache rawCache = (CaffeineCache) cacheManager.getCache(CacheConfig.DEADLOCK_MATCH_METADATA_RAW);
        assertThat(rawCache).isNotNull();

        Policy<Object, Object> rawPolicy = rawCache.getNativeCache().policy();
        assertThat(rawPolicy.expireAfterWrite()).hasValueSatisfying(
                expiration -> assertThat(expiration.getExpiresAfter()).isEqualTo(Duration.ofMinutes(2)));
        assertThat(rawPolicy.eviction()).hasValueSatisfying(
                eviction -> assertThat(eviction.getMaximum()).isEqualTo(100L));
    }

    @Test
    void onlyTheFallbackIsPrimary() {
        MatchMetadataSource bean = context.getBean(MatchMetadataSource.class);
        assertThat(bean).isNotNull();
        assertThat(AopUtils.getTargetClass(bean)).isEqualTo(FallbackMatchMetadataSource.class);
    }
}
