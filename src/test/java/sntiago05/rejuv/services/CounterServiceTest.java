package sntiago05.rejuv.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.DeadlockApiUnavailableException;
import sntiago05.rejuv.clients.dto.HeroAsset;
import sntiago05.rejuv.clients.dto.HeroCounterStat;
import sntiago05.rejuv.clients.dto.ItemAsset;
import sntiago05.rejuv.clients.dto.ItemStat;
import sntiago05.rejuv.domain.CounterGuide;
import sntiago05.rejuv.domain.CounterItem;
import sntiago05.rejuv.domain.CounterMatchup;

class CounterServiceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-10-17T15:30:00Z");
    private static final long EXPECTED_TIMESTAMP = Instant.parse("2026-09-17T00:00:00Z").getEpochSecond();
    private static final HeroAsset INFERNUS = new HeroAsset(1, "hero_inferno", "Infernus");

    private DeadlockApiClient deadlockApiClient;
    private HeroResolver heroResolver;
    private Clock clock;
    private CounterService counterService;

    @BeforeEach
    void setUp() {
        deadlockApiClient = mock(DeadlockApiClient.class);
        heroResolver = mock(HeroResolver.class);
        clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        counterService = new CounterService(deadlockApiClient, heroResolver, new GameAssets(deadlockApiClient), clock);

        when(heroResolver.resolve("Infernus")).thenReturn(Optional.of(INFERNUS));
        when(deadlockApiClient.getHeroes()).thenReturn(List.of(
                INFERNUS,
                new HeroAsset(2, "hero_seven", "Seven"),
                new HeroAsset(3, "hero_vindicta", "Vindicta"),
                new HeroAsset(4, "hero_lady_geist", "Lady Geist"),
                new HeroAsset(5, "hero_abrams", "Abrams"),
                new HeroAsset(6, "hero_wraith", "Wraith"),
                new HeroAsset(7, "hero_mcginnis", "McGinnis")
        ));
        when(deadlockApiClient.getItems()).thenReturn(List.of(
                new ItemAsset(101L, "item_101", "Item A", "upgrade", 1, "weapon", 500),
                new ItemAsset(102L, "item_102", "Item B", "upgrade", 1, "vitality", 500),
                new ItemAsset(103L, "item_103", "Item C", "upgrade", 2, "spirit", 1250),
                new ItemAsset(104L, "item_104", "Item D", "upgrade", 3, "weapon", 3000),
                new ItemAsset(105L, "item_105", "Item E", "upgrade", 4, "vitality", 6200),
                new ItemAsset(106L, "item_106", "Item F", "upgrade", 4, "spirit", 6200)
        ));
    }

    @Test
    void timestampIsStartOfUtcDayMinus30Days() {
        when(deadlockApiClient.getHeroCounterStats(eq(7), eq(EXPECTED_TIMESTAMP))).thenReturn(List.of());
        when(deadlockApiClient.getItemStatsAgainst(eq(1), eq(7), eq(EXPECTED_TIMESTAMP))).thenReturn(List.of());

        counterService.getCounterGuide("Infernus", 7);

        verify(deadlockApiClient).getHeroCounterStats(7, EXPECTED_TIMESTAMP);
        verify(deadlockApiClient).getItemStatsAgainst(1, 7, EXPECTED_TIMESTAMP);
    }

    @Test
    void allRanksPassesNullTier() {
        when(deadlockApiClient.getHeroCounterStats(eq(null), eq(EXPECTED_TIMESTAMP))).thenReturn(List.of());
        when(deadlockApiClient.getItemStatsAgainst(eq(1), eq(null), eq(EXPECTED_TIMESTAMP))).thenReturn(List.of());

        CounterGuide guide = counterService.getCounterGuide("Infernus", null);

        assertThat(guide.rankTier()).isNull();
        verify(deadlockApiClient).getHeroCounterStats(null, EXPECTED_TIMESTAMP);
        verify(deadlockApiClient).getItemStatsAgainst(1, null, EXPECTED_TIMESTAMP);
    }

    @Test
    void invalidTierIsRejectedWithoutCalls() {
        assertThatThrownBy(() -> counterService.getCounterGuide("Infernus", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> counterService.getCounterGuide("Infernus", 12))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(deadlockApiClient);
        verifyNoInteractions(heroResolver);
    }

    @Test
    void unknownHeroThrowsAndMakesNoStatsCalls() {
        when(heroResolver.resolve("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> counterService.getCounterGuide("unknown", 1))
                .isInstanceOf(HeroNotFoundException.class);

        verifyNoInteractions(deadlockApiClient);
    }

    @Test
    void callsCountersThenItemsThenAssetsSequentially() {
        when(deadlockApiClient.getHeroCounterStats(any(), anyLong())).thenReturn(List.of());
        when(deadlockApiClient.getItemStatsAgainst(anyInt(), any(), anyLong())).thenReturn(List.of());

        counterService.getCounterGuide("Infernus", 1);

        InOrder inOrder = inOrder(deadlockApiClient);
        inOrder.verify(deadlockApiClient).getHeroCounterStats(1, EXPECTED_TIMESTAMP);
        inOrder.verify(deadlockApiClient).getItemStatsAgainst(1, 1, EXPECTED_TIMESTAMP);
        inOrder.verify(deadlockApiClient).getHeroes();
        inOrder.verify(deadlockApiClient).getItems();
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    void countersAreRowsWhereTargetIsTheEnemy() {
        when(deadlockApiClient.getHeroCounterStats(any(), anyLong())).thenReturn(List.of(
                new HeroCounterStat(2, 1, 60, 100),  // 60% win rate
                new HeroCounterStat(3, 1, 70, 100),  // 70% win rate
                new HeroCounterStat(4, 1, 50, 100),  // 50% win rate, 100 matches, id 4
                new HeroCounterStat(5, 1, 60, 120),  // 50% win rate (60/120), 120 matches
                new HeroCounterStat(6, 1, 50, 100),  // 50% win rate, 100 matches, id 6
                new HeroCounterStat(7, 1, 40, 100)   // 40% win rate (should be dropped by cap 5)
        ));
        when(deadlockApiClient.getItemStatsAgainst(anyInt(), any(), anyLong())).thenReturn(List.of());

        CounterGuide guide = counterService.getCounterGuide("Infernus", 1);

        assertThat(guide.counters()).hasSize(5);
        assertThat(guide.counters().get(0)).isEqualTo(new CounterMatchup(3, "Vindicta", 100, 70.0));
        assertThat(guide.counters().get(1)).isEqualTo(new CounterMatchup(2, "Seven", 100, 60.0));
        assertThat(guide.counters().get(2)).isEqualTo(new CounterMatchup(5, "Abrams", 120, 50.0));
        assertThat(guide.counters().get(3)).isEqualTo(new CounterMatchup(4, "Lady Geist", 100, 50.0));
        assertThat(guide.counters().get(4)).isEqualTo(new CounterMatchup(6, "Wraith", 100, 50.0));
    }

    @Test
    void favorableAreRowsWhereTargetIsTheHero() {
        when(deadlockApiClient.getHeroCounterStats(any(), anyLong())).thenReturn(List.of(
                new HeroCounterStat(1, 2, 75, 100), // Infernus won 75 of 100 against Seven
                new HeroCounterStat(1, 3, 65, 100)  // Infernus won 65 of 100 against Vindicta
        ));
        when(deadlockApiClient.getItemStatsAgainst(anyInt(), any(), anyLong())).thenReturn(List.of());

        CounterGuide guide = counterService.getCounterGuide("Infernus", 1);

        assertThat(guide.favorable()).hasSize(2);
        assertThat(guide.favorable().get(0)).isEqualTo(new CounterMatchup(2, "Seven", 100, 75.0));
        assertThat(guide.favorable().get(1)).isEqualTo(new CounterMatchup(3, "Vindicta", 100, 65.0));
    }

    @Test
    void pairsBelowMinMatchesAreDropped() {
        when(deadlockApiClient.getHeroCounterStats(any(), anyLong())).thenReturn(List.of(
                new HeroCounterStat(2, 1, 30, 49),  // dropped (< 50)
                new HeroCounterStat(3, 1, 30, 50),  // kept (== 50)
                new HeroCounterStat(1, 4, 30, 49),  // dropped (< 50)
                new HeroCounterStat(1, 5, 30, 50)   // kept (== 50)
        ));
        when(deadlockApiClient.getItemStatsAgainst(anyInt(), any(), anyLong())).thenReturn(List.of());

        CounterGuide guide = counterService.getCounterGuide("Infernus", 1);

        assertThat(guide.counters()).extracting(CounterMatchup::heroId).containsExactly(3);
        assertThat(guide.favorable()).extracting(CounterMatchup::heroId).containsExactly(5);
    }

    @Test
    void mirrorRowIsIgnored() {
        when(deadlockApiClient.getHeroCounterStats(any(), anyLong())).thenReturn(List.of(
                new HeroCounterStat(1, 1, 50, 100)
        ));
        when(deadlockApiClient.getItemStatsAgainst(anyInt(), any(), anyLong())).thenReturn(List.of());

        CounterGuide guide = counterService.getCounterGuide("Infernus", 1);

        assertThat(guide.counters()).isEmpty();
        assertThat(guide.favorable()).isEmpty();
    }

    @Test
    void duplicateOpponentKeepsFirst() {
        when(deadlockApiClient.getHeroCounterStats(any(), anyLong())).thenReturn(List.of(
                new HeroCounterStat(2, 1, 60, 100), // first: 60%
                new HeroCounterStat(2, 1, 90, 100), // duplicate: ignored
                new HeroCounterStat(1, 3, 70, 100), // first: 70%
                new HeroCounterStat(1, 3, 90, 100)  // duplicate: ignored
        ));
        when(deadlockApiClient.getItemStatsAgainst(anyInt(), any(), anyLong())).thenReturn(List.of());

        CounterGuide guide = counterService.getCounterGuide("Infernus", 1);

        assertThat(guide.counters()).hasSize(1);
        assertThat(guide.counters().get(0).winRatePercent()).isEqualTo(60.0);
        assertThat(guide.favorable()).hasSize(1);
        assertThat(guide.favorable().get(0).winRatePercent()).isEqualTo(70.0);
    }

    @Test
    void unknownOpponentHasNullName() {
        when(deadlockApiClient.getHeroCounterStats(any(), anyLong())).thenReturn(List.of(
                new HeroCounterStat(999, 1, 60, 100),
                new HeroCounterStat(1, 999, 70, 100)
        ));
        when(deadlockApiClient.getItemStatsAgainst(anyInt(), any(), anyLong())).thenReturn(List.of());

        CounterGuide guide = counterService.getCounterGuide("Infernus", 1);

        assertThat(guide.counters()).hasSize(1);
        assertThat(guide.counters().get(0).heroName()).isNull();
        assertThat(guide.favorable()).hasSize(1);
        assertThat(guide.favorable().get(0).heroName()).isNull();
    }

    @Test
    void itemsFilterToUpgradesWithAKnownName() {
        when(deadlockApiClient.getHeroCounterStats(any(), anyLong())).thenReturn(List.of());
        when(deadlockApiClient.getItems()).thenReturn(List.of(
                new ItemAsset(101L, "upgrade_item", "Upgrade Item", "upgrade", 1, "weapon", 500),
                new ItemAsset(102L, "ability_item", "Ability Item", "ability", 1, "weapon", 500),
                new ItemAsset(103L, "weapon_item", "Weapon Item", "weapon", 1, "weapon", 500),
                new ItemAsset(104L, "blank_name", "   ", "upgrade", 1, "weapon", 500),
                new ItemAsset(105L, "null_name", null, "upgrade", 1, "weapon", 500)
        ));
        when(deadlockApiClient.getItemStatsAgainst(anyInt(), any(), anyLong())).thenReturn(List.of(
                new ItemStat(101L, 100, 60, 40, 10.0, 1.0),
                new ItemStat(102L, 100, 70, 30, 10.0, 1.0),
                new ItemStat(103L, 100, 80, 20, 10.0, 1.0),
                new ItemStat(104L, 100, 90, 10, 10.0, 1.0),
                new ItemStat(105L, 100, 95, 5, 10.0, 1.0),
                new ItemStat(999L, 100, 99, 1, 10.0, 1.0) // unknown id
        ));

        CounterGuide guide = counterService.getCounterGuide("Infernus", 1);

        assertThat(guide.items()).extracting(CounterItem::itemId).containsExactly(101L);
    }

    @Test
    void itemsBelowMinMatchesAreDropped() {
        when(deadlockApiClient.getHeroCounterStats(any(), anyLong())).thenReturn(List.of());
        when(deadlockApiClient.getItemStatsAgainst(anyInt(), any(), anyLong())).thenReturn(List.of(
                new ItemStat(101L, 99, 60, 39, 10.0, 1.0),  // dropped (< 100)
                new ItemStat(102L, 100, 60, 40, 10.0, 1.0)  // kept (== 100)
        ));

        CounterGuide guide = counterService.getCounterGuide("Infernus", 1);

        assertThat(guide.items()).extracting(CounterItem::itemId).containsExactly(102L);
    }

    @Test
    void itemsSortedByWinRateThenMatchesThenIdAndCappedAtFive() {
        when(deadlockApiClient.getHeroCounterStats(any(), anyLong())).thenReturn(List.of());
        when(deadlockApiClient.getItemStatsAgainst(anyInt(), any(), anyLong())).thenReturn(List.of(
                new ItemStat(101L, 100, 60, 40, 10.0, 1.0), // 60.0%
                new ItemStat(102L, 100, 70, 30, 10.0, 1.0), // 70.0%
                new ItemStat(103L, 100, 50, 50, 10.0, 1.0), // 50.0%, 100 matches, id 103
                new ItemStat(104L, 120, 60, 60, 10.0, 1.0), // 50.0%, 120 matches
                new ItemStat(105L, 100, 50, 50, 10.0, 1.0), // 50.0%, 100 matches, id 105
                new ItemStat(106L, 100, 40, 60, 10.0, 1.0)  // 40.0% (should be dropped by cap 5)
        ));

        CounterGuide guide = counterService.getCounterGuide("Infernus", 1);

        assertThat(guide.items()).hasSize(5);
        assertThat(guide.items().get(0)).isEqualTo(new CounterItem(102L, "Item B", 100, 70.0));
        assertThat(guide.items().get(1)).isEqualTo(new CounterItem(101L, "Item A", 100, 60.0));
        assertThat(guide.items().get(2)).isEqualTo(new CounterItem(104L, "Item D", 120, 50.0));
        assertThat(guide.items().get(3)).isEqualTo(new CounterItem(103L, "Item C", 100, 50.0));
        assertThat(guide.items().get(4)).isEqualTo(new CounterItem(105L, "Item E", 100, 50.0));
    }

    @Test
    void duplicateItemKeepsFirst() {
        when(deadlockApiClient.getHeroCounterStats(any(), anyLong())).thenReturn(List.of());
        when(deadlockApiClient.getItemStatsAgainst(anyInt(), any(), anyLong())).thenReturn(List.of(
                new ItemStat(101L, 100, 60, 40, 10.0, 1.0), // first: 60.0%
                new ItemStat(101L, 100, 80, 20, 10.0, 1.0)  // duplicate: ignored
        ));

        CounterGuide guide = counterService.getCounterGuide("Infernus", 1);

        assertThat(guide.items()).hasSize(1);
        assertThat(guide.items().get(0).winRatePercent()).isEqualTo(60.0);
    }

    @Test
    void emptyDataGivesEmptyListsNotNull() {
        when(deadlockApiClient.getHeroCounterStats(any(), anyLong())).thenReturn(List.of());
        when(deadlockApiClient.getItemStatsAgainst(anyInt(), any(), anyLong())).thenReturn(List.of());

        CounterGuide guide = counterService.getCounterGuide("Infernus", 1);

        assertThat(guide.heroId()).isEqualTo(1);
        assertThat(guide.heroName()).isEqualTo("Infernus");
        assertThat(guide.rankTier()).isEqualTo(1);
        assertThat(guide.counters()).isNotNull().isEmpty();
        assertThat(guide.favorable()).isNotNull().isEmpty();
        assertThat(guide.items()).isNotNull().isEmpty();
    }

    @Test
    void propagatesUnavailableException() {
        when(deadlockApiClient.getHeroCounterStats(any(), anyLong()))
                .thenThrow(new DeadlockApiUnavailableException("busy", Duration.ofSeconds(5), null));

        assertThatThrownBy(() -> counterService.getCounterGuide("Infernus", 1))
                .isInstanceOf(DeadlockApiUnavailableException.class);
    }
}
