package sntiago05.rejuv.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.github.benmanes.caffeine.cache.Policy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.DeadlockApiUnavailableException;
import sntiago05.rejuv.clients.dto.MatchHistoryEntry;
import sntiago05.rejuv.clients.dto.PlayerRank;
import sntiago05.rejuv.config.CacheConfig;
import sntiago05.rejuv.repositories.LinkedAccountRepository;

@SpringJUnitConfig(ProfileServiceCachingTest.TestConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ProfileServiceCachingTest {

    @Configuration
    @Import({CacheConfig.class, ProfileService.class, GameAssets.class})
    static class TestConfig {

        // DeadlockApiClient has @Cacheable methods, so its bean is wrapped in a caching proxy:
        // tests stub and verify this raw mock, not the injected bean.
        final DeadlockApiClient client = mock(DeadlockApiClient.class);

        @Bean
        LinkedAccountRepository linkedAccountRepository() {
            return mock(LinkedAccountRepository.class);
        }

        @Bean
        DeadlockApiClient deadlockApiClient() {
            return client;
        }
    }

    @Autowired
    private ProfileService service;

    @Autowired
    private TestConfig config;

    private DeadlockApiClient client;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void stubAssets() {
        client = config.client;
        when(client.getPlayerRank(anyLong())).thenReturn(new PlayerRank(0, 0, 0));
        when(client.getRanks()).thenReturn(List.of());
        when(client.getHeroes()).thenReturn(List.of());
    }

    @Test
    void secondCallForSameAccountHitsCache() {
        when(client.getMatchHistory(1000001L)).thenReturn(List.of(entry()));

        assertThat(service.getProfile(1000001L).recentMatches()).isEqualTo(1);
        assertThat(service.getProfile(1000001L).recentMatches()).isEqualTo(1);

        verify(client, times(1)).getMatchHistory(1000001L);
        assertThat(profileCache().get(1000001L)).isNotNull();
    }

    @Test
    void differentAccountsAreCachedSeparately() {
        when(client.getMatchHistory(1000001L)).thenReturn(List.of(entry()));
        when(client.getMatchHistory(1000002L)).thenReturn(List.of(entry(), entry()));

        for (int round = 0; round < 2; round++) {
            assertThat(service.getProfile(1000001L).recentMatches()).isEqualTo(1);
            assertThat(service.getProfile(1000002L).recentMatches()).isEqualTo(2);
        }

        verify(client, times(1)).getMatchHistory(1000001L);
        verify(client, times(1)).getMatchHistory(1000002L);
        assertThat(profileCache().get(1000001L)).isNotNull();
        assertThat(profileCache().get(1000002L)).isNotNull();
    }

    @Test
    void failureIsNotCached() {
        when(client.getMatchHistory(1000001L))
                .thenThrow(new DeadlockApiUnavailableException("busy", null, null))
                .thenReturn(List.of(entry()));

        assertThatThrownBy(() -> service.getProfile(1000001L)).isInstanceOf(DeadlockApiUnavailableException.class);
        assertThat(profileCache().get(1000001L)).isNull();
        assertThat(service.getProfile(1000001L).recentMatches()).isEqualTo(1);

        verify(client, times(2)).getMatchHistory(1000001L);
    }

    @Test
    void profileCacheIsRegisteredWithTenMinuteTtl() {
        assertThat(cacheManager.getCache("profileSummary")).isInstanceOf(CaffeineCache.class);
        Policy<Object, Object> policy = ((CaffeineCache) cacheManager.getCache(CacheConfig.PROFILE_SUMMARY))
                .getNativeCache().policy();
        assertThat(policy.expireAfterWrite()).hasValueSatisfying(
                expiration -> assertThat(expiration.getExpiresAfter()).isEqualTo(Duration.ofMinutes(10)));
        assertThat(policy.eviction()).hasValueSatisfying(
                eviction -> assertThat(eviction.getMaximum()).isEqualTo(1000L));
    }

    private Cache profileCache() {
        return cacheManager.getCache(CacheConfig.PROFILE_SUMMARY);
    }

    private static MatchHistoryEntry entry() {
        return new MatchHistoryEntry(1L, 1, 20, Instant.now().getEpochSecond(), 1800, 1, 1, 1, 1,
                5, 2, 7, 30000L, 150, 10, null);
    }
}
