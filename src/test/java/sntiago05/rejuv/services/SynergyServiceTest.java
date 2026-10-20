package sntiago05.rejuv.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.DeadlockApiUnavailableException;
import sntiago05.rejuv.clients.dto.HeroSynergyStat;
import sntiago05.rejuv.services.SynergyService.Partner;
import sntiago05.rejuv.services.SynergyService.SynergyResult;

class SynergyServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC);
    private static final long EXPECTED_TIMESTAMP = ServiceSupport.startOfUtcDayMinus(FIXED_CLOCK, ServiceSupport.WINDOW);

    private DeadlockApiClient deadlockApiClient;
    private SynergyService synergyService;

    @BeforeEach
    void setUp() {
        deadlockApiClient = mock(DeadlockApiClient.class);
        synergyService = new SynergyService(deadlockApiClient, FIXED_CLOCK);
    }

    @Test
    void rejectsInvalidRankTier() {
        assertThatThrownBy(() -> synergyService.forHero(1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> synergyService.forHero(1, 12))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void passesRankTierAndTimestampToClient() {
        when(deadlockApiClient.getHeroSynergyStats(eq(5), eq(EXPECTED_TIMESTAMP))).thenReturn(List.of());

        SynergyResult result = synergyService.forHero(1, 5);

        assertThat(result.heroId()).isEqualTo(1);
        assertThat(result.best()).isEmpty();
        assertThat(result.worst()).isEmpty();
        verify(deadlockApiClient).getHeroSynergyStats(5, EXPECTED_TIMESTAMP);
    }

    @Test
    void filtersOutPairsBelowMinimumMatches() {
        // Hero 2 has 49 matches (< 50 MIN_PAIR_MATCHES), Hero 3 has 50 matches (>= 50)
        List<HeroSynergyStat> stats = List.of(
                new HeroSynergyStat(1, 2, 30, 49),
                new HeroSynergyStat(1, 3, 30, 50)
        );
        when(deadlockApiClient.getHeroSynergyStats(any(), anyLong())).thenReturn(stats);

        SynergyResult result = synergyService.forHero(1, null);

        assertThat(result.best()).hasSize(1);
        assertThat(result.best().getFirst().heroId()).isEqualTo(3);
        assertThat(result.worst()).hasSize(1);
        assertThat(result.worst().getFirst().heroId()).isEqualTo(3);
    }

    @Test
    void handlesBothPairOrdersAndCalculatesShrunkWinRate() {
        // For hero 1:
        // Partner 2: heroId1=1, heroId2=2. Wins=60, matches=100. Shrunk = (60 + 25) / (100 + 50) = 85/150 = 0.56667
        // Partner 3: heroId1=3, heroId2=1. Wins=40, matches=100. Shrunk = (40 + 25) / (100 + 50) = 65/150 = 0.43333
        List<HeroSynergyStat> stats = List.of(
                new HeroSynergyStat(1, 2, 60, 100),
                new HeroSynergyStat(3, 1, 40, 100)
        );
        when(deadlockApiClient.getHeroSynergyStats(any(), anyLong())).thenReturn(stats);

        SynergyResult result = synergyService.forHero(1, null);

        assertThat(result.best()).hasSize(2);
        assertThat(result.best().get(0).heroId()).isEqualTo(2);
        assertThat(result.best().get(0).winRate()).isCloseTo(85.0 / 150.0, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(result.best().get(1).heroId()).isEqualTo(3);
        assertThat(result.best().get(1).winRate()).isCloseTo(65.0 / 150.0, org.assertj.core.data.Offset.offset(0.0001));

        assertThat(result.worst()).hasSize(2);
        assertThat(result.worst().get(0).heroId()).isEqualTo(3);
        assertThat(result.worst().get(1).heroId()).isEqualTo(2);
    }

    @Test
    void limitsBestAndWorstToTop5() {
        // 7 partners with different win rates
        List<HeroSynergyStat> stats = List.of(
                new HeroSynergyStat(1, 2, 80, 100), // (80+25)/150 = 105/150 = 0.70
                new HeroSynergyStat(1, 3, 75, 100), // 100/150 = 0.667
                new HeroSynergyStat(1, 4, 70, 100), // 95/150 = 0.633
                new HeroSynergyStat(1, 5, 65, 100), // 90/150 = 0.60
                new HeroSynergyStat(1, 6, 60, 100), // 85/150 = 0.567
                new HeroSynergyStat(1, 7, 50, 100), // 75/150 = 0.50
                new HeroSynergyStat(1, 8, 30, 100)  // 55/150 = 0.367
        );
        when(deadlockApiClient.getHeroSynergyStats(any(), anyLong())).thenReturn(stats);

        SynergyResult result = synergyService.forHero(1, null);

        assertThat(result.best()).hasSize(5);
        assertThat(result.best().stream().map(Partner::heroId).toList())
                .containsExactly(2, 3, 4, 5, 6);

        assertThat(result.worst()).hasSize(5);
        assertThat(result.worst().stream().map(Partner::heroId).toList())
                .containsExactly(8, 7, 6, 5, 4);
    }

    @Test
    void propagatesDeadlockApiUnavailableException() {
        when(deadlockApiClient.getHeroSynergyStats(any(), anyLong()))
                .thenThrow(new DeadlockApiUnavailableException("503 busy", null, null));

        assertThatThrownBy(() -> synergyService.forHero(1, null))
                .isInstanceOf(DeadlockApiUnavailableException.class);
    }
}
