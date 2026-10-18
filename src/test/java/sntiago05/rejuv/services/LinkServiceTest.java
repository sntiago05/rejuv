package sntiago05.rejuv.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.DeadlockApiException;
import sntiago05.rejuv.clients.DeadlockApiUnavailableException;
import sntiago05.rejuv.clients.dto.MatchHistoryEntry;
import sntiago05.rejuv.config.CacheConfig;
import sntiago05.rejuv.domain.LinkedAccount;
import sntiago05.rejuv.repositories.LinkedAccountRepository;
import sntiago05.rejuv.repositories.MatchAnalysisRepository;

class LinkServiceTest {

    private static final long DISCORD_USER_ID = 111L;
    private static final long ACCOUNT_ID = 1000001L;
    private static final List<MatchHistoryEntry> HISTORY = List.of(
            new MatchHistoryEntry(900000001L, 1, 20, 1700000000L, 1800, 4, 0, 0, 1, 5, 3, 7, 30000L, 150, 10, 50));

    private DeadlockApiClient client;
    private LinkedAccountRepository repository;
    private MatchAnalysisRepository matchAnalysisRepository;
    private CacheManager cacheManager;
    private Cache profileSummaryCache;
    private Cache matchHistoryCache;
    private Cache playerRankCache;
    private LinkService service;

    @BeforeEach
    void setUp() {
        client = mock(DeadlockApiClient.class);
        repository = mock(LinkedAccountRepository.class);
        matchAnalysisRepository = mock(MatchAnalysisRepository.class);
        cacheManager = mock(CacheManager.class);
        profileSummaryCache = mock(Cache.class);
        matchHistoryCache = mock(Cache.class);
        playerRankCache = mock(Cache.class);
        when(cacheManager.getCache(CacheConfig.PROFILE_SUMMARY)).thenReturn(profileSummaryCache);
        when(cacheManager.getCache(CacheConfig.DEADLOCK_MATCH_HISTORY)).thenReturn(matchHistoryCache);
        when(cacheManager.getCache(CacheConfig.DEADLOCK_PLAYER_RANK)).thenReturn(playerRankCache);
        service = new LinkService(client, repository, matchAnalysisRepository, cacheManager);
    }

    @Test
    void linkSavesAccountWhenHistoryExists() {
        when(client.getMatchHistory(ACCOUNT_ID)).thenReturn(HISTORY);

        Instant before = Instant.now();
        service.link(DISCORD_USER_ID, ACCOUNT_ID);
        Instant after = Instant.now();

        ArgumentCaptor<LinkedAccount> saved = ArgumentCaptor.forClass(LinkedAccount.class);
        verify(repository, times(1)).save(saved.capture());
        assertThat(saved.getValue().getDiscordUserId()).isEqualTo(DISCORD_USER_ID);
        assertThat(saved.getValue().getAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(saved.getValue().getLinkedAt()).isBetween(before, after);

        verify(profileSummaryCache).evict(ACCOUNT_ID);
        verify(matchHistoryCache).evict(ACCOUNT_ID);
        verify(playerRankCache).evict(ACCOUNT_ID);
    }

    @Test
    void linkWithEmptyHistoryThrowsAndSavesNothing() {
        when(client.getMatchHistory(ACCOUNT_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.link(DISCORD_USER_ID, ACCOUNT_ID))
                .isInstanceOf(NoMatchHistoryException.class);
        verify(repository, never()).save(any());
        verify(profileSummaryCache, never()).evict(any());
    }

    @Test
    void linkPropagatesUnavailableAndSavesNothing() {
        DeadlockApiUnavailableException unavailable = new DeadlockApiUnavailableException("busy", null, null);
        when(client.getMatchHistory(anyLong())).thenThrow(unavailable);

        assertThatThrownBy(() -> service.link(DISCORD_USER_ID, ACCOUNT_ID)).isSameAs(unavailable);
        verify(repository, never()).save(any());
        verify(profileSummaryCache, never()).evict(any());
    }

    @Test
    void linkPropagatesApiExceptionAndSavesNothing() {
        DeadlockApiException failure = new DeadlockApiException("bad body");
        when(client.getMatchHistory(anyLong())).thenThrow(failure);

        assertThatThrownBy(() -> service.link(DISCORD_USER_ID, ACCOUNT_ID)).isSameAs(failure);
        verify(repository, never()).save(any());
        verify(profileSummaryCache, never()).evict(any());
    }

    @Test
    void linkAgainToDifferentAccountEvictsBothOldAndNew() {
        when(client.getMatchHistory(anyLong())).thenReturn(HISTORY);
        when(repository.findById(DISCORD_USER_ID))
                .thenReturn(Optional.of(new LinkedAccount(DISCORD_USER_ID, ACCOUNT_ID, Instant.now())));

        service.link(DISCORD_USER_ID, 1000002L);

        ArgumentCaptor<LinkedAccount> saved = ArgumentCaptor.forClass(LinkedAccount.class);
        verify(repository, times(1)).save(saved.capture());
        assertThat(saved.getValue().getDiscordUserId()).isEqualTo(DISCORD_USER_ID);
        assertThat(saved.getValue().getAccountId()).isEqualTo(1000002L);

        verify(profileSummaryCache).evict(1000002L);
        verify(matchHistoryCache).evict(1000002L);
        verify(playerRankCache).evict(1000002L);
        verify(profileSummaryCache).evict(ACCOUNT_ID);
        verify(matchHistoryCache).evict(ACCOUNT_ID);
        verify(playerRankCache).evict(ACCOUNT_ID);
    }

    @Test
    void unlinkDeletesExistingLinkAndRemovesAnalysesIfNotLinked() {
        LinkedAccount link = new LinkedAccount(DISCORD_USER_ID, ACCOUNT_ID, Instant.now());
        when(repository.findById(DISCORD_USER_ID)).thenReturn(Optional.of(link));

        assertThat(service.unlink(DISCORD_USER_ID)).isTrue();

        InOrder inOrder = inOrder(repository, matchAnalysisRepository);
        inOrder.verify(repository, times(1)).deleteById(DISCORD_USER_ID);
        inOrder.verify(matchAnalysisRepository, times(1)).deleteByAccountIdIfNotLinked(ACCOUNT_ID);

        verify(profileSummaryCache).evict(ACCOUNT_ID);
        verify(matchHistoryCache).evict(ACCOUNT_ID);
        verify(playerRankCache).evict(ACCOUNT_ID);
    }

    @Test
    void unlinkWithoutLinkReturnsFalse() {
        when(repository.findById(DISCORD_USER_ID)).thenReturn(Optional.empty());

        assertThat(service.unlink(DISCORD_USER_ID)).isFalse();
        verify(repository, never()).deleteById(anyLong());
        verify(matchAnalysisRepository, never()).deleteByAccountIdIfNotLinked(anyLong());
        verify(profileSummaryCache, never()).evict(any());
        verify(matchHistoryCache, never()).evict(any());
        verify(playerRankCache, never()).evict(any());
    }
}
