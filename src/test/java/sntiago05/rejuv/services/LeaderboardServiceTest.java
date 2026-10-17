package sntiago05.rejuv.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.DeadlockApiException;
import sntiago05.rejuv.clients.DeadlockApiUnavailableException;
import sntiago05.rejuv.clients.dto.AccountRank;
import sntiago05.rejuv.clients.dto.RankAsset;
import sntiago05.rejuv.domain.LeaderboardEntry;
import sntiago05.rejuv.domain.LinkedAccount;
import sntiago05.rejuv.domain.LinkedMember;
import sntiago05.rejuv.domain.ServerLeaderboard;
import sntiago05.rejuv.repositories.LinkedAccountRepository;

class LeaderboardServiceTest {

    private LinkedAccountRepository repository;
    private DeadlockApiClient deadlockApiClient;
    private LeaderboardService service;

    @BeforeEach
    void setUp() {
        repository = mock(LinkedAccountRepository.class);
        deadlockApiClient = mock(DeadlockApiClient.class);
        service = new LeaderboardService(repository, deadlockApiClient);
    }

    @Test
    void findCandidatesRequestsPageZeroOfSize500SortedByLinkedAtDesc() {
        Instant now = Instant.now();
        List<LinkedAccount> accounts = List.of(
                new LinkedAccount(111L, 1000001L, now),
                new LinkedAccount(222L, 1000002L, now.minusSeconds(10)));
        when(repository.findAll(any(PageRequest.class)))
                .thenReturn(new PageImpl<>(accounts));

        List<LinkedMember> candidates = service.findCandidates();

        ArgumentCaptor<PageRequest> pageRequestCaptor = ArgumentCaptor.forClass(PageRequest.class);
        verify(repository).findAll(pageRequestCaptor.capture());
        PageRequest pageRequest = pageRequestCaptor.getValue();
        assertThat(pageRequest.getPageNumber()).isZero();
        assertThat(pageRequest.getPageSize()).isEqualTo(LeaderboardService.MAX_CANDIDATES);
        assertThat(pageRequest.getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "linkedAt"));

        assertThat(candidates).containsExactly(
                new LinkedMember(111L, 1000001L),
                new LinkedMember(222L, 1000002L));
    }

    @Test
    void buildLeaderboardWithEmptyListMakesNoApiCall() {
        ServerLeaderboard leaderboard = service.buildLeaderboard(List.of());

        assertThat(leaderboard.entries()).isEmpty();
        assertThat(leaderboard.rankedCount()).isZero();
        assertThat(leaderboard.unrankedCount()).isZero();
        verify(deadlockApiClient, never()).getPlayerRanks(any());
        verify(deadlockApiClient, never()).getRanks();
    }

    @Test
    void buildLeaderboardPassesDistinctSortedImmutableIdsToClient() {
        List<LinkedMember> members = List.of(
                new LinkedMember(111L, 300L),
                new LinkedMember(222L, 100L),
                new LinkedMember(333L, 300L)); // Two users linked to account 300L

        when(deadlockApiClient.getPlayerRanks(any()))
                .thenReturn(Map.of(
                        100L, new AccountRank(100L, 50, 5, 0),
                        300L, new AccountRank(300L, 70, 7, 0)));
        when(deadlockApiClient.getRanks()).thenReturn(List.of(
                new RankAsset(5, "Archon"),
                new RankAsset(7, "Oracle")));

        ServerLeaderboard leaderboard = service.buildLeaderboard(members);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(deadlockApiClient).getPlayerRanks(captor.capture());
        List<Long> passedIds = captor.getValue();

        assertThat(passedIds).containsExactly(100L, 300L);
        assertThatThrownBy(() -> passedIds.add(999L)).isInstanceOf(UnsupportedOperationException.class);

        // Two users linked to same account both get entries
        assertThat(leaderboard.entries()).hasSize(3);
        assertThat(leaderboard.rankedCount()).isEqualTo(3);
        assertThat(leaderboard.unrankedCount()).isZero();
    }

    @Test
    void entriesAreOrderedByBadgeDescThenDiscordUserIdAsc() {
        List<LinkedMember> members = List.of(
                new LinkedMember(300L, 3L),
                new LinkedMember(100L, 1L),
                new LinkedMember(200L, 2L));

        when(deadlockApiClient.getPlayerRanks(any())).thenReturn(Map.of(
                1L, new AccountRank(1L, 80, 8, 0),
                2L, new AccountRank(2L, 80, 8, 0),
                3L, new AccountRank(3L, 90, 9, 0)));
        when(deadlockApiClient.getRanks()).thenReturn(List.of(
                new RankAsset(8, "Phantom"),
                new RankAsset(9, "Ascendant")));

        ServerLeaderboard leaderboard = service.buildLeaderboard(members);

        assertThat(leaderboard.entries()).extracting(LeaderboardEntry::discordUserId)
                .containsExactly(300L, 100L, 200L);
    }

    @Test
    void absentAccountsAndBadgeZeroAreCountedAsUnrankedAndNotListed() {
        List<LinkedMember> members = List.of(
                new LinkedMember(100L, 1L), // ranked
                new LinkedMember(200L, 2L), // badge 0
                new LinkedMember(300L, 3L)); // absent from response

        when(deadlockApiClient.getPlayerRanks(any())).thenReturn(Map.of(
                1L, new AccountRank(1L, 74, 7, 4),
                2L, new AccountRank(2L, 0, 0, 0)));
        when(deadlockApiClient.getRanks()).thenReturn(List.of(new RankAsset(7, "Archon")));

        ServerLeaderboard leaderboard = service.buildLeaderboard(members);

        assertThat(leaderboard.entries()).hasSize(1);
        assertThat(leaderboard.entries().get(0).discordUserId()).isEqualTo(100L);
        assertThat(leaderboard.rankedCount()).isEqualTo(1);
        assertThat(leaderboard.unrankedCount()).isEqualTo(2);
    }

    @Test
    void limitsEntriesToTop10WhileReportingTotalRankedCount() {
        List<LinkedMember> members = LongStream.rangeClosed(1, 15)
                .mapToObj(i -> new LinkedMember(i, i))
                .toList();

        Map<Long, AccountRank> ranks = members.stream()
                .collect(Collectors.toMap(
                        LinkedMember::accountId,
                        m -> new AccountRank(m.accountId(), (int) (m.accountId() * 10), (int) m.accountId(), 0)));

        when(deadlockApiClient.getPlayerRanks(any())).thenReturn(ranks);
        when(deadlockApiClient.getRanks()).thenReturn(List.of());

        ServerLeaderboard leaderboard = service.buildLeaderboard(members);

        assertThat(leaderboard.entries()).hasSize(10);
        assertThat(leaderboard.rankedCount()).isEqualTo(15);
        assertThat(leaderboard.unrankedCount()).isZero();
        assertThat(leaderboard.entries().get(0).badge()).isEqualTo(150);
        assertThat(leaderboard.entries().get(9).badge()).isEqualTo(60);
    }

    @Test
    void rankNamesAreResolvedAndNullWhenTierNotInRanksAsset() {
        List<LinkedMember> members = List.of(
                new LinkedMember(100L, 1L),
                new LinkedMember(200L, 2L));

        when(deadlockApiClient.getPlayerRanks(any())).thenReturn(Map.of(
                1L, new AccountRank(1L, 74, 7, 4),
                2L, new AccountRank(2L, 999, 99, 9)));
        when(deadlockApiClient.getRanks()).thenReturn(List.of(new RankAsset(7, "Archon")));

        ServerLeaderboard leaderboard = service.buildLeaderboard(members);

        assertThat(leaderboard.entries()).hasSize(2);
        LeaderboardEntry entry1 = leaderboard.entries().get(1); // badge 74
        LeaderboardEntry entry2 = leaderboard.entries().get(0); // badge 999
        assertThat(entry1.rankName()).isEqualTo("Archon");
        assertThat(entry2.rankName()).isNull();
    }

    @Test
    void unavailableExceptionPropagates() {
        List<LinkedMember> members = List.of(new LinkedMember(100L, 1L));
        when(deadlockApiClient.getPlayerRanks(any())).thenThrow(new DeadlockApiUnavailableException("busy", null, null));

        assertThatThrownBy(() -> service.buildLeaderboard(members))
                .isInstanceOf(DeadlockApiUnavailableException.class);
    }

    @Test
    void apiExceptionPropagates() {
        List<LinkedMember> members = List.of(new LinkedMember(100L, 1L));
        when(deadlockApiClient.getPlayerRanks(any())).thenThrow(new DeadlockApiException("internal error"));

        assertThatThrownBy(() -> service.buildLeaderboard(members))
                .isInstanceOf(DeadlockApiException.class);
    }
}
