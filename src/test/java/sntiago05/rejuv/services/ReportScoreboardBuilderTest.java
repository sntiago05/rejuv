package sntiago05.rejuv.services;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import sntiago05.rejuv.clients.dto.ItemAsset;
import sntiago05.rejuv.domain.ItemPurchase;
import sntiago05.rejuv.domain.MatchTelemetry;
import sntiago05.rejuv.domain.PlayerTelemetry;
import sntiago05.rejuv.domain.ReportView;
import sntiago05.rejuv.domain.SnapshotDetail;
import sntiago05.rejuv.domain.StatSnapshot;

class ReportScoreboardBuilderTest {

    private static final long VIEWER = 100L;
    private static final Map<Integer, String> HEROES = Map.of(1, "Abrams", 2, "Bebop", 3, "Dynamo");
    private static final Map<Long, ItemAsset> ITEMS = Map.of(
            10L, new ItemAsset(10L, "item_a", "Basic Magazine", "upgrade", 1, "weapon", 500));

    private static PlayerTelemetry player(long id, int team, int hero, int k, int d, int a, int creeps, long damage,
                                          long netWorth, List<ItemPurchase> items) {
        StatSnapshot snapshot = new StatSnapshot(600, netWorth, damage, 0L,
                new SnapshotDetail(k, d, a, creeps, 0, 0, 0, 0, 0L, 0L, 0L, 0L, 0L));
        return new PlayerTelemetry(id, team, hero, items, List.of(snapshot), List.of());
    }

    private static MatchTelemetry match(PlayerTelemetry... players) {
        return new MatchTelemetry(1L, 600, List.of(players));
    }

    private static ReportView.Scoreboard build(MatchTelemetry telemetry, String result, Map<Long, String> names) {
        return ReportScoreboardBuilder.build(telemetry, VIEWER, result, HEROES, names, ITEMS).orElseThrow();
    }

    @Test
    void viewerTeamComesFirstWithOutcomeAndRowsSortedByDamage() {
        MatchTelemetry telemetry = match(
                player(1, 0, 3, 1, 5, 2, 100, 9_000, 20_000, List.of()),
                player(VIEWER, 1, 1, 5, 2, 8, 200, 20_000, 30_000, List.of()),
                player(3, 1, 2, 9, 1, 3, 150, 30_000, 25_000, List.of()));

        ReportView.Scoreboard board = build(telemetry, "win", Map.of());

        assertThat(board.teams()).hasSize(2);
        ReportView.Team mine = board.teams().get(0);
        assertThat(mine.viewerTeam()).isTrue();
        assertThat(mine.labelKey()).isEqualTo("report.scoreboard.team.you");
        assertThat(mine.outcome()).isEqualTo("win");
        assertThat(mine.killsText()).isEqualTo("14");
        assertThat(mine.netWorthText()).isEqualTo("55.0k");
        assertThat(mine.rows()).extracting(ReportView.Row::heroName).containsExactly("Bebop", "Abrams");
        assertThat(mine.rows().get(1).viewer()).isTrue();
        ReportView.Team enemy = board.teams().get(1);
        assertThat(enemy.labelKey()).isEqualTo("report.scoreboard.team.enemy");
        assertThat(enemy.outcome()).isEqualTo("loss");
    }

    @Test
    void lossFlipsOutcomes() {
        MatchTelemetry telemetry = match(
                player(VIEWER, 0, 1, 0, 0, 0, 0, 1, 1, List.of()),
                player(2, 1, 2, 0, 0, 0, 0, 1, 1, List.of()));

        ReportView.Scoreboard board = build(telemetry, "LOSS", Map.of());

        assertThat(board.teams().get(0).outcome()).isEqualTo("loss");
        assertThat(board.teams().get(1).outcome()).isEqualTo("win");
    }

    @Test
    void unknownResultOrViewerGivesNoOutcome() {
        MatchTelemetry telemetry = match(
                player(VIEWER, 0, 1, 0, 0, 0, 0, 1, 1, List.of()),
                player(2, 1, 2, 0, 0, 0, 0, 1, 1, List.of()));
        assertThat(build(telemetry, null, Map.of()).teams()).extracting(ReportView.Team::outcome)
                .containsOnlyNulls();
        assertThat(build(telemetry, "draw", Map.of()).teams()).extracting(ReportView.Team::outcome)
                .containsOnlyNulls();

        MatchTelemetry noViewer = match(
                player(7, 1, 1, 0, 0, 0, 0, 1, 1, List.of()),
                player(8, 0, 2, 0, 0, 0, 0, 1, 1, List.of()));
        ReportView.Scoreboard board = build(noViewer, "win", Map.of());
        assertThat(board.teams()).extracting(ReportView.Team::labelKey)
                .containsExactly("report.scoreboard.team.a", "report.scoreboard.team.b");
        assertThat(board.teams()).extracting(ReportView.Team::outcome).containsOnlyNulls();
        assertThat(board.teams().get(0).rows().get(0).viewer()).isFalse();
    }

    @Test
    void kdaDamageBarAndFormatting() {
        MatchTelemetry telemetry = match(
                player(VIEWER, 0, 1, 5, 0, 5, 215, 107_441, 1, List.of()),
                player(2, 1, 2, 2, 8, 16, 183, 11_153, 1, List.of()));

        ReportView.Scoreboard board = build(telemetry, "win", Map.of());

        ReportView.Row top = board.teams().get(0).rows().get(0);
        assertThat(top.kda()).isEqualTo("10.0");
        assertThat(top.damageText()).isEqualTo("107,441");
        assertThat(top.damageWidthClass()).isEqualTo("w100");
        assertThat(top.creeps()).isEqualTo(215);
        ReportView.Row low = board.teams().get(1).rows().get(0);
        assertThat(low.kda()).isEqualTo("2.3");
        assertThat(low.damageWidthClass()).isEqualTo("w10");
    }

    @Test
    void soldItemsAreDroppedAndAtMostTwelveLatestAreKept() {
        List<ItemPurchase> purchases = new ArrayList<>();
        purchases.add(new ItemPurchase(10L, 30, 400));
        for (int i = 0; i < 14; i++) {
            purchases.add(new ItemPurchase(100L + i, 60 + i, null));
        }
        MatchTelemetry telemetry = match(player(VIEWER, 0, 1, 0, 0, 0, 0, 1, 1, purchases));

        ReportView.Row row = build(telemetry, "win", Map.of()).teams().get(0).rows().get(0);

        assertThat(row.items()).hasSize(ReportScoreboardBuilder.MAX_ITEMS);
        assertThat(row.items().get(0).itemId()).isEqualTo(102L);
        assertThat(row.items().get(11).itemId()).isEqualTo(113L);
        assertThat(row.items().get(0).name()).isEqualTo("Item 102");
    }

    @Test
    void knownItemUsesAssetName() {
        MatchTelemetry telemetry = match(player(VIEWER, 0, 1, 0, 0, 0, 0, 1, 1,
                List.of(new ItemPurchase(10L, 30, null))));

        ReportView.Row row = build(telemetry, "win", Map.of()).teams().get(0).rows().get(0);

        assertThat(row.items()).extracting(ReportView.ScoreItem::name).containsExactly("Basic Magazine");
    }

    @Test
    void namesAreCleanedAndShortened() {
        assertThat(ReportScoreboardBuilder.cleanName("  Two\nLines\t ")).isEqualTo("Two Lines");
        assertThat(ReportScoreboardBuilder.cleanName(null)).isEmpty();
        String longName = "x".repeat(40);
        assertThat(ReportScoreboardBuilder.cleanName(longName)).hasSize(ReportScoreboardBuilder.MAX_NAME_CODE_POINTS);
        MatchTelemetry telemetry = match(player(VIEWER, 0, 1, 0, 0, 0, 0, 1, 1, List.of()));
        ReportView.Row row = build(telemetry, "win", Map.of(VIEWER, "<b>Me</b>")).teams().get(0).rows().get(0);
        assertThat(row.playerName()).isEqualTo("<b>Me</b>");
    }

    @Test
    void playerWithoutSnapshotsGetsZeros() {
        PlayerTelemetry silent = new PlayerTelemetry(VIEWER, 0, 1, List.of(), List.of(), List.of());

        ReportView.Row row = build(match(silent), "win", Map.of()).teams().get(0).rows().get(0);

        assertThat(row.kills()).isZero();
        assertThat(row.damageWidthClass()).isEqualTo("w0");
        assertThat(row.kda()).isEqualTo("0.0");
    }

    @Test
    void emptyOrNullTelemetryGivesNoScoreboard() {
        assertThat(ReportScoreboardBuilder.build(null, VIEWER, "win", HEROES, Map.of(), ITEMS)).isEqualTo(Optional.empty());
        assertThat(ReportScoreboardBuilder.build(new MatchTelemetry(1L, 60, List.of()), VIEWER, "win", HEROES,
                Map.of(), ITEMS)).isEmpty();
    }
}
