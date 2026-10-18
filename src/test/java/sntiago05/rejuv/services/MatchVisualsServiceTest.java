package sntiago05.rejuv.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sntiago05.rejuv.clients.dto.ItemAsset;
import sntiago05.rejuv.domain.ItemPurchase;
import sntiago05.rejuv.domain.MatchTelemetry;
import sntiago05.rejuv.domain.MatchVisuals;
import sntiago05.rejuv.domain.PlayerTelemetry;
import sntiago05.rejuv.domain.StatSnapshot;

class MatchVisualsServiceTest {

    private static final long ME = 1L;

    private MatchVisualsService service;

    @BeforeEach
    void setUp() {
        GameAssets assets = mock(GameAssets.class);
        Map<Long, ItemAsset> items = new HashMap<>();
        for (long id = 100; id < 125; id++) {
            items.put(id, new ItemAsset(id, "item_" + id, "Item " + id, "upgrade", 1, "weapon", 500));
        }
        items.put(900L, new ItemAsset(900L, "ability_x", "Ability", "ability", null, null, null));
        items.put(901L, new ItemAsset(901L, "blank", " ", "upgrade", 1, "weapon", 500));
        when(assets.itemsById()).thenReturn(items);
        service = new MatchVisualsService(assets);
    }

    private static List<StatSnapshot> snaps(long... worthByIndex) {
        List<StatSnapshot> list = new ArrayList<>();
        for (int i = 0; i < worthByIndex.length; i++) {
            list.add(new StatSnapshot(i * 180, worthByIndex[i], 0, 0));
        }
        return list;
    }

    private static PlayerTelemetry player(long id, int team, List<ItemPurchase> items, List<StatSnapshot> snaps) {
        return new PlayerTelemetry(id, team, 1, items, snaps, List.of());
    }

    private static MatchTelemetry match(PlayerTelemetry... players) {
        return new MatchTelemetry(10L, 1800, List.of(players));
    }

    @Test
    void itemsExcludeSoldUnknownAndNonUpgrade() {
        List<ItemPurchase> bought = List.of(
                new ItemPurchase(100, 60, null),
                new ItemPurchase(101, 120, 400),
                new ItemPurchase(900, 130, null),
                new ItemPurchase(999, 140, null),
                new ItemPurchase(901, 150, null),
                new ItemPurchase(102, 200, null));

        MatchVisuals v = service.build(ME, match(player(ME, 0, bought, List.of()))).orElseThrow();

        assertThat(v.items()).extracting(MatchVisuals.PurchasedItem::itemId).containsExactly(100L, 102L);
        assertThat(v.items().get(0).name()).isEqualTo("Item 100");
        assertThat(v.chart()).isNull();
    }

    @Test
    void itemsAreCappedAtTwelveKeepingTheLastBought() {
        List<ItemPurchase> bought = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            bought.add(new ItemPurchase(100 + i, 60 * i, null));
        }

        MatchVisuals v = service.build(ME, match(player(ME, 0, bought, List.of()))).orElseThrow();

        assertThat(v.items()).hasSize(12);
        assertThat(v.items().get(0).itemId()).isEqualTo(103L);
        assertThat(v.items().get(11).itemId()).isEqualTo(114L);
    }

    @Test
    void nullTelemetryOrAbsentPlayerGivesEmpty() {
        assertThat(service.build(ME, null)).isEmpty();
        assertThat(service.build(ME, match(player(2, 0, List.of(), snaps(1, 2))))).isEmpty();
    }

    @Test
    void soulLeadIsOwnTeamMinusEnemyOnCommonTimes() {
        MatchTelemetry t = match(
                player(ME, 0, List.of(), snaps(0, 1000, 3000)),
                player(2, 0, List.of(), snaps(0, 2000, 4000)),
                player(3, 1, List.of(), snaps(0, 1500, 2000)),
                player(4, 1, List.of(), snaps(0, 500, 1000)));

        MatchVisuals.ChartData c = service.build(ME, t).orElseThrow().chart();

        assertThat(c.timesS()).containsExactly(0, 180, 360);
        assertThat(c.soulLead()).containsExactly(0L, 1000L, 4000L);
        assertThat(c.playerNetWorth()).containsExactly(0L, 1000L, 3000L);
        assertThat(c.teammatesAvgNetWorth()).containsExactly(0L, 2000L, 4000L);
    }

    @Test
    void missingSnapshotTimeForOnePlayerRemovesItForEveryone() {
        List<StatSnapshot> gap = List.of(new StatSnapshot(0, 0, 0, 0), new StatSnapshot(360, 3000, 0, 0),
                new StatSnapshot(540, 4000, 0, 0));
        MatchTelemetry t = match(
                player(ME, 0, List.of(), snaps(0, 1000, 2000, 3000)),
                player(2, 0, List.of(), gap),
                player(3, 1, List.of(), snaps(0, 1, 2, 3)));

        MatchVisuals.ChartData c = service.build(ME, t).orElseThrow().chart();

        assertThat(c.timesS()).containsExactly(0, 360, 540);
    }

    @Test
    void fewerThanTwoCommonTimesGivesNullChartButKeepsItems() {
        MatchTelemetry t = match(
                player(ME, 0, List.of(new ItemPurchase(100, 60, null)), snaps(0, 1000)),
                player(2, 0, List.of(), List.of(new StatSnapshot(0, 0, 0, 0))),
                player(3, 1, List.of(), snaps(0, 1)));

        MatchVisuals v = service.build(ME, t).orElseThrow();

        assertThat(v.chart()).isNull();
        assertThat(v.items()).hasSize(1);
    }

    @Test
    void noTeammatesGivesNoChart() {
        MatchTelemetry t = match(
                player(ME, 0, List.of(new ItemPurchase(100, 60, null)), snaps(0, 1000, 2000)),
                player(3, 1, List.of(), snaps(0, 1, 2)));

        assertThat(service.build(ME, t).orElseThrow().chart()).isNull();
    }

    @Test
    void bothEmptyGivesEmptyOptional() {
        assertThat(service.build(ME, match(player(ME, 0, List.of(), List.of())))).isEqualTo(Optional.empty());
    }

    @Test
    void unevenTeamsStillWork() {
        MatchTelemetry t = match(
                player(ME, 0, List.of(), snaps(0, 1000)),
                player(2, 0, List.of(), snaps(0, 1000)),
                player(3, 0, List.of(), snaps(0, 1000)),
                player(4, 0, List.of(), snaps(0, 1000)),
                player(5, 0, List.of(), snaps(0, 1000)),
                player(6, 1, List.of(), snaps(0, 900)),
                player(7, 1, List.of(), snaps(0, 900)),
                player(8, 1, List.of(), snaps(0, 900)),
                player(9, 1, List.of(), snaps(0, 900)),
                player(10, 1, List.of(), snaps(0, 900)),
                player(11, 1, List.of(), snaps(0, 900)));

        MatchVisuals.ChartData c = service.build(ME, t).orElseThrow().chart();

        assertThat(c.soulLead()).containsExactly(0L, 5000L - 5400L);
        assertThat(c.teammatesAvgNetWorth()).containsExactly(0L, 1000L);
    }
}
