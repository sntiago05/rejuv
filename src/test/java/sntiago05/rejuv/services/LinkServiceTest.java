package sntiago05.rejuv.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.DeadlockApiException;
import sntiago05.rejuv.clients.DeadlockApiUnavailableException;
import sntiago05.rejuv.clients.dto.MatchHistoryEntry;
import sntiago05.rejuv.domain.LinkedAccount;
import sntiago05.rejuv.repositories.LinkedAccountRepository;

class LinkServiceTest {

    private static final long DISCORD_USER_ID = 111L;
    private static final long ACCOUNT_ID = 1000001L;
    private static final List<MatchHistoryEntry> HISTORY = List.of(
            new MatchHistoryEntry(900000001L, 1, 20, 1700000000L, 1800, 4, 0, 0, 1, 5, 3, 7, 30000L, 150, 10, 50));

    private DeadlockApiClient client;
    private LinkedAccountRepository repository;
    private LinkService service;

    @BeforeEach
    void setUp() {
        client = mock(DeadlockApiClient.class);
        repository = mock(LinkedAccountRepository.class);
        service = new LinkService(client, repository);
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
    }

    @Test
    void linkWithEmptyHistoryThrowsAndSavesNothing() {
        when(client.getMatchHistory(ACCOUNT_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.link(DISCORD_USER_ID, ACCOUNT_ID))
                .isInstanceOf(NoMatchHistoryException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void linkPropagatesUnavailableAndSavesNothing() {
        DeadlockApiUnavailableException unavailable = new DeadlockApiUnavailableException("busy", null, null);
        when(client.getMatchHistory(anyLong())).thenThrow(unavailable);

        assertThatThrownBy(() -> service.link(DISCORD_USER_ID, ACCOUNT_ID)).isSameAs(unavailable);
        verify(repository, never()).save(any());
    }

    @Test
    void linkPropagatesApiExceptionAndSavesNothing() {
        DeadlockApiException failure = new DeadlockApiException("bad body");
        when(client.getMatchHistory(anyLong())).thenThrow(failure);

        assertThatThrownBy(() -> service.link(DISCORD_USER_ID, ACCOUNT_ID)).isSameAs(failure);
        verify(repository, never()).save(any());
    }

    @Test
    void linkAgainSavesTheNewAccount() {
        when(client.getMatchHistory(anyLong())).thenReturn(HISTORY);

        service.link(DISCORD_USER_ID, ACCOUNT_ID);
        service.link(DISCORD_USER_ID, 1000002L);

        ArgumentCaptor<LinkedAccount> saved = ArgumentCaptor.forClass(LinkedAccount.class);
        verify(repository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(1).getDiscordUserId()).isEqualTo(DISCORD_USER_ID);
        assertThat(saved.getAllValues().get(1).getAccountId()).isEqualTo(1000002L);
    }

    @Test
    void unlinkDeletesExistingLink() {
        when(repository.existsById(DISCORD_USER_ID)).thenReturn(true);

        assertThat(service.unlink(DISCORD_USER_ID)).isTrue();
        verify(repository, times(1)).deleteById(DISCORD_USER_ID);
    }

    @Test
    void unlinkWithoutLinkReturnsFalse() {
        when(repository.existsById(DISCORD_USER_ID)).thenReturn(false);

        assertThat(service.unlink(DISCORD_USER_ID)).isFalse();
        verify(repository, never()).deleteById(anyLong());
    }
}
