package sntiago05.rejuv.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.DeadlockApiUnavailableException;
import sntiago05.rejuv.clients.dto.HeroAsset;
import sntiago05.rejuv.clients.dto.HeroCounterStat;
import sntiago05.rejuv.clients.dto.HeroSynergyStat;
import sntiago05.rejuv.services.DraftService.DraftRequest;
import sntiago05.rejuv.services.DraftService.DraftResult;
import sntiago05.rejuv.services.DraftService.PickSuggestion;

class DraftServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC);
    private static final long EXPECTED_TIMESTAMP = ServiceSupport.startOfUtcDayMinus(FIXED_CLOCK, ServiceSupport.WINDOW);

    private DeadlockApiClient deadlockApiClient;
    private GameAssets gameAssets;
    private DraftService draftService;

    @BeforeEach
    void setUp() {
        deadlockApiClient = mock(DeadlockApiClient.class);
        gameAssets = mock(GameAssets.class);
        draftService = new DraftService(deadlockApiClient, gameAssets, FIXED_CLOCK);
    }

    @Test
    void rejectsInvalidRankTier() {
        assertThatThrownBy(() -> draftService.estimate(new DraftRequest(List.of(1), List.of(2), 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> draftService.estimate(new DraftRequest(List.of(1), List.of(2), 12)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void callsSynergyAndCounterEndpointsSequentiallyWithSameWindowAndTier() {
        when(deadlockApiClient.getHeroSynergyStats(eq(7), eq(EXPECTED_TIMESTAMP))).thenReturn(List.of());
        when(deadlockApiClient.getHeroCounterStats(eq(7), eq(EXPECTED_TIMESTAMP))).thenReturn(List.of());
        when(gameAssets.namedHeroes()).thenReturn(List.of());

        draftService.estimate(new DraftRequest(List.of(1), List.of(2), 7));

        InOrder inOrder = inOrder(deadlockApiClient);
        inOrder.verify(deadlockApiClient).getHeroSynergyStats(7, EXPECTED_TIMESTAMP);
        inOrder.verify(deadlockApiClient).getHeroCounterStats(7, EXPECTED_TIMESTAMP);
    }

    @Test
    void emptyDraftYields50PercentAndLowConfidence() {
        when(deadlockApiClient.getHeroSynergyStats(any(), anyLong())).thenReturn(List.of());
        when(deadlockApiClient.getHeroCounterStats(any(), anyLong())).thenReturn(List.of());
        when(gameAssets.namedHeroes()).thenReturn(List.of());

        DraftResult result = draftService.estimate(new DraftRequest(List.of(), List.of(), null));

        assertThat(result.winProbability()).isEqualTo(0.50);
        assertThat(result.lowConfidence()).isTrue();
        assertThat(result.allyCount()).isEqualTo(0);
        assertThat(result.enemyCount()).isEqualTo(0);
    }

    @Test
    void evaluatesSynergyAndCounterStatsWithShrinkageAndLogOdds() {
        // Ally 1 and 2: synergy stat wins=60, matches=100 -> shrunk p = (60+25)/(100+50) = 85/150 = 0.566667
        // delta_synergy = log(85/65) ~ 0.26826
        List<HeroSynergyStat> synergyStats = List.of(
                new HeroSynergyStat(1, 2, 60, 100)
        );
        // Ally 1 vs Enemy 3: counter stat wins=70, matches=100 -> shrunk p = (70+25)/(100+50) = 95/150 = 0.633333
        // delta_counter = log(95/55) ~ 0.54654
        List<HeroCounterStat> counterStats = List.of(
                new HeroCounterStat(1, 3, 70, 100)
        );
        when(deadlockApiClient.getHeroSynergyStats(any(), anyLong())).thenReturn(synergyStats);
        when(deadlockApiClient.getHeroCounterStats(any(), anyLong())).thenReturn(counterStats);
        when(gameAssets.namedHeroes()).thenReturn(List.of());

        DraftResult result = draftService.estimate(new DraftRequest(List.of(1, 2), List.of(3), null));

        double expectedLogOdds = Math.log(85.0 / 65.0) + Math.log(95.0 / 55.0);
        double expectedProb = 1.0 / (1.0 + Math.exp(-expectedLogOdds));

        assertThat(result.winProbability()).isCloseTo(expectedProb, org.assertj.core.data.Offset.offset(0.0001));
        // Total qualifying pairs: 1 synergy + 1 counter = 2 (< 3) -> low confidence true
        assertThat(result.lowConfidence()).isTrue();
    }

    @Test
    void lowConfidenceFalseWhenAtLeastThreePairsQualify() {
        List<HeroSynergyStat> synergyStats = List.of(
                new HeroSynergyStat(1, 2, 50, 100),
                new HeroSynergyStat(1, 3, 50, 100),
                new HeroSynergyStat(2, 3, 50, 100)
        );
        when(deadlockApiClient.getHeroSynergyStats(any(), anyLong())).thenReturn(synergyStats);
        when(deadlockApiClient.getHeroCounterStats(any(), anyLong())).thenReturn(List.of());
        when(gameAssets.namedHeroes()).thenReturn(List.of());

        DraftResult result = draftService.estimate(new DraftRequest(List.of(1, 2, 3), List.of(), null));

        assertThat(result.winProbability()).isEqualTo(0.50);
        assertThat(result.lowConfidence()).isFalse();
    }

    @Test
    void ignoresPairsBelowMinimumSampleMatches() {
        // Synergy with 49 matches (< 50) and Counter with 49 matches (< 50)
        List<HeroSynergyStat> synergyStats = List.of(
                new HeroSynergyStat(1, 2, 40, 49)
        );
        List<HeroCounterStat> counterStats = List.of(
                new HeroCounterStat(1, 3, 40, 49)
        );
        when(deadlockApiClient.getHeroSynergyStats(any(), anyLong())).thenReturn(synergyStats);
        when(deadlockApiClient.getHeroCounterStats(any(), anyLong())).thenReturn(counterStats);
        when(gameAssets.namedHeroes()).thenReturn(List.of());

        DraftResult result = draftService.estimate(new DraftRequest(List.of(1, 2), List.of(3), null));

        // Both pairs ignored, so log-odds = 0 -> 50%
        assertThat(result.winProbability()).isEqualTo(0.50);
        assertThat(result.lowConfidence()).isTrue();
    }

    @Test
    void handlesReversePairOrderInBothSynergyAndCounterStats() {
        // Synergy stored with heroId1=2, heroId2=1
        List<HeroSynergyStat> synergyStats = List.of(
                new HeroSynergyStat(2, 1, 60, 100)
        );
        // Counter stored with heroId=3, enemyHeroId=1 (enemy vs ally). Ally 1 won 100 - 30 = 70 games.
        List<HeroCounterStat> counterStats = List.of(
                new HeroCounterStat(3, 1, 30, 100)
        );
        when(deadlockApiClient.getHeroSynergyStats(any(), anyLong())).thenReturn(synergyStats);
        when(deadlockApiClient.getHeroCounterStats(any(), anyLong())).thenReturn(counterStats);
        when(gameAssets.namedHeroes()).thenReturn(List.of());

        DraftResult result = draftService.estimate(new DraftRequest(List.of(1, 2), List.of(3), null));

        double expectedLogOdds = Math.log(85.0 / 65.0) + Math.log(95.0 / 55.0);
        double expectedProb = 1.0 / (1.0 + Math.exp(-expectedLogOdds));
        assertThat(result.winProbability()).isCloseTo(expectedProb, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    void clampsWinProbabilityBetween5And95Percent() {
        // Extreme positive synergy/counter
        List<HeroSynergyStat> synergyStats = List.of(
                new HeroSynergyStat(1, 2, 10000, 10000)
        );
        when(deadlockApiClient.getHeroSynergyStats(any(), anyLong())).thenReturn(synergyStats);
        when(deadlockApiClient.getHeroCounterStats(any(), anyLong())).thenReturn(List.of());
        when(gameAssets.namedHeroes()).thenReturn(List.of());

        DraftResult resultMax = draftService.estimate(new DraftRequest(List.of(1, 2), List.of(), null));
        assertThat(resultMax.winProbability()).isEqualTo(0.95);

        // Extreme negative
        List<HeroSynergyStat> synergyNegative = List.of(
                new HeroSynergyStat(1, 2, 0, 10000)
        );
        when(deadlockApiClient.getHeroSynergyStats(any(), anyLong())).thenReturn(synergyNegative);

        DraftResult resultMin = draftService.estimate(new DraftRequest(List.of(1, 2), List.of(), null));
        assertThat(resultMin.winProbability()).isEqualTo(0.05);
    }

    @Test
    void suggestionsExcludePickedHeroesAndRankTop3() {
        // Allies: 1, 2. Enemies: 3.
        // Candidates in gameAssets: 1 (picked ally), 3 (picked enemy), 4, 5, 6, 7
        when(deadlockApiClient.getHeroSynergyStats(any(), anyLong())).thenReturn(List.of(
                new HeroSynergyStat(1, 4, 80, 100), // strong synergy with 4
                new HeroSynergyStat(1, 5, 60, 100), // moderate with 5
                new HeroSynergyStat(1, 6, 50, 100), // neutral with 6
                new HeroSynergyStat(1, 7, 20, 100)  // bad with 7
        ));
        when(deadlockApiClient.getHeroCounterStats(any(), anyLong())).thenReturn(List.of());
        when(gameAssets.namedHeroes()).thenReturn(List.of(
                new HeroAsset(1, "hero1", "Hero1", null),
                new HeroAsset(2, "hero2", "Hero2", null),
                new HeroAsset(3, "hero3", "Hero3", null),
                new HeroAsset(4, "hero4", "Hero4", null),
                new HeroAsset(5, "hero5", "Hero5", null),
                new HeroAsset(6, "hero6", "Hero6", null),
                new HeroAsset(7, "hero7", "Hero7", null)
        ));

        DraftResult result = draftService.estimate(new DraftRequest(List.of(1, 2), List.of(3), null));

        assertThat(result.suggestions()).hasSize(3);
        // Excludes 1, 2, 3. Top 3 are 4, 5, 6
        assertThat(result.suggestions().stream().map(PickSuggestion::heroId).toList())
                .containsExactly(4, 5, 6);
        assertThat(result.suggestions().get(0).winProbability())
                .isGreaterThan(result.suggestions().get(1).winProbability());
        assertThat(result.suggestions().get(1).winProbability())
                .isGreaterThan(result.suggestions().get(2).winProbability());
    }

    @Test
    void givesNoSuggestionsWhen5AlliesProvided() {
        // Acceptance criterion: /draft with 5 allies and 6 enemies gives a probability and no suggestions
        when(deadlockApiClient.getHeroSynergyStats(any(), anyLong())).thenReturn(List.of());
        when(deadlockApiClient.getHeroCounterStats(any(), anyLong())).thenReturn(List.of());

        DraftResult result = draftService.estimate(
                new DraftRequest(List.of(1, 2, 3, 4, 5), List.of(6, 7, 8, 9, 10, 11), null));

        assertThat(result.allyCount()).isEqualTo(5);
        assertThat(result.enemyCount()).isEqualTo(6);
        assertThat(result.suggestions()).isEmpty();
    }

    @Test
    void propagatesDeadlockApiUnavailableException() {
        when(deadlockApiClient.getHeroSynergyStats(any(), anyLong()))
                .thenThrow(new DeadlockApiUnavailableException("503 busy", null, null));

        assertThatThrownBy(() -> draftService.estimate(new DraftRequest(List.of(1), List.of(2), null)))
                .isInstanceOf(DeadlockApiUnavailableException.class);
    }
}
