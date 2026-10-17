package sntiago05.rejuv.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.DeadlockApiException;
import sntiago05.rejuv.clients.dto.MatchHistoryEntry;
import sntiago05.rejuv.domain.MatchFilter;
import sntiago05.rejuv.domain.MatchSummary;

class MatchSearchServiceTest {

    private static final long ACCOUNT_ID = 1000001L;

    private DeadlockApiClient deadlockApiClient;
    private GameAssets gameAssets;
    private MatchSearchService service;

    @BeforeEach
    void setUp() {
        deadlockApiClient = mock(DeadlockApiClient.class);
        gameAssets = mock(GameAssets.class);
        service = new MatchSearchService(deadlockApiClient, gameAssets);

        when(gameAssets.heroNames()).thenReturn(Map.of(
                1, "Infernus",
                2, "Seven",
                3, "Vindicta"
        ));
    }

    @Test
    void winRuleUsesMatchResultEqualsPlayerTeam() {
        List<MatchHistoryEntry> history = List.of(
                match(101L, 1, 1000L, 0, 0), // win
                match(102L, 1, 900L, 1, 1),  // win
                match(103L, 1, 800L, 1, 0),  // loss
                match(104L, 1, 700L, 0, 1)   // loss
        );
        when(deadlockApiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(history);

        List<MatchSummary> summaries = service.find(ACCOUNT_ID, MatchFilter.LATEST, 10);

        assertThat(summaries).hasSize(4);
        assertThat(summaries.get(0).win()).isTrue();
        assertThat(summaries.get(1).win()).isTrue();
        assertThat(summaries.get(2).win()).isFalse();
        assertThat(summaries.get(3).win()).isFalse();
    }

    @Test
    void filtersByHero() {
        List<MatchHistoryEntry> history = List.of(
                match(101L, 1, 1000L, 0, 0),
                match(102L, 2, 900L, 0, 0),
                match(103L, 1, 800L, 1, 0)
        );
        when(deadlockApiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(history);

        List<MatchSummary> results = service.find(ACCOUNT_ID, new MatchFilter(null, 1, null), 10);

        assertThat(results).extracting(MatchSummary::matchId).containsExactly(101L, 103L);
    }

    @Test
    void filtersByResult() {
        List<MatchHistoryEntry> history = List.of(
                match(101L, 1, 1000L, 0, 0), // win
                match(102L, 2, 900L, 1, 0),  // loss
                match(103L, 3, 800L, 0, 0)   // win
        );
        when(deadlockApiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(history);

        List<MatchSummary> wins = service.find(ACCOUNT_ID, new MatchFilter(null, null, true), 10);
        List<MatchSummary> losses = service.find(ACCOUNT_ID, new MatchFilter(null, null, false), 10);

        assertThat(wins).extracting(MatchSummary::matchId).containsExactly(101L, 103L);
        assertThat(losses).extracting(MatchSummary::matchId).containsExactly(102L);
    }

    @Test
    void filtersByBothHeroAndResult() {
        List<MatchHistoryEntry> history = List.of(
                match(101L, 1, 1000L, 0, 0), // Infernus Win
                match(102L, 1, 900L, 1, 0),  // Infernus Loss
                match(103L, 2, 800L, 0, 0)   // Seven Win
        );
        when(deadlockApiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(history);

        List<MatchSummary> results = service.find(ACCOUNT_ID, new MatchFilter(null, 1, true), 10);

        assertThat(results).extracting(MatchSummary::matchId).containsExactly(101L);
    }

    @Test
    void filtersByExactMatchId() {
        List<MatchHistoryEntry> history = List.of(
                match(101L, 1, 1000L, 0, 0),
                match(102L, 2, 900L, 0, 0),
                match(103L, 3, 800L, 0, 0)
        );
        when(deadlockApiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(history);

        List<MatchSummary> found = service.find(ACCOUNT_ID, new MatchFilter(102L, null, null), 10);
        Optional<MatchHistoryEntry> selected = service.select(ACCOUNT_ID, new MatchFilter(102L, null, null));

        assertThat(found).extracting(MatchSummary::matchId).containsExactly(102L);
        assertThat(selected).isPresent().hasValueSatisfying(m -> assertThat(m.matchId()).isEqualTo(102L));
    }

    @Test
    void newestFirstAndLimit() {
        List<MatchHistoryEntry> history = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            history.add(match(200L - i, 1, 2000L - i * 10, 0, 0));
        }
        when(deadlockApiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(history);

        List<MatchSummary> summaries = service.find(ACCOUNT_ID, MatchFilter.LATEST, 10);

        assertThat(summaries).hasSize(10);
        assertThat(summaries.get(0).matchId()).isEqualTo(200L);
        assertThat(summaries.get(9).matchId()).isEqualTo(191L);
    }

    @Test
    void selectPicksNewestMatchPassingFilter() {
        List<MatchHistoryEntry> history = List.of(
                match(103L, 2, 1000L, 0, 0), // Seven Win
                match(102L, 1, 900L, 0, 0),  // Infernus Win (newest Infernus)
                match(101L, 1, 800L, 0, 0)   // Infernus Win
        );
        when(deadlockApiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(history);

        Optional<MatchHistoryEntry> selected = service.select(ACCOUNT_ID, new MatchFilter(null, 1, null));

        assertThat(selected).isPresent();
        assertThat(selected.get().matchId()).isEqualTo(102L);
    }

    @Test
    void exactMatchIdNotInHistoryReturnsEmpty() {
        List<MatchHistoryEntry> history = List.of(match(101L, 1, 1000L, 0, 0));
        when(deadlockApiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(history);

        List<MatchSummary> found = service.find(ACCOUNT_ID, new MatchFilter(999L, null, null), 10);
        Optional<MatchHistoryEntry> selected = service.select(ACCOUNT_ID, new MatchFilter(999L, null, null));

        assertThat(found).isEmpty();
        assertThat(selected).isEmpty();
    }

    @Test
    void heroNameNullForUnknownHero() {
        List<MatchHistoryEntry> history = List.of(match(101L, 999, 1000L, 0, 0));
        when(deadlockApiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(history);

        List<MatchSummary> summaries = service.find(ACCOUNT_ID, MatchFilter.LATEST, 10);

        assertThat(summaries).hasSize(1);
        assertThat(summaries.getFirst().heroName()).isNull();
        assertThat(summaries.getFirst().heroId()).isEqualTo(999);
    }

    @Test
    void emptyHistoryReturnsEmptyListAndEmptyOptional() {
        when(deadlockApiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(List.of());

        assertThat(service.find(ACCOUNT_ID, MatchFilter.LATEST, 10)).isEmpty();
        assertThat(service.select(ACCOUNT_ID, MatchFilter.LATEST)).isEmpty();
    }

    @Test
    void exceptionsPropagate() {
        when(deadlockApiClient.getMatchHistory(ACCOUNT_ID))
                .thenThrow(new DeadlockApiException("API error"));

        assertThatThrownBy(() -> service.find(ACCOUNT_ID, MatchFilter.LATEST, 10))
                .isInstanceOf(DeadlockApiException.class);
        assertThatThrownBy(() -> service.select(ACCOUNT_ID, MatchFilter.LATEST))
                .isInstanceOf(DeadlockApiException.class);
    }

    @Test
    void twoHundredEntryHistoryProcessedOnce() {
        List<MatchHistoryEntry> history = new ArrayList<>(200);
        for (int i = 0; i < 200; i++) {
            history.add(match(3000L - i, 1, 3000L - i, 0, 0));
        }
        when(deadlockApiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(history);

        List<MatchSummary> result = service.find(ACCOUNT_ID, MatchFilter.LATEST, 10);

        assertThat(result).hasSize(10);
        verify(deadlockApiClient, times(1)).getMatchHistory(ACCOUNT_ID);
        verify(gameAssets, times(1)).heroNames();
    }

    private static MatchHistoryEntry match(long matchId, int heroId, long startTime, int matchResult, int playerTeam) {
        return new MatchHistoryEntry(
                matchId, heroId, 1, startTime, 1800, 4, matchResult, playerTeam,
                1, 5, 3, 7, 25000L, 120, 10, 70);
    }
}
