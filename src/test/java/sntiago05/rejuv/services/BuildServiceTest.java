package sntiago05.rejuv.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.DeadlockApiUnavailableException;
import sntiago05.rejuv.clients.dto.AbilityOrderStat;
import sntiago05.rejuv.clients.dto.HeroAsset;
import sntiago05.rejuv.clients.dto.ItemAsset;
import sntiago05.rejuv.clients.dto.ItemFlowNode;
import sntiago05.rejuv.clients.dto.ItemFlowStats;
import sntiago05.rejuv.domain.BuildItem;
import sntiago05.rejuv.domain.BuildPhase;
import sntiago05.rejuv.domain.HeroBuild;

class BuildServiceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-10-17T15:30:00Z");
    private static final long EXPECTED_TIMESTAMP = Instant.parse("2026-09-17T00:00:00Z").getEpochSecond();
    private static final HeroAsset HERO_INFERNUS = new HeroAsset(1, "hero_inferno", "Infernus");

    private DeadlockApiClient deadlockApiClient;
    private HeroResolver heroResolver;
    private Clock clock;
    private BuildService buildService;

    @BeforeEach
    void setUp() {
        deadlockApiClient = mock(DeadlockApiClient.class);
        heroResolver = mock(HeroResolver.class);
        clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        buildService = new BuildService(deadlockApiClient, heroResolver, clock);

        when(heroResolver.resolve("Infernus")).thenReturn(Optional.of(HERO_INFERNUS));
        when(deadlockApiClient.getItemFlowStats(anyInt(), any(), anyLong()))
                .thenReturn(new ItemFlowStats(List.of(), List.of()));
        when(deadlockApiClient.getAbilityOrderStats(anyInt(), any(), anyLong()))
                .thenReturn(List.of());
        when(deadlockApiClient.getItems()).thenReturn(List.of());
    }

    @Test
    void timestampIsStartOfUtcDayMinus30Days() {
        buildService.getBuild("Infernus", 7);

        verify(deadlockApiClient).getItemFlowStats(1, 7, EXPECTED_TIMESTAMP);
        verify(deadlockApiClient).getAbilityOrderStats(1, 7, EXPECTED_TIMESTAMP);
    }

    @Test
    void allRanksPassesNullTier() {
        HeroBuild build = buildService.getBuild("Infernus", null);

        verify(deadlockApiClient).getItemFlowStats(1, null, EXPECTED_TIMESTAMP);
        verify(deadlockApiClient).getAbilityOrderStats(1, null, EXPECTED_TIMESTAMP);
        assertThat(build.rankTier()).isNull();
    }

    @Test
    void invalidTierIsRejectedWithoutCalls() {
        assertThatThrownBy(() -> buildService.getBuild("Infernus", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> buildService.getBuild("Infernus", 12))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(heroResolver);
        verifyNoInteractions(deadlockApiClient);
    }

    @Test
    void unknownHeroThrowsAndMakesNoStatsCalls() {
        when(heroResolver.resolve("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> buildService.getBuild("unknown", 7))
                .isInstanceOf(HeroNotFoundException.class);

        verifyNoInteractions(deadlockApiClient);
    }

    @Test
    void callsFlowThenAbilitiesThenItemsSequentially() {
        buildService.getBuild("Infernus", 7);

        InOrder inOrder = inOrder(deadlockApiClient);
        inOrder.verify(deadlockApiClient).getItemFlowStats(1, 7, EXPECTED_TIMESTAMP);
        inOrder.verify(deadlockApiClient).getAbilityOrderStats(1, 7, EXPECTED_TIMESTAMP);
        inOrder.verify(deadlockApiClient).getItems();
    }

    @Test
    void phasesGroupByColumnAndAreOrdered() {
        when(deadlockApiClient.getItemFlowStats(1, null, EXPECTED_TIMESTAMP))
                .thenReturn(new ItemFlowStats(List.of(
                        new ItemFlowNode(1, 201, 30, 15),
                        new ItemFlowNode(0, 101, 40, 20)
                ), List.of(100L, 100L)));

        HeroBuild build = buildService.getBuild("Infernus", null);

        assertThat(build.phases()).hasSize(2);
        assertThat(build.phases().get(0).column()).isEqualTo(0);
        assertThat(build.phases().get(1).column()).isEqualTo(1);
    }

    @Test
    void itemsBelowMinMatchesAreDropped() {
        when(deadlockApiClient.getItemFlowStats(1, null, EXPECTED_TIMESTAMP))
                .thenReturn(new ItemFlowStats(List.of(
                        new ItemFlowNode(0, 101, 19, 10),
                        new ItemFlowNode(0, 102, 20, 10)
                ), List.of(100L)));

        HeroBuild build = buildService.getBuild("Infernus", null);

        assertThat(build.phases()).hasSize(1);
        List<BuildItem> items = build.phases().get(0).items();
        assertThat(items).extracting(BuildItem::itemId).containsExactly(102L);
    }

    @Test
    void itemsSortedByMatchesThenIdAndCappedAtFour() {
        when(deadlockApiClient.getItemFlowStats(1, null, EXPECTED_TIMESTAMP))
                .thenReturn(new ItemFlowStats(List.of(
                        new ItemFlowNode(0, 105, 30, 15),
                        new ItemFlowNode(0, 103, 50, 25),
                        new ItemFlowNode(0, 101, 100, 60),
                        new ItemFlowNode(0, 104, 50, 25),
                        new ItemFlowNode(0, 102, 80, 45)
                ), List.of(200L)));

        HeroBuild build = buildService.getBuild("Infernus", null);

        assertThat(build.phases()).hasSize(1);
        List<BuildItem> items = build.phases().get(0).items();
        assertThat(items).hasSize(4);
        assertThat(items).extracting(BuildItem::itemId).containsExactly(101L, 102L, 103L, 104L);
    }

    @Test
    void phasesCappedAtSix() {
        List<ItemFlowNode> nodes = List.of(
                new ItemFlowNode(0, 100, 25, 15),
                new ItemFlowNode(1, 101, 25, 15),
                new ItemFlowNode(2, 102, 25, 15),
                new ItemFlowNode(3, 103, 25, 15),
                new ItemFlowNode(4, 104, 25, 15),
                new ItemFlowNode(5, 105, 25, 15),
                new ItemFlowNode(6, 106, 25, 15),
                new ItemFlowNode(7, 107, 25, 15)
        );
        when(deadlockApiClient.getItemFlowStats(1, null, EXPECTED_TIMESTAMP))
                .thenReturn(new ItemFlowStats(nodes, List.of(100L, 100L, 100L, 100L, 100L, 100L, 100L, 100L)));

        HeroBuild build = buildService.getBuild("Infernus", null);

        assertThat(build.phases()).hasSize(6);
        assertThat(build.phases()).extracting(BuildPhase::column).containsExactly(0, 1, 2, 3, 4, 5);
    }

    @Test
    void emptyColumnsAreDropped() {
        when(deadlockApiClient.getItemFlowStats(1, null, EXPECTED_TIMESTAMP))
                .thenReturn(new ItemFlowStats(List.of(
                        new ItemFlowNode(0, 101, 10, 5), // < 20 matches, dropped
                        new ItemFlowNode(1, 102, 30, 15)  // >= 20 matches, kept
                ), List.of(100L, 100L)));

        HeroBuild build = buildService.getBuild("Infernus", null);

        assertThat(build.phases()).hasSize(1);
        assertThat(build.phases().get(0).column()).isEqualTo(1);
    }

    @Test
    void pickRateUsesReachedPerColumn() {
        when(deadlockApiClient.getItemFlowStats(1, null, EXPECTED_TIMESTAMP))
                .thenReturn(new ItemFlowStats(List.of(
                        new ItemFlowNode(0, 101, 250, 150)
                ), List.of(1000L)));

        HeroBuild build = buildService.getBuild("Infernus", null);

        BuildItem item = build.phases().get(0).items().get(0);
        assertThat(item.pickRatePercent()).isEqualTo(25.0);
    }

    @Test
    void missingReachedEntryGivesZeroPickRateAndKeepsItem() {
        when(deadlockApiClient.getItemFlowStats(1, null, EXPECTED_TIMESTAMP))
                .thenReturn(new ItemFlowStats(List.of(
                        new ItemFlowNode(1, 101, 50, 25)
                ), List.of(1000L))); // only column 0 in reachedPerColumn

        HeroBuild build = buildService.getBuild("Infernus", null);

        assertThat(build.phases()).hasSize(1);
        BuildItem item = build.phases().get(0).items().get(0);
        assertThat(item.pickRatePercent()).isEqualTo(0.0);
    }

    @Test
    void zeroReachedGivesZeroPickRate() {
        when(deadlockApiClient.getItemFlowStats(1, null, EXPECTED_TIMESTAMP))
                .thenReturn(new ItemFlowStats(List.of(
                        new ItemFlowNode(0, 101, 50, 25)
                ), List.of(0L)));

        HeroBuild build = buildService.getBuild("Infernus", null);

        BuildItem item = build.phases().get(0).items().get(0);
        assertThat(item.pickRatePercent()).isEqualTo(0.0);
    }

    @Test
    void winRateIsWinsOverMatches() {
        when(deadlockApiClient.getItemFlowStats(1, null, EXPECTED_TIMESTAMP))
                .thenReturn(new ItemFlowStats(List.of(
                        new ItemFlowNode(0, 101, 200, 110)
                ), List.of(500L)));

        HeroBuild build = buildService.getBuild("Infernus", null);

        BuildItem item = build.phases().get(0).items().get(0);
        assertThat(item.winRatePercent()).isEqualTo(55.0);
    }

    @Test
    void unknownItemHasNullName() {
        when(deadlockApiClient.getItemFlowStats(1, null, EXPECTED_TIMESTAMP))
                .thenReturn(new ItemFlowStats(List.of(
                        new ItemFlowNode(0, 9999, 50, 25)
                ), List.of(100L)));
        when(deadlockApiClient.getItems()).thenReturn(List.of());

        HeroBuild build = buildService.getBuild("Infernus", null);

        BuildItem item = build.phases().get(0).items().get(0);
        assertThat(item.itemName()).isNull();
    }

    @Test
    void duplicateItemInColumnKeepsFirst() {
        when(deadlockApiClient.getItemFlowStats(1, null, EXPECTED_TIMESTAMP))
                .thenReturn(new ItemFlowStats(List.of(
                        new ItemFlowNode(0, 101, 50, 30),
                        new ItemFlowNode(0, 101, 30, 10)
                ), List.of(100L)));

        HeroBuild build = buildService.getBuild("Infernus", null);

        assertThat(build.phases().get(0).items()).hasSize(1);
        assertThat(build.phases().get(0).items().get(0).matches()).isEqualTo(50L);
    }

    @Test
    void mostPlayedAbilityOrderIsChosen() {
        when(deadlockApiClient.getAbilityOrderStats(1, null, EXPECTED_TIMESTAMP))
                .thenReturn(List.of(
                        new AbilityOrderStat(List.of(3001L, 3002L), 500, 250),
                        new AbilityOrderStat(List.of(3002L, 3001L), 800, 420)
                ));
        when(deadlockApiClient.getItems()).thenReturn(List.of(
                new ItemAsset(3001L, "ability_one", "Flame Dash", "ability", 1, null, 0),
                new ItemAsset(3002L, "ability_two", "Afterburn", "ability", 1, null, 0)
        ));

        HeroBuild build = buildService.getBuild("Infernus", null);

        assertThat(build.abilityOrderMatches()).isEqualTo(800L);
        assertThat(build.abilityOrder()).containsExactly("Afterburn", "Flame Dash");
    }

    @Test
    void abilityOrderTieBreaksOnWinsThenLexicographic() {
        // Tie in matches: 500 vs 500, higher wins (260 vs 240) wins
        when(deadlockApiClient.getAbilityOrderStats(1, null, EXPECTED_TIMESTAMP))
                .thenReturn(List.of(
                        new AbilityOrderStat(List.of(1L, 2L), 500, 240),
                        new AbilityOrderStat(List.of(3L, 4L), 500, 260)
                ));
        when(deadlockApiClient.getItems()).thenReturn(List.of(
                new ItemAsset(3L, "a3", "Skill 3", "ability", 1, null, 0),
                new ItemAsset(4L, "a4", "Skill 4", "ability", 1, null, 0)
        ));

        HeroBuild buildWins = buildService.getBuild("Infernus", null);
        assertThat(buildWins.abilityOrder()).containsExactly("Skill 3", "Skill 4");

        // Tie in matches and wins: 500 matches, 250 wins each. Lexicographically smaller wins:
        // [10, 20] vs [10, 30] -> [10, 20] is smaller
        when(deadlockApiClient.getAbilityOrderStats(1, null, EXPECTED_TIMESTAMP))
                .thenReturn(List.of(
                        new AbilityOrderStat(List.of(10L, 30L), 500, 250),
                        new AbilityOrderStat(List.of(10L, 20L), 500, 250)
                ));
        when(deadlockApiClient.getItems()).thenReturn(List.of(
                new ItemAsset(10L, "a10", "Skill 10", "ability", 1, null, 0),
                new ItemAsset(20L, "a20", "Skill 20", "ability", 1, null, 0)
        ));

        HeroBuild buildLex = buildService.getBuild("Infernus", null);
        assertThat(buildLex.abilityOrder()).containsExactly("Skill 10", "Skill 20");
    }

    @Test
    void emptyAbilityEntriesIgnored() {
        when(deadlockApiClient.getAbilityOrderStats(1, null, EXPECTED_TIMESTAMP))
                .thenReturn(List.of(
                        new AbilityOrderStat(List.of(), 1000, 600),
                        new AbilityOrderStat(List.of(3001L), 50, 30)
                ));
        when(deadlockApiClient.getItems()).thenReturn(List.of(
                new ItemAsset(3001L, "ability_one", "Flame Dash", "ability", 1, null, 0)
        ));

        HeroBuild build = buildService.getBuild("Infernus", null);

        assertThat(build.abilityOrderMatches()).isEqualTo(50L);
        assertThat(build.abilityOrder()).containsExactly("Flame Dash");
    }

    @Test
    void unknownAbilityIdUsesHashFallback() {
        when(deadlockApiClient.getAbilityOrderStats(1, null, EXPECTED_TIMESTAMP))
                .thenReturn(List.of(
                        new AbilityOrderStat(List.of(8888L, 9999L), 100, 50)
                ));
        when(deadlockApiClient.getItems()).thenReturn(List.of(
                new ItemAsset(9999L, "blank", "   ", "ability", 1, null, 0)
        ));

        HeroBuild build = buildService.getBuild("Infernus", null);

        assertThat(build.abilityOrder()).containsExactly("#8888", "#9999");
    }

    @Test
    void noAbilityDataGivesEmptyOrder() {
        when(deadlockApiClient.getAbilityOrderStats(1, null, EXPECTED_TIMESTAMP))
                .thenReturn(List.of());

        HeroBuild build = buildService.getBuild("Infernus", null);

        assertThat(build.abilityOrder()).isEmpty();
        assertThat(build.abilityOrderMatches()).isZero();
    }

    @Test
    void propagatesUnavailableException() {
        when(deadlockApiClient.getItemFlowStats(1, null, EXPECTED_TIMESTAMP))
                .thenThrow(new DeadlockApiUnavailableException("busy", null, null));

        assertThatThrownBy(() -> buildService.getBuild("Infernus", null))
                .isInstanceOf(DeadlockApiUnavailableException.class);
    }
}
