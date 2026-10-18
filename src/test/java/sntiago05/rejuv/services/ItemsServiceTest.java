package sntiago05.rejuv.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.dto.HeroAsset;
import sntiago05.rejuv.clients.dto.ItemAsset;
import sntiago05.rejuv.clients.dto.ItemStat;
import sntiago05.rejuv.clients.dto.PlayerItemStat;
import sntiago05.rejuv.clients.dto.PlayerRank;
import sntiago05.rejuv.domain.ItemComparison;
import sntiago05.rejuv.domain.ItemsReport;

class ItemsServiceTest {

    private static final long ACCOUNT_ID = 10001L;
    private static final int HERO_ID = 1;
    private static final String HERO_NAME = "Infernus";
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-18T12:00:00Z"), ZoneOffset.UTC);

    private DeadlockApiClient apiClient;
    private HeroResolver heroResolver;
    private GameAssets gameAssets;
    private ItemsService itemsService;

    @BeforeEach
    void setUp() {
        apiClient = mock(DeadlockApiClient.class);
        heroResolver = mock(HeroResolver.class);
        gameAssets = mock(GameAssets.class);
        itemsService = new ItemsService(apiClient, heroResolver, gameAssets, FIXED_CLOCK);

        when(heroResolver.resolve("Infernus"))
                .thenReturn(Optional.of(new HeroAsset(HERO_ID, "infernus", HERO_NAME, null)));
    }

    private static ItemAsset upgrade(long id, String name) {
        return new ItemAsset(id, "item_" + id, name, "upgrade", 1, null, 500, null);
    }

    private static ItemAsset nonUpgrade(long id, String name, String type) {
        return new ItemAsset(id, "item_" + id, name, type, 1, null, 500, null);
    }

    private static ItemStat metaStat(long id, long matches, long wins, double avgBuyTimeS) {
        return new ItemStat(id, matches, wins, matches - wins, avgBuyTimeS, 50.0);
    }

    @Test
    void unknownHeroThrowsHeroNotFoundException() {
        when(heroResolver.resolve("Unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> itemsService.compare(ACCOUNT_ID, "Unknown", null))
                .isInstanceOf(HeroNotFoundException.class)
                .hasMessageContaining("Unknown");
    }

    @Test
    void invalidRankOverrideRejected() {
        assertThatThrownBy(() -> itemsService.compare(ACCOUNT_ID, "Infernus", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> itemsService.compare(ACCOUNT_ID, "Infernus", 12))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unrankedPlayerWithoutOverrideThrowsNoRankException() {
        when(apiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(new PlayerRank(0, 0, 0));

        assertThatThrownBy(() -> itemsService.compare(ACCOUNT_ID, "Infernus", null))
                .isInstanceOf(NoRankException.class)
                .hasMessageContaining(String.valueOf(ACCOUNT_ID));
    }

    @Test
    void rankOverrideWinsOverCurrentRank() {
        when(apiClient.getPlayerItemStats(eq(ACCOUNT_ID), eq(HERO_ID), anyLong())).thenReturn(List.of());
        when(apiClient.getItemStats(HERO_ID, 9)).thenReturn(Map.of());
        when(gameAssets.itemsById()).thenReturn(Map.of());

        ItemsReport report = itemsService.compare(ACCOUNT_ID, "Infernus", 9);

        assertThat(report.rankTier()).isEqualTo(9);
        verify(apiClient, never()).getPlayerRank(anyLong());
        verify(apiClient).getItemStats(HERO_ID, 9);
    }

    @Test
    void currentRankUsedWhenNoOverride() {
        when(apiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(new PlayerRank(74, 7, 4));
        when(apiClient.getPlayerItemStats(eq(ACCOUNT_ID), eq(HERO_ID), anyLong())).thenReturn(List.of());
        when(apiClient.getItemStats(HERO_ID, 7)).thenReturn(Map.of());
        when(gameAssets.itemsById()).thenReturn(Map.of());

        ItemsReport report = itemsService.compare(ACCOUNT_ID, "Infernus", null);

        assertThat(report.rankTier()).isEqualTo(7);
        verify(apiClient).getPlayerRank(ACCOUNT_ID);
        verify(apiClient).getItemStats(HERO_ID, 7);
    }

    @Test
    void thresholdsAndDeltaBoundaries() {
        when(apiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(new PlayerRank(50, 5, 0));

        // item 1: 7 player matches (< 8) -> skipped
        PlayerItemStat p1 = new PlayerItemStat(1, 7, 7, 500.0);
        ItemStat m1 = metaStat(1, 200, 100, 500.0);

        // item 2: 8 player matches, 99 meta matches (< 100) -> skipped
        PlayerItemStat p2 = new PlayerItemStat(2, 8, 8, 500.0);
        ItemStat m2 = metaStat(2, 99, 50, 500.0);

        // item 3: 10 matches, player 50%, meta 50% (delta 0.0) -> considered=1, neither list
        PlayerItemStat p3 = new PlayerItemStat(3, 10, 5, 500.0);
        ItemStat m3 = metaStat(3, 200, 100, 500.0);

        // item 4: delta -4.9 points -> player 45.1%, meta 50.0% -> considered, not underperforming
        PlayerItemStat p4 = new PlayerItemStat(4, 1000, 451, 500.0);
        ItemStat m4 = metaStat(4, 1000, 500, 500.0);

        // item 5: delta -5.0 points -> player 45.0%, meta 50.0% -> underperforming
        PlayerItemStat p5 = new PlayerItemStat(5, 1000, 450, 500.0);
        ItemStat m5 = metaStat(5, 1000, 500, 500.0);

        // item 6: delta +4.9 points -> player 54.9%, meta 50.0% -> considered, not outperforming
        PlayerItemStat p6 = new PlayerItemStat(6, 1000, 549, 500.0);
        ItemStat m6 = metaStat(6, 1000, 500, 500.0);

        // item 7: delta +5.0 points -> player 55.0%, meta 50.0% -> outperforming
        PlayerItemStat p7 = new PlayerItemStat(7, 1000, 550, 500.0);
        ItemStat m7 = metaStat(7, 1000, 500, 500.0);

        when(apiClient.getPlayerItemStats(eq(ACCOUNT_ID), eq(HERO_ID), anyLong()))
                .thenReturn(List.of(p1, p2, p3, p4, p5, p6, p7));
        when(apiClient.getItemStats(HERO_ID, 5)).thenReturn(Map.of(
                1L, m1, 2L, m2, 3L, m3, 4L, m4, 5L, m5, 6L, m6, 7L, m7));
        when(gameAssets.itemsById()).thenReturn(Map.of(
                1L, upgrade(1, "Item 1"),
                2L, upgrade(2, "Item 2"),
                3L, upgrade(3, "Item 3"),
                4L, upgrade(4, "Item 4"),
                5L, upgrade(5, "Item 5"),
                6L, upgrade(6, "Item 6"),
                7L, upgrade(7, "Item 7")));

        ItemsReport report = itemsService.compare(ACCOUNT_ID, "Infernus", null);

        // p1 and p2 did not pass match thresholds; p3, p4, p5, p6, p7 passed (5 items)
        assertThat(report.itemsConsidered()).isEqualTo(5);
        assertThat(report.underperforming()).extracting(ItemComparison::itemId).containsExactly(5L);
        assertThat(report.outperforming()).extracting(ItemComparison::itemId).containsExactly(7L);
    }

    @Test
    void orderingAndTieBreaks() {
        when(apiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(new PlayerRank(50, 5, 0));

        // Underperforming items (worst delta first, ties: more matches, ties: smaller itemId)
        // u1: delta -10.0, matches 10, id 101
        PlayerItemStat u1 = new PlayerItemStat(101, 10, 4, 600.0); // 40% vs 50% = -10.0
        // u2: delta -15.0, matches 10, id 102 (worst delta -> should be first)
        PlayerItemStat u2 = new PlayerItemStat(102, 10, 3, 600.0); // 30% vs 45% = -15.0
        // u3: delta -10.0, matches 20, id 103 (same delta as u1, more matches -> should precede u1)
        PlayerItemStat u3 = new PlayerItemStat(103, 20, 8, 600.0); // 40% vs 50% = -10.0
        // u4: delta -10.0, matches 20, id 100 (same delta & matches as u3, smaller id -> should precede u3)
        PlayerItemStat u4 = new PlayerItemStat(100, 20, 8, 600.0); // 40% vs 50% = -10.0

        // Outperforming items (best delta first, ties: more matches, ties: smaller itemId)
        // o1: delta +10.0, matches 10, id 201
        PlayerItemStat o1 = new PlayerItemStat(201, 10, 6, 600.0); // 60% vs 50% = +10.0
        // o2: delta +20.0, matches 10, id 202 (best delta -> should be first)
        PlayerItemStat o2 = new PlayerItemStat(202, 10, 7, 600.0); // 70% vs 50% = +20.0
        // o3: delta +10.0, matches 20, id 203 (same delta as o1, more matches -> should precede o1)
        PlayerItemStat o3 = new PlayerItemStat(203, 20, 12, 600.0); // 60% vs 50% = +10.0
        // o4: delta +10.0, matches 20, id 200 (same delta & matches as o3, smaller id -> should precede o3)
        PlayerItemStat o4 = new PlayerItemStat(200, 20, 12, 600.0); // 60% vs 50% = +10.0

        when(apiClient.getPlayerItemStats(eq(ACCOUNT_ID), eq(HERO_ID), anyLong()))
                .thenReturn(List.of(u1, u2, u3, u4, o1, o2, o3, o4));
        when(apiClient.getItemStats(HERO_ID, 5)).thenReturn(Map.ofEntries(
                Map.entry(101L, metaStat(101, 100, 50, 600.0)),
                Map.entry(102L, metaStat(102, 100, 45, 600.0)),
                Map.entry(103L, metaStat(103, 100, 50, 600.0)),
                Map.entry(100L, metaStat(100, 100, 50, 600.0)),
                Map.entry(201L, metaStat(201, 100, 50, 600.0)),
                Map.entry(202L, metaStat(202, 100, 50, 600.0)),
                Map.entry(203L, metaStat(203, 100, 50, 600.0)),
                Map.entry(200L, metaStat(200, 100, 50, 600.0))
        ));
        when(gameAssets.itemsById()).thenReturn(Map.ofEntries(
                Map.entry(101L, upgrade(101, "U101")),
                Map.entry(102L, upgrade(102, "U102")),
                Map.entry(103L, upgrade(103, "U103")),
                Map.entry(100L, upgrade(100, "U100")),
                Map.entry(201L, upgrade(201, "O201")),
                Map.entry(202L, upgrade(202, "O202")),
                Map.entry(203L, upgrade(203, "O203")),
                Map.entry(200L, upgrade(200, "O200"))
        ));

        ItemsReport report = itemsService.compare(ACCOUNT_ID, "Infernus", null);

        // Expected underperforming order: 102 (-15.0), then 100 (-10.0, 20 matches, id 100), then 103 (-10.0, 20 matches, id 103), then 101 (-10.0, 10 matches)
        assertThat(report.underperforming()).extracting(ItemComparison::itemId)
                .containsExactly(102L, 100L, 103L, 101L);

        // Expected outperforming order: 202 (+20.0), then 200 (+10.0, 20 matches, id 200), then 203 (+10.0, 20 matches, id 203), then 201 (+10.0, 10 matches)
        assertThat(report.outperforming()).extracting(ItemComparison::itemId)
                .containsExactly(202L, 200L, 203L, 201L);
    }

    @Test
    void eachListCappedAt5() {
        when(apiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(new PlayerRank(50, 5, 0));

        List<PlayerItemStat> playerStats = new ArrayList<>();
        Map<Long, ItemStat> metaStats = new java.util.HashMap<>();
        Map<Long, ItemAsset> assets = new java.util.HashMap<>();

        // Generate 7 underperforming items (deltas -10, -11, -12, -13, -14, -15, -16)
        for (int i = 1; i <= 7; i++) {
            long id = 100 + i;
            playerStats.add(new PlayerItemStat(id, 10, 0, 600.0)); // 0%
            metaStats.put(id, metaStat(id, 100, 10 + i, 600.0)); // (10+i)% -> delta -(10+i)
            assets.put(id, upgrade(id, "U" + id));
        }

        // Generate 7 outperforming items (deltas +10, +11, +12, +13, +14, +15, +16)
        for (int i = 1; i <= 7; i++) {
            long id = 200 + i;
            playerStats.add(new PlayerItemStat(id, 10, 10, 600.0)); // 100%
            metaStats.put(id, metaStat(id, 100, 90 - i, 600.0)); // (90-i)% -> delta +(10+i)
            assets.put(id, upgrade(id, "O" + id));
        }

        when(apiClient.getPlayerItemStats(eq(ACCOUNT_ID), eq(HERO_ID), anyLong())).thenReturn(playerStats);
        when(apiClient.getItemStats(HERO_ID, 5)).thenReturn(metaStats);
        when(gameAssets.itemsById()).thenReturn(assets);

        ItemsReport report = itemsService.compare(ACCOUNT_ID, "Infernus", null);

        assertThat(report.itemsConsidered()).isEqualTo(14);
        assertThat(report.underperforming()).hasSize(5);
        assertThat(report.outperforming()).hasSize(5);
        // Worst 5: deltas -17, -16, -15, -14, -13 (ids 107, 106, 105, 104, 103)
        assertThat(report.underperforming()).extracting(ItemComparison::itemId)
                .containsExactly(107L, 106L, 105L, 104L, 103L);
        // Best 5: deltas +17, +16, +15, +14, +13 (ids 207, 206, 205, 204, 203)
        assertThat(report.outperforming()).extracting(ItemComparison::itemId)
                .containsExactly(207L, 206L, 205L, 204L, 203L);
    }

    @Test
    void nonUpgradeItemsAndMissingMetaItemsAreSkipped() {
        when(apiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(new PlayerRank(50, 5, 0));

        PlayerItemStat p1 = new PlayerItemStat(1, 10, 1, 600.0); // Missing meta
        PlayerItemStat p2 = new PlayerItemStat(2, 10, 1, 600.0); // Weapon type
        PlayerItemStat p3 = new PlayerItemStat(3, 10, 1, 600.0); // Valid upgrade

        when(apiClient.getPlayerItemStats(eq(ACCOUNT_ID), eq(HERO_ID), anyLong())).thenReturn(List.of(p1, p2, p3));
        when(apiClient.getItemStats(HERO_ID, 5)).thenReturn(Map.of(
                2L, metaStat(2, 200, 100, 600.0),
                3L, metaStat(3, 200, 100, 600.0)
        ));
        when(gameAssets.itemsById()).thenReturn(Map.of(
                1L, upgrade(1, "Item 1"),
                2L, nonUpgrade(2, "Item 2", "weapon"),
                3L, upgrade(3, "Item 3")
        ));

        ItemsReport report = itemsService.compare(ACCOUNT_ID, "Infernus", null);

        assertThat(report.itemsConsidered()).isEqualTo(1);
        assertThat(report.underperforming()).extracting(ItemComparison::itemId).containsExactly(3L);
    }

    @Test
    void buyTimesNullWhenAbsentOrZero() {
        when(apiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(new PlayerRank(50, 5, 0));

        // p1: player has null avgBuyTimeS -> both buy times null
        PlayerItemStat p1 = new PlayerItemStat(1, 10, 1, null);
        ItemStat m1 = metaStat(1, 200, 100, 600.0);

        // p2: player has 0.0 avgBuyTimeS -> both buy times null
        PlayerItemStat p2 = new PlayerItemStat(2, 10, 1, 0.0);
        ItemStat m2 = metaStat(2, 200, 100, 600.0);

        // p3: meta has 0.0 avgBuyTimeS -> both buy times null
        PlayerItemStat p3 = new PlayerItemStat(3, 10, 1, 600.0);
        ItemStat m3 = metaStat(3, 200, 100, 0.0);

        // p4: both have positive avgBuyTimeS -> minutes converted (630s = 10.5m, 720s = 12.0m)
        PlayerItemStat p4 = new PlayerItemStat(4, 10, 1, 630.0);
        ItemStat m4 = metaStat(4, 200, 100, 720.0);

        when(apiClient.getPlayerItemStats(eq(ACCOUNT_ID), eq(HERO_ID), anyLong()))
                .thenReturn(List.of(p1, p2, p3, p4));
        when(apiClient.getItemStats(HERO_ID, 5)).thenReturn(Map.of(
                1L, m1, 2L, m2, 3L, m3, 4L, m4
        ));
        when(gameAssets.itemsById()).thenReturn(Map.of(
                1L, upgrade(1, "Item 1"),
                2L, upgrade(2, "Item 2"),
                3L, upgrade(3, "Item 3"),
                4L, upgrade(4, "Item 4")
        ));

        ItemsReport report = itemsService.compare(ACCOUNT_ID, "Infernus", null);

        Map<Long, ItemComparison> map = report.underperforming().stream()
                .collect(java.util.stream.Collectors.toMap(ItemComparison::itemId, java.util.function.Function.identity()));

        assertThat(map.get(1L).playerBuyMin()).isNull();
        assertThat(map.get(1L).metaBuyMin()).isNull();
        assertThat(map.get(2L).playerBuyMin()).isNull();
        assertThat(map.get(2L).metaBuyMin()).isNull();
        assertThat(map.get(3L).playerBuyMin()).isNull();
        assertThat(map.get(3L).metaBuyMin()).isNull();

        assertThat(map.get(4L).playerBuyMin()).isEqualTo(10.5);
        assertThat(map.get(4L).metaBuyMin()).isEqualTo(12.0);
    }
}
