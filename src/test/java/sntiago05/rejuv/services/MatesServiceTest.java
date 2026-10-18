package sntiago05.rejuv.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.DeadlockApiException;
import sntiago05.rejuv.clients.DeadlockApiUnavailableException;
import sntiago05.rejuv.clients.dto.MateStat;
import sntiago05.rejuv.domain.MatesSummary;
import sntiago05.rejuv.domain.Teammate;

class MatesServiceTest {

    private static final long ACCOUNT_ID = 1000001L;
    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private DeadlockApiClient deadlockApiClient;
    private MatesService matesService;
    private long expectedWindowTimestamp;

    @BeforeEach
    void setUp() {
        deadlockApiClient = mock(DeadlockApiClient.class);
        matesService = new MatesService(deadlockApiClient, CLOCK);
        expectedWindowTimestamp = ServiceSupport.startOfUtcDayMinus(CLOCK, Duration.ofDays(90));
    }

    @Test
    void playerThemselfIsIgnored() {
        when(deadlockApiClient.getMateStats(eq(ACCOUNT_ID), eq(expectedWindowTimestamp))).thenReturn(List.of(
                new MateStat(ACCOUNT_ID, 20, 15),
                new MateStat(2000002L, 10, 8)));

        MatesSummary summary = matesService.getMates(ACCOUNT_ID);

        assertThat(summary.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(summary.teammates()).hasSize(1);
        assertThat(summary.teammates().get(0).accountId()).isEqualTo(2000002L);
    }

    @Test
    void belowMinMatchesTogetherIgnored() {
        when(deadlockApiClient.getMateStats(eq(ACCOUNT_ID), eq(expectedWindowTimestamp))).thenReturn(List.of(
                new MateStat(2000001L, 4, 4),
                new MateStat(2000002L, 5, 3)));

        MatesSummary summary = matesService.getMates(ACCOUNT_ID);

        assertThat(summary.teammates()).hasSize(1);
        assertThat(summary.teammates().get(0).accountId()).isEqualTo(2000002L);
    }

    @Test
    void orderingByWinRateDescThenMatchesDescThenAccountIdAsc() {
        // Mate A: 10 matches, 8 wins = 80.0%, id 2000003
        // Mate B: 20 matches, 18 wins = 90.0%, id 2000001 -> 1st (90%)
        // Mate C: 10 matches, 7 wins = 70.0%, id 2000005 -> 5th (70%)
        // Mate D: 20 matches, 16 wins = 80.0%, id 2000004 -> 2nd (80%, 20 matches)
        // Mate E: 10 matches, 8 wins = 80.0%, id 2000002 -> 3rd (80%, 10 matches, lower id than A)
        when(deadlockApiClient.getMateStats(eq(ACCOUNT_ID), eq(expectedWindowTimestamp))).thenReturn(List.of(
                new MateStat(2000003L, 10, 8),
                new MateStat(2000001L, 20, 18),
                new MateStat(2000005L, 10, 7),
                new MateStat(2000004L, 20, 16),
                new MateStat(2000002L, 10, 8)));

        MatesSummary summary = matesService.getMates(ACCOUNT_ID);

        assertThat(summary.teammates()).extracting(Teammate::accountId)
                .containsExactly(2000001L, 2000004L, 2000002L, 2000003L, 2000005L);
        assertThat(summary.teammates().get(0)).isEqualTo(new Teammate(2000001L, 20, 18, 90.0));
    }

    @Test
    void top5Truncation() {
        when(deadlockApiClient.getMateStats(eq(ACCOUNT_ID), eq(expectedWindowTimestamp))).thenReturn(List.of(
                new MateStat(2000001L, 10, 10),
                new MateStat(2000002L, 10, 9),
                new MateStat(2000003L, 10, 8),
                new MateStat(2000004L, 10, 7),
                new MateStat(2000005L, 10, 6),
                new MateStat(2000006L, 10, 5),
                new MateStat(2000007L, 10, 4)));

        MatesSummary summary = matesService.getMates(ACCOUNT_ID);

        assertThat(summary.teammates()).hasSize(5);
        assertThat(summary.teammates()).extracting(Teammate::accountId)
                .containsExactly(2000001L, 2000002L, 2000003L, 2000004L, 2000005L);
    }

    @Test
    void emptyInputReturnsEmptySummary() {
        when(deadlockApiClient.getMateStats(eq(ACCOUNT_ID), eq(expectedWindowTimestamp))).thenReturn(List.of());

        MatesSummary summary = matesService.getMates(ACCOUNT_ID);

        assertThat(summary.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(summary.teammates()).isEmpty();
    }

    @Test
    void windowComputedFromInjectedClock() {
        matesService.getMates(ACCOUNT_ID);

        long expectedTs = ServiceSupport.startOfUtcDayMinus(CLOCK, Duration.ofDays(90));
        when(deadlockApiClient.getMateStats(ACCOUNT_ID, expectedTs)).thenReturn(List.of());
        matesService.getMates(ACCOUNT_ID);
    }

    @Test
    void exceptionsPropagate() {
        when(deadlockApiClient.getMateStats(eq(ACCOUNT_ID), eq(expectedWindowTimestamp)))
                .thenThrow(new DeadlockApiUnavailableException("busy", null, null));

        assertThatThrownBy(() -> matesService.getMates(ACCOUNT_ID))
                .isInstanceOf(DeadlockApiUnavailableException.class);

        when(deadlockApiClient.getMateStats(eq(ACCOUNT_ID), eq(expectedWindowTimestamp)))
                .thenThrow(new DeadlockApiException("error", null));

        assertThatThrownBy(() -> matesService.getMates(ACCOUNT_ID))
                .isInstanceOf(DeadlockApiException.class);
    }

    @Test
    void namesFetchedOnceForSortedDistinctTeammateIdsExcludingCaller() {
        when(deadlockApiClient.getMateStats(eq(ACCOUNT_ID), eq(expectedWindowTimestamp))).thenReturn(List.of(
                new MateStat(2000003L, 10, 8),
                new MateStat(ACCOUNT_ID, 30, 20),
                new MateStat(2000001L, 20, 18),
                new MateStat(2000002L, 10, 5)));
        when(deadlockApiClient.getPlayerNames(List.of(2000001L, 2000002L, 2000003L)))
                .thenReturn(Map.of(2000001L, "Alpha"));

        MatesSummary summary = matesService.getMates(ACCOUNT_ID);

        assertThat(summary.names()).containsOnly(Map.entry(2000001L, "Alpha"));
        assertThat(summary.teammates()).extracting(Teammate::accountId)
                .containsExactly(2000001L, 2000003L, 2000002L);
        verify(deadlockApiClient).getPlayerNames(List.of(2000001L, 2000002L, 2000003L));
    }

    @Test
    void noNamesCallWhenNoTeammates() {
        when(deadlockApiClient.getMateStats(eq(ACCOUNT_ID), eq(expectedWindowTimestamp))).thenReturn(List.of());

        MatesSummary summary = matesService.getMates(ACCOUNT_ID);

        assertThat(summary.names()).isEmpty();
        verify(deadlockApiClient, never()).getPlayerNames(anyList());
    }

    @Test
    void namesFailureGivesSummaryWithEmptyNames() {
        when(deadlockApiClient.getMateStats(eq(ACCOUNT_ID), eq(expectedWindowTimestamp)))
                .thenReturn(List.of(new MateStat(2000001L, 10, 8)));

        doThrow(new DeadlockApiUnavailableException("busy", null, null)).when(deadlockApiClient).getPlayerNames(anyList());
        MatesSummary unavailable = matesService.getMates(ACCOUNT_ID);

        doThrow(new DeadlockApiException("no profile", null)).when(deadlockApiClient).getPlayerNames(anyList());
        MatesSummary failed = matesService.getMates(ACCOUNT_ID);

        for (MatesSummary summary : List.of(unavailable, failed)) {
            assertThat(summary.teammates()).extracting(Teammate::accountId).containsExactly(2000001L);
            assertThat(summary.names()).isEmpty();
        }
    }

    @Test
    void illegalArgumentFromNamesCallIsNotSwallowed() {
        when(deadlockApiClient.getMateStats(eq(ACCOUNT_ID), eq(expectedWindowTimestamp)))
                .thenReturn(List.of(new MateStat(2000001L, 10, 8)));
        when(deadlockApiClient.getPlayerNames(anyList())).thenThrow(new IllegalArgumentException("bad"));

        assertThatThrownBy(() -> matesService.getMates(ACCOUNT_ID)).isInstanceOf(IllegalArgumentException.class);
    }
}
