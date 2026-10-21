package sntiago05.rejuv.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.DeadlockApiException;
import sntiago05.rejuv.clients.DeadlockApiUnavailableException;
import sntiago05.rejuv.clients.dto.EnemyStat;
import sntiago05.rejuv.services.NemesisService.NemesisLine;
import sntiago05.rejuv.services.NemesisService.NemesisSummary;

class NemesisServiceTest {

    private static final long ACCOUNT_ID = 1000001L;
    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private DeadlockApiClient deadlockApiClient;
    private NemesisService nemesisService;
    private long expectedWindowTimestamp;

    @BeforeEach
    void setUp() {
        deadlockApiClient = mock(DeadlockApiClient.class);
        nemesisService = new NemesisService(deadlockApiClient, clock);
        expectedWindowTimestamp = ServiceSupport.startOfUtcDayMinus(clock, NemesisService.WINDOW);
    }

    @Test
    void nemesisOrderingAndMinimumSample() {
        // Opponents:
        // 1. Account self: 10 matches, 0 wins -> must be ignored
        // 2. 2000001: 4 matches, 0 wins (0%) -> ignored (< 5 matches)
        // 3. 2000002: 10 matches, 2 wins (20%)
        // 4. 2000003: 5 matches, 0 wins (0%)
        // 5. 2000004: 10 matches, 0 wins (0%) -> same win rate as 2000003, but more matches (10 vs 5), so worse!
        // 6. 2000005: 6 matches, 1 win (16.67%)
        // 7. 2000006: 8 matches, 4 wins (50%)
        // 8. 2000007: 5 matches, 1 win (20%) -> same win rate as 2000002 (20%), but fewer matches (5 vs 10), so 2000002 is worse
        List<EnemyStat> stats = List.of(
                new EnemyStat(ACCOUNT_ID, 10, 0),
                new EnemyStat(2000001L, 4, 0),
                new EnemyStat(2000002L, 10, 2),
                new EnemyStat(2000003L, 5, 0),
                new EnemyStat(2000004L, 10, 0),
                new EnemyStat(2000005L, 6, 1),
                new EnemyStat(2000006L, 8, 4),
                new EnemyStat(2000007L, 5, 1)
        );
        when(deadlockApiClient.getEnemyStats(eq(ACCOUNT_ID), eq(expectedWindowTimestamp))).thenReturn(stats);
        when(deadlockApiClient.getPlayerNames(List.of(2000002L, 2000003L, 2000004L, 2000005L, 2000007L)))
                .thenReturn(Map.of(2000004L, "Viper", 2000003L, "Kobra"));

        NemesisSummary summary = nemesisService.nemeses(ACCOUNT_ID);

        // Expected worst 5:
        // 1. 2000004: 0% win, 10 matches
        // 2. 2000003: 0% win, 5 matches
        // 3. 2000005: 16.67% win, 6 matches
        // 4. 2000002: 20% win, 10 matches
        // 5. 2000007: 20% win, 5 matches
        assertThat(summary.nemeses()).hasSize(5);

        NemesisLine first = summary.nemeses().get(0);
        assertThat(first.accountId()).isEqualTo(2000004L);
        assertThat(first.name()).isEqualTo("Viper");
        assertThat(first.matches()).isEqualTo(10L);
        assertThat(first.wins()).isZero();
        assertThat(first.winRate()).isEqualTo(0.0);

        NemesisLine second = summary.nemeses().get(1);
        assertThat(second.accountId()).isEqualTo(2000003L);
        assertThat(second.name()).isEqualTo("Kobra");
        assertThat(second.matches()).isEqualTo(5L);
        assertThat(second.wins()).isZero();

        NemesisLine third = summary.nemeses().get(2);
        assertThat(third.accountId()).isEqualTo(2000005L);
        assertThat(third.name()).isNull();
        assertThat(third.matches()).isEqualTo(6L);

        NemesisLine fourth = summary.nemeses().get(3);
        assertThat(fourth.accountId()).isEqualTo(2000002L);
        assertThat(fourth.matches()).isEqualTo(10L);

        NemesisLine fifth = summary.nemeses().get(4);
        assertThat(fifth.accountId()).isEqualTo(2000007L);
        assertThat(fifth.matches()).isEqualTo(5L);
    }

    @Test
    void emptyStatsReturnsEmptySummaryWithoutCallingNames() {
        when(deadlockApiClient.getEnemyStats(eq(ACCOUNT_ID), eq(expectedWindowTimestamp))).thenReturn(List.of());

        NemesisSummary summary = nemesisService.nemeses(ACCOUNT_ID);

        assertThat(summary.nemeses()).isEmpty();
        verify(deadlockApiClient, never()).getPlayerNames(anyList());
    }

    @Test
    void namesFailureDoesNotFailSummary() {
        when(deadlockApiClient.getEnemyStats(eq(ACCOUNT_ID), eq(expectedWindowTimestamp))).thenReturn(List.of(
                new EnemyStat(2000001L, 6, 1)
        ));
        when(deadlockApiClient.getPlayerNames(anyList()))
                .thenThrow(new DeadlockApiUnavailableException("busy", null, null));

        NemesisSummary summary = nemesisService.nemeses(ACCOUNT_ID);

        assertThat(summary.nemeses()).hasSize(1);
        assertThat(summary.nemeses().get(0).accountId()).isEqualTo(2000001L);
        assertThat(summary.nemeses().get(0).name()).isNull();
    }

    @Test
    void enemyStatsApiFailurePropagates() {
        when(deadlockApiClient.getEnemyStats(eq(ACCOUNT_ID), eq(expectedWindowTimestamp)))
                .thenThrow(new DeadlockApiException("API down", null));

        assertThatThrownBy(() -> nemesisService.nemeses(ACCOUNT_ID))
                .isInstanceOf(DeadlockApiException.class);
    }
}
