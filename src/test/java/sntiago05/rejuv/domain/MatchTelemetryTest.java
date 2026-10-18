package sntiago05.rejuv.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class MatchTelemetryTest {

    @Test
    void playerFindsByAccountId() {
        PlayerTelemetry p1 = new PlayerTelemetry(1000001L, 1, 1, List.of(), List.of(), List.of());
        PlayerTelemetry p2 = new PlayerTelemetry(1000002L, 0, 13, List.of(), List.of(), List.of());
        MatchTelemetry match = new MatchTelemetry(900000003L, 1952, List.of(p1, p2));

        assertThat(match.player(1000001L)).contains(p1);
        assertThat(match.player(1000002L)).contains(p2);
        assertThat(match.player(9999999L)).isEmpty();
    }

    @Test
    void playerBySlotFindsPlayerAndSkipsNullSlots() {
        PlayerTelemetry p1 = new PlayerTelemetry(1000001L, 1, 1, List.of(), List.of(), List.of(), 2, 1, List.of());
        PlayerTelemetry p2 = new PlayerTelemetry(1000002L, 0, 13, List.of(), List.of(), List.of(), null, 2, List.of());
        PlayerTelemetry p3 = new PlayerTelemetry(1000003L, 0, 25, List.of(), List.of(), List.of(), 5, 3, List.of());
        MatchTelemetry match = new MatchTelemetry(900000003L, 1952, List.of(p1, p2, p3));

        assertThat(match.playerBySlot(2)).contains(p1);
        assertThat(match.playerBySlot(5)).contains(p3);
        assertThat(match.playerBySlot(8)).isEmpty(); // not found
    }

    @Test
    void listsAreImmutableCopies() {
        List<Integer> pickupTimes = new ArrayList<>(List.of(100, 200));
        PowerUpBuff buff = new PowerUpBuff("spirit", true, pickupTimes);
        pickupTimes.add(300);
        assertThat(buff.pickupTimesS()).containsExactly(100, 200);
        assertThatThrownBy(() -> buff.pickupTimesS().add(400))
                .isInstanceOf(UnsupportedOperationException.class);

        List<ItemPurchase> items = new ArrayList<>(List.of(new ItemPurchase(1L, 100, null)));
        List<StatSnapshot> snapshots = new ArrayList<>(List.of(new StatSnapshot(180, 1000L, 500L, 0L)));
        List<PowerUpBuff> buffs = new ArrayList<>(List.of(buff));
        PlayerTelemetry player = new PlayerTelemetry(1000001L, 1, 1, items, snapshots, buffs);

        items.add(new ItemPurchase(2L, 200, null));
        snapshots.add(new StatSnapshot(360, 2000L, 1000L, 0L));
        buffs.add(new PowerUpBuff("gun", false, List.of(300)));

        assertThat(player.items()).hasSize(1);
        assertThat(player.snapshots()).hasSize(1);
        assertThat(player.powerUpBuffs()).hasSize(1);

        assertThatThrownBy(() -> player.items().add(new ItemPurchase(3L, 300, null)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> player.snapshots().add(new StatSnapshot(540, 3000L, 1500L, 0L)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> player.powerUpBuffs().add(new PowerUpBuff("gun", false, List.of(500))))
                .isInstanceOf(UnsupportedOperationException.class);

        List<PlayerTelemetry> players = new ArrayList<>(List.of(player));
        MatchTelemetry match = new MatchTelemetry(900000003L, 1952, players);
        players.add(new PlayerTelemetry(1000002L, 0, 13, List.of(), List.of(), List.of()));

        assertThat(match.players()).hasSize(1);
        assertThatThrownBy(() -> match.players().add(player))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void averageBadgeStoredAndNullableWithConstructors() {
        PlayerTelemetry p1 = new PlayerTelemetry(1000001L, 1, 1, List.of(), List.of(), List.of());
        MatchTelemetry matchWithBadge = new MatchTelemetry(900000003L, 1952, List.of(p1), 75);
        assertThat(matchWithBadge.averageBadge()).isEqualTo(75);

        MatchTelemetry matchDefault = new MatchTelemetry(900000003L, 1952, List.of(p1));
        assertThat(matchDefault.averageBadge()).isNull();
    }
}
