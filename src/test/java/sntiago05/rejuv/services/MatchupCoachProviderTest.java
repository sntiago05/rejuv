package sntiago05.rejuv.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.DeadlockApiUnavailableException;
import sntiago05.rejuv.clients.dto.HeroAsset;
import sntiago05.rejuv.clients.dto.HeroCounterStat;
import sntiago05.rejuv.clients.dto.MatchHistoryEntry;
import sntiago05.rejuv.domain.MatchTelemetry;
import sntiago05.rejuv.domain.PlayerTelemetry;
import sntiago05.rejuv.domain.StatSnapshot;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class MatchupCoachProviderTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-10-17T15:30:00Z");
    private static final long EXPECTED_TIMESTAMP = Instant.parse("2026-09-17T00:00:00Z").getEpochSecond();

    private static final List<HeroAsset> HEROES = List.of(
            new HeroAsset(1, "hero_infernus", "Infernus"),
            new HeroAsset(2, "hero_seven", "Seven"),
            new HeroAsset(3, "hero_vindicta", "Vindicta"),
            new HeroAsset(4, "hero_lady_geist", "Lady Geist"),
            new HeroAsset(5, "hero_abrams", "Abrams"),
            new HeroAsset(6, "hero_wraith", "Wraith"),
            new HeroAsset(7, "hero_haze", "Haze"),
            new HeroAsset(8, "hero_dynamo", "Dynamo"),
            new HeroAsset(9, "hero_kelvin", "Kelvin"),
            new HeroAsset(10, "hero_ivy", "Ivy"),
            new HeroAsset(11, "hero_bebop", "Bebop"),
            new HeroAsset(12, "hero_grey_talon", "Grey Talon")
    );

    private DeadlockApiClient deadlockApiClient;
    private Clock clock;
    private MatchupCoachProvider provider;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        deadlockApiClient = mock(DeadlockApiClient.class);
        clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        provider = new MatchupCoachProvider(deadlockApiClient, clock);
        objectMapper = new JsonMapper();
    }

    @Test
    void keyEqualsMatchup() {
        assertThat(provider.key()).isEqualTo("matchup");
    }

    @Test
    void happyPathFull12PlayerMatchWithExactPayloadFields() throws Exception {
        // Player: hero 1 (Infernus), team 0, lane 1, 8750 NW at 720s
        PlayerTelemetry player = createPlayer(1001L, 0, 1, 1, 1, 8750);

        // Teammates: heroes 2..6, team 0
        List<PlayerTelemetry> players = new ArrayList<>();
        players.add(player);
        for (int i = 2; i <= 6; i++) {
            players.add(createPlayer(1000L + i, 0, i, (i % 4) + 1, i, 8000));
        }

        // Enemies: heroes 7..12, team 1
        // Hero 7 is in lane 1 (only lane opponent of player), NW 10200 at 720s
        players.add(createPlayer(1007L, 1, 7, 1, 7, 10200));
        players.add(createPlayer(1008L, 1, 8, 2, 8, 8000));
        players.add(createPlayer(1009L, 1, 9, 2, 9, 8000));
        players.add(createPlayer(1010L, 1, 10, 3, 10, 8000));
        players.add(createPlayer(1011L, 1, 11, 3, 11, 8000));
        players.add(createPlayer(1012L, 1, 12, 4, 12, 8000));

        MatchTelemetry telemetry = new MatchTelemetry(999999L, 1800, players);

        // Counter stats for hero 1 vs enemies (qualifying pairs with >= 100 matches)
        // 7 (Haze): 446 / 1000 = 44.6%
        // 8 (Dynamo): 534 / 1000 = 53.4%
        // 9 (Kelvin): 470 / 1000 = 47.0%
        // 10 (Ivy): 450 / 1000 = 45.0%
        // 11 (Bebop): 490 / 1000 = 49.0%
        // 12 (Grey Talon): 99 matches (< 100 MIN_PAIR_MATCHES, does not qualify)
        List<HeroCounterStat> stats = List.of(
                new HeroCounterStat(1, 7, 446, 1000),
                new HeroCounterStat(1, 8, 534, 1000),
                new HeroCounterStat(1, 9, 470, 1000),
                new HeroCounterStat(1, 10, 450, 1000),
                new HeroCounterStat(1, 11, 490, 1000),
                new HeroCounterStat(1, 12, 50, 99)
        );

        when(deadlockApiClient.getHeroCounterStats(eq(5), eq(EXPECTED_TIMESTAMP))).thenReturn(stats);

        CoachRequest request = createRequest(player, telemetry, 5);
        Optional<CoachInsight> result = provider.provide(request);

        assertThat(result).isPresent();
        CoachInsight insight = result.get();
        assertThat(insight.key()).isEqualTo("matchup");

        MatchupCoachProvider.MatchupPayload payload = (MatchupCoachProvider.MatchupPayload) insight.payload();

        // Qualifying win rates: 44.6, 53.4, 47.0, 45.0, 49.0.
        // Mean = (44.6 + 53.4 + 47.0 + 45.0 + 49.0) / 5 = 239.0 / 5 = 47.8
        assertThat(payload.enemyAvgWinRatePct()).isEqualTo(47.8);

        // Hardest: 2 enemies with lowest win rate -> Haze (44.6), Ivy (45.0)
        assertThat(payload.hardest()).containsExactly(
                new MatchupCoachProvider.HeroWinRate("Haze", 44.6),
                new MatchupCoachProvider.HeroWinRate("Ivy", 45.0)
        );

        // Easiest: highest win rate >= 52.0 -> Dynamo (53.4)
        assertThat(payload.easiest()).isEqualTo(new MatchupCoachProvider.HeroWinRate("Dynamo", 53.4));

        // Lane opponents: Hero 7 (Haze), lane 1, win rate 44.6%
        assertThat(payload.laneOpponents()).containsExactly(
                new MatchupCoachProvider.HeroWinRate("Haze", 44.6)
        );

        // Net worth diff: 8750 - 10200 = -1450, diffPct = -1450 / 10200 * 100 = -14.2%, result = lost
        assertThat(payload.laneNetWorthDiff()).isEqualTo(-1450L);
        assertThat(payload.laneNetWorthDiffPct()).isEqualTo(-14.2);
        assertThat(payload.laneResult()).isEqualTo("lost");

        // Significance: Haze win rate 44.6 <= 45.0 -> major
        assertThat(payload.significance()).isEqualTo("major");

        // Assert serialized JSON size is well below 1,200 characters
        String json = objectMapper.writeValueAsString(payload);
        assertThat(json.length()).isLessThanOrEqualTo(1200);

        // Assert exact JSON structure
        assertThat(json).contains("\"significance\":\"major\"");
        assertThat(json).contains("\"enemy_avg_win_rate_pct\":47.8");
        assertThat(json).contains("\"lane_net_worth_diff\":-1450");
        assertThat(json).contains("\"lane_net_worth_diff_pct\":-14.2");
        assertThat(json).contains("\"lane_result\":\"lost\"");
    }

    @Test
    void hardestAndEasiestSelectionAndTieBreaking() {
        PlayerTelemetry player = createPlayer(1001L, 0, 1, null, null, null);
        List<PlayerTelemetry> players = List.of(
                player,
                createPlayer(1002L, 1, 10, null, null, null),
                createPlayer(1003L, 1, 5, null, null, null),
                createPlayer(1004L, 1, 8, null, null, null),
                createPlayer(1005L, 1, 12, null, null, null)
        );
        MatchTelemetry telemetry = new MatchTelemetry(999999L, 1800, players);

        // Hero 10 and 5 both have 42.0% win rate -> ties broken by lower heroId (5 before 10)
        // Hero 8 and 12 both have 55.0% win rate -> easiest ties broken by lower heroId (8 before 12)
        List<HeroCounterStat> stats = List.of(
                new HeroCounterStat(1, 10, 420, 1000),
                new HeroCounterStat(1, 5, 420, 1000),
                new HeroCounterStat(1, 8, 550, 1000),
                new HeroCounterStat(1, 12, 550, 1000)
        );

        when(deadlockApiClient.getHeroCounterStats(isNull(), eq(EXPECTED_TIMESTAMP))).thenReturn(stats);

        Optional<CoachInsight> result = provider.provide(createRequest(player, telemetry, null));
        assertThat(result).isPresent();

        MatchupCoachProvider.MatchupPayload payload = (MatchupCoachProvider.MatchupPayload) result.get().payload();

        // Lowest win rate tie: hero 5 (Abrams) before hero 10 (Ivy)
        assertThat(payload.hardest()).containsExactly(
                new MatchupCoachProvider.HeroWinRate("Abrams", 42.0),
                new MatchupCoachProvider.HeroWinRate("Ivy", 42.0)
        );

        // Highest win rate tie: hero 8 (Dynamo) before hero 12 (Grey Talon)
        assertThat(payload.easiest()).isEqualTo(new MatchupCoachProvider.HeroWinRate("Dynamo", 55.0));
    }

    @Test
    void minPairMatchesBoundary99Vs100() {
        PlayerTelemetry player = createPlayer(1001L, 0, 1, null, null, null);
        List<PlayerTelemetry> players = List.of(
                player,
                createPlayer(1002L, 1, 7, null, null, null),
                createPlayer(1003L, 1, 8, null, null, null)
        );
        MatchTelemetry telemetry = new MatchTelemetry(999999L, 1800, players);

        // Hero 7 has 99 matches (disqualified), Hero 8 has 100 matches (qualified)
        List<HeroCounterStat> stats = List.of(
                new HeroCounterStat(1, 7, 40, 99),
                new HeroCounterStat(1, 8, 55, 100)
        );

        when(deadlockApiClient.getHeroCounterStats(isNull(), eq(EXPECTED_TIMESTAMP))).thenReturn(stats);

        Optional<CoachInsight> result = provider.provide(createRequest(player, telemetry, null));
        assertThat(result).isPresent();

        MatchupCoachProvider.MatchupPayload payload = (MatchupCoachProvider.MatchupPayload) result.get().payload();
        assertThat(payload.enemyAvgWinRatePct()).isEqualTo(55.0);
        assertThat(payload.hardest()).containsExactly(new MatchupCoachProvider.HeroWinRate("Dynamo", 55.0));
        assertThat(payload.easiest()).isEqualTo(new MatchupCoachProvider.HeroWinRate("Dynamo", 55.0));
    }

    @Test
    void easiestThresholdBoundary51Point9Vs52Point0() {
        PlayerTelemetry player = createPlayer(1001L, 0, 1, null, null, null);
        List<PlayerTelemetry> players = List.of(player, createPlayer(1002L, 1, 7, null, null, null));
        MatchTelemetry telemetry = new MatchTelemetry(999999L, 1800, players);

        // 51.9% -> easiest must be omitted (null)
        when(deadlockApiClient.getHeroCounterStats(isNull(), eq(EXPECTED_TIMESTAMP))).thenReturn(List.of(
                new HeroCounterStat(1, 7, 519, 1000)
        ));

        Optional<CoachInsight> res519 = provider.provide(createRequest(player, telemetry, null));
        assertThat(res519).isPresent();
        assertThat(((MatchupCoachProvider.MatchupPayload) res519.get().payload()).easiest()).isNull();

        // 52.0% -> easiest must be present
        when(deadlockApiClient.getHeroCounterStats(isNull(), eq(EXPECTED_TIMESTAMP))).thenReturn(List.of(
                new HeroCounterStat(1, 7, 520, 1000)
        ));

        Optional<CoachInsight> res520 = provider.provide(createRequest(player, telemetry, null));
        assertThat(res520).isPresent();
        assertThat(((MatchupCoachProvider.MatchupPayload) res520.get().payload()).easiest())
                .isEqualTo(new MatchupCoachProvider.HeroWinRate("Haze", 52.0));
    }

    @Test
    void laneSectionWithTwoOpponents() {
        // Player: lane 2, NW 6000
        PlayerTelemetry player = createPlayer(1001L, 0, 1, 2, 1, 6000);
        // Opponents in lane 2: hero 8 (NW 5000), hero 7 (NW 7000)
        PlayerTelemetry opp1 = createPlayer(1002L, 1, 8, 2, 2, 5000);
        PlayerTelemetry opp2 = createPlayer(1003L, 1, 7, 2, 3, 7000);

        MatchTelemetry telemetry = new MatchTelemetry(999999L, 1800, List.of(player, opp1, opp2));

        when(deadlockApiClient.getHeroCounterStats(isNull(), eq(EXPECTED_TIMESTAMP))).thenReturn(List.of(
                new HeroCounterStat(1, 7, 500, 1000),
                new HeroCounterStat(1, 8, 500, 1000)
        ));

        Optional<CoachInsight> result = provider.provide(createRequest(player, telemetry, null));
        assertThat(result).isPresent();

        MatchupCoachProvider.MatchupPayload payload = (MatchupCoachProvider.MatchupPayload) result.get().payload();

        // Ordered by heroId ascending: hero 7 (Haze), then hero 8 (Dynamo)
        assertThat(payload.laneOpponents()).extracting(MatchupCoachProvider.HeroWinRate::hero)
                .containsExactly("Haze", "Dynamo");

        // Average opponent NW = (5000 + 7000) / 2 = 6000. Diff = 6000 - 6000 = 0, diffPct = 0.0, result = even
        assertThat(payload.laneNetWorthDiff()).isEqualTo(0L);
        assertThat(payload.laneNetWorthDiffPct()).isEqualTo(0.0);
        assertThat(payload.laneResult()).isEqualTo("even");
    }

    @Test
    void laneSectionWithThreeOpponentsCapsAtTwoOrderedByHeroId() {
        PlayerTelemetry player = createPlayer(1001L, 0, 1, 2, 1, 6000);
        PlayerTelemetry opp1 = createPlayer(1002L, 1, 10, 2, 2, 5000);
        PlayerTelemetry opp2 = createPlayer(1003L, 1, 7, 2, 3, 7000);
        PlayerTelemetry opp3 = createPlayer(1004L, 1, 8, 2, 4, 6000);

        MatchTelemetry telemetry = new MatchTelemetry(999999L, 1800, List.of(player, opp1, opp2, opp3));
        when(deadlockApiClient.getHeroCounterStats(isNull(), eq(EXPECTED_TIMESTAMP))).thenReturn(List.of());

        Optional<CoachInsight> result = provider.provide(createRequest(player, telemetry, null));
        assertThat(result).isPresent();

        MatchupCoachProvider.MatchupPayload payload = (MatchupCoachProvider.MatchupPayload) result.get().payload();
        // Capped at 2, sorted by heroId: hero 7, hero 8 (hero 10 excluded)
        assertThat(payload.laneOpponents()).extracting(MatchupCoachProvider.HeroWinRate::hero)
                .containsExactly("Haze", "Dynamo");

        // Net worth calculation uses capped opponents: (7000 + 6000) / 2 = 6500. Diff = 6000 - 6500 = -500
        assertThat(payload.laneNetWorthDiff()).isEqualTo(-500L);
    }

    @Test
    void laneSectionWithOneOpponent() {
        PlayerTelemetry player = createPlayer(1001L, 0, 1, 3, 1, 5500);
        PlayerTelemetry opp = createPlayer(1002L, 1, 7, 3, 2, 5000);

        MatchTelemetry telemetry = new MatchTelemetry(999999L, 1800, List.of(player, opp));
        when(deadlockApiClient.getHeroCounterStats(isNull(), eq(EXPECTED_TIMESTAMP))).thenReturn(List.of());

        Optional<CoachInsight> result = provider.provide(createRequest(player, telemetry, null));
        assertThat(result).isPresent();

        MatchupCoachProvider.MatchupPayload payload = (MatchupCoachProvider.MatchupPayload) result.get().payload();
        assertThat(payload.laneOpponents()).hasSize(1);
        assertThat(payload.laneNetWorthDiff()).isEqualTo(500L);
        assertThat(payload.laneNetWorthDiffPct()).isEqualTo(10.0);
        assertThat(payload.laneResult()).isEqualTo("won");
    }

    @Test
    void laneSectionWithNoOpponentsInSameLane() {
        PlayerTelemetry player = createPlayer(1001L, 0, 1, 1, 1, 5000);
        PlayerTelemetry opp = createPlayer(1002L, 1, 7, 2, 2, 5000);

        MatchTelemetry telemetry = new MatchTelemetry(999999L, 1800, List.of(player, opp));
        when(deadlockApiClient.getHeroCounterStats(isNull(), eq(EXPECTED_TIMESTAMP))).thenReturn(List.of(
                new HeroCounterStat(1, 7, 500, 1000)
        ));

        Optional<CoachInsight> result = provider.provide(createRequest(player, telemetry, null));
        assertThat(result).isPresent();

        MatchupCoachProvider.MatchupPayload payload = (MatchupCoachProvider.MatchupPayload) result.get().payload();
        assertThat(payload.laneOpponents()).isEmpty();
        assertThat(payload.laneNetWorthDiff()).isNull();
        assertThat(payload.laneNetWorthDiffPct()).isNull();
        assertThat(payload.laneResult()).isNull();
    }

    @Test
    void assignedLaneNullOmitsLaneFields() {
        // Raw metadata fallback where assignedLane is null
        PlayerTelemetry player = createPlayer(1001L, 0, 1, null, null, 5000);
        PlayerTelemetry opp = createPlayer(1002L, 1, 7, null, null, 5000);

        MatchTelemetry telemetry = new MatchTelemetry(999999L, 1800, List.of(player, opp));
        when(deadlockApiClient.getHeroCounterStats(isNull(), eq(EXPECTED_TIMESTAMP))).thenReturn(List.of(
                new HeroCounterStat(1, 7, 490, 1000)
        ));

        Optional<CoachInsight> result = provider.provide(createRequest(player, telemetry, null));
        assertThat(result).isPresent();

        MatchupCoachProvider.MatchupPayload payload = (MatchupCoachProvider.MatchupPayload) result.get().payload();
        assertThat(payload.enemyAvgWinRatePct()).isEqualTo(49.0);
        assertThat(payload.hardest()).isNotEmpty();
        assertThat(payload.laneOpponents()).isNull();
        assertThat(payload.laneNetWorthDiff()).isNull();
        assertThat(payload.laneNetWorthDiffPct()).isNull();
        assertThat(payload.laneResult()).isNull();
    }

    @Test
    void snapshotMissingForOneOpponentUsesAvailableOpponentSnapshot() {
        PlayerTelemetry player = createPlayer(1001L, 0, 1, 1, 1, 5500);
        // Opponent 1 has snapshot at 720s (5000 NW)
        PlayerTelemetry opp1 = createPlayer(1002L, 1, 7, 1, 2, 5000);
        // Opponent 2 has snapshots, but none at 720s
        PlayerTelemetry opp2 = new PlayerTelemetry(1003L, 1, 8, List.of(),
                List.of(new StatSnapshot(360, 2500, 1000, 0)), List.of(), 3, 1, List.of());

        MatchTelemetry telemetry = new MatchTelemetry(999999L, 1800, List.of(player, opp1, opp2));
        when(deadlockApiClient.getHeroCounterStats(isNull(), eq(EXPECTED_TIMESTAMP))).thenReturn(List.of());

        Optional<CoachInsight> result = provider.provide(createRequest(player, telemetry, null));
        assertThat(result).isPresent();

        MatchupCoachProvider.MatchupPayload payload = (MatchupCoachProvider.MatchupPayload) result.get().payload();
        // Uses opp1 NW (5000), diff = 5500 - 5000 = 500
        assertThat(payload.laneNetWorthDiff()).isEqualTo(500L);
        assertThat(payload.laneNetWorthDiffPct()).isEqualTo(10.0);
        assertThat(payload.laneResult()).isEqualTo("won");
    }

    @Test
    void snapshotMissingForAllOpponentsOmitsNetWorthDiff() {
        PlayerTelemetry player = createPlayer(1001L, 0, 1, 1, 1, 5500);
        PlayerTelemetry opp = new PlayerTelemetry(1002L, 1, 7, List.of(), List.of(), List.of(), 2, 1, List.of());

        MatchTelemetry telemetry = new MatchTelemetry(999999L, 1800, List.of(player, opp));
        when(deadlockApiClient.getHeroCounterStats(isNull(), eq(EXPECTED_TIMESTAMP))).thenReturn(List.of());

        Optional<CoachInsight> result = provider.provide(createRequest(player, telemetry, null));
        assertThat(result).isPresent();

        MatchupCoachProvider.MatchupPayload payload = (MatchupCoachProvider.MatchupPayload) result.get().payload();
        assertThat(payload.laneOpponents()).hasSize(1);
        assertThat(payload.laneNetWorthDiff()).isNull();
        assertThat(payload.laneNetWorthDiffPct()).isNull();
        assertThat(payload.laneResult()).isNull();
    }

    @Test
    void snapshotMissingForPlayerOmitsNetWorthDiff() {
        PlayerTelemetry player = new PlayerTelemetry(1001L, 0, 1, List.of(), List.of(), List.of(), 1, 1, List.of());
        PlayerTelemetry opp = createPlayer(1002L, 1, 7, 1, 2, 5000);

        MatchTelemetry telemetry = new MatchTelemetry(999999L, 1800, List.of(player, opp));
        when(deadlockApiClient.getHeroCounterStats(isNull(), eq(EXPECTED_TIMESTAMP))).thenReturn(List.of());

        Optional<CoachInsight> result = provider.provide(createRequest(player, telemetry, null));
        assertThat(result).isPresent();

        MatchupCoachProvider.MatchupPayload payload = (MatchupCoachProvider.MatchupPayload) result.get().payload();
        assertThat(payload.laneNetWorthDiff()).isNull();
        assertThat(payload.laneResult()).isNull();
    }

    @Test
    void matchShorterThan720SecondsOmitsNetWorthDiff() {
        PlayerTelemetry player = createPlayer(1001L, 0, 1, 1, 1, 5000);
        PlayerTelemetry opp = createPlayer(1002L, 1, 7, 1, 2, 5000);

        // Match duration 719s (< 720s)
        MatchTelemetry telemetry = new MatchTelemetry(999999L, 719, List.of(player, opp));
        when(deadlockApiClient.getHeroCounterStats(isNull(), eq(EXPECTED_TIMESTAMP))).thenReturn(List.of());

        Optional<CoachInsight> result = provider.provide(createRequest(player, telemetry, null));
        assertThat(result).isPresent();

        MatchupCoachProvider.MatchupPayload payload = (MatchupCoachProvider.MatchupPayload) result.get().payload();
        assertThat(payload.laneNetWorthDiff()).isNull();
        assertThat(payload.laneNetWorthDiffPct()).isNull();
        assertThat(payload.laneResult()).isNull();
    }

    @Test
    void opponentsAverageNetWorthZeroOrNegativeOmitsNetWorthDiff() {
        PlayerTelemetry player = createPlayer(1001L, 0, 1, 1, 1, 5000);
        PlayerTelemetry opp = createPlayer(1002L, 1, 7, 1, 2, 0); // 0 NW at 720s

        MatchTelemetry telemetry = new MatchTelemetry(999999L, 1800, List.of(player, opp));
        when(deadlockApiClient.getHeroCounterStats(isNull(), eq(EXPECTED_TIMESTAMP))).thenReturn(List.of());

        Optional<CoachInsight> result = provider.provide(createRequest(player, telemetry, null));
        assertThat(result).isPresent();

        MatchupCoachProvider.MatchupPayload payload = (MatchupCoachProvider.MatchupPayload) result.get().payload();
        assertThat(payload.laneNetWorthDiff()).isNull();
        assertThat(payload.laneNetWorthDiffPct()).isNull();
        assertThat(payload.laneResult()).isNull();
    }

    @Test
    void laneResultBoundaries() {
        assertLaneResult(11000, 10000, 10.0, "won");
        assertLaneResult(10990, 10000, 9.9, "even");
        assertLaneResult(9010, 10000, -9.9, "even");
        assertLaneResult(9000, 10000, -10.0, "lost");
    }

    private void assertLaneResult(long playerNw, long oppNw, double expectedPct, String expectedResult) {
        PlayerTelemetry player = createPlayer(1001L, 0, 1, 1, 1, playerNw);
        PlayerTelemetry opp = createPlayer(1002L, 1, 7, 1, 2, oppNw);
        MatchTelemetry telemetry = new MatchTelemetry(999999L, 1800, List.of(player, opp));

        when(deadlockApiClient.getHeroCounterStats(isNull(), eq(EXPECTED_TIMESTAMP))).thenReturn(List.of());

        Optional<CoachInsight> result = provider.provide(createRequest(player, telemetry, null));
        assertThat(result).isPresent();

        MatchupCoachProvider.MatchupPayload payload = (MatchupCoachProvider.MatchupPayload) result.get().payload();
        assertThat(payload.laneNetWorthDiffPct()).isEqualTo(expectedPct);
        assertThat(payload.laneResult()).isEqualTo(expectedResult);
    }

    @Test
    void significanceMajorBoundaries() {
        // Major boundary at -20.0% lane loss vs -19.9%
        assertSignificance(8000, 10000, null, "major");  // -20.0% -> major
        assertSignificance(8010, 10000, null, "notable"); // -19.9% (lost) -> notable

        // Win rate 45.0% in hardest -> major
        assertSignificance(10000, 10000, 45.0, "major");
        // Win rate 45.1% in hardest -> notable (since <= 48.0)
        assertSignificance(10000, 10000, 45.1, "notable");
        // Win rate 48.0% in hardest -> notable
        assertSignificance(10000, 10000, 48.0, "notable");
        // Win rate 48.1% in hardest -> none
        assertSignificance(10000, 10000, 48.1, "none");
    }

    private void assertSignificance(long playerNw, long oppNw, Double winRate, String expectedSignificance) {
        PlayerTelemetry player = createPlayer(1001L, 0, 1, 1, 1, playerNw);
        PlayerTelemetry opp = createPlayer(1002L, 1, 7, 1, 2, oppNw);
        MatchTelemetry telemetry = new MatchTelemetry(999999L, 1800, List.of(player, opp));

        List<HeroCounterStat> stats = (winRate != null)
                ? List.of(new HeroCounterStat(1, 7, (long) (winRate * 10), 1000))
                : List.of();

        when(deadlockApiClient.getHeroCounterStats(isNull(), eq(EXPECTED_TIMESTAMP))).thenReturn(stats);

        Optional<CoachInsight> result = provider.provide(createRequest(player, telemetry, null));
        assertThat(result).isPresent();

        MatchupCoachProvider.MatchupPayload payload = (MatchupCoachProvider.MatchupPayload) result.get().payload();
        assertThat(payload.significance()).isEqualTo(expectedSignificance);
    }

    @Test
    void significanceMajorWhenLaneOpponentHas45WinRateEvenIfNotInHardest() {
        // Player in lane 1 vs Hero 7
        PlayerTelemetry player = createPlayer(1001L, 0, 1, 1, 1, 10000);
        PlayerTelemetry laneOpp = createPlayer(1002L, 1, 7, 1, 2, 10000); // Hero 7 in lane
        PlayerTelemetry enemy2 = createPlayer(1003L, 1, 8, 2, 3, 10000);
        PlayerTelemetry enemy3 = createPlayer(1004L, 1, 9, 3, 4, 10000);

        MatchTelemetry telemetry = new MatchTelemetry(999999L, 1800, List.of(player, laneOpp, enemy2, enemy3));

        // Two other enemies have even lower win rate (40.0 and 42.0), so they fill hardest.
        // Hero 7 (in lane) has 45.0% (<= 45.0).
        List<HeroCounterStat> stats = List.of(
                new HeroCounterStat(1, 8, 400, 1000), // hardest #1
                new HeroCounterStat(1, 9, 420, 1000), // hardest #2
                new HeroCounterStat(1, 7, 450, 1000)  // lane opp (not in hardest, but winRate <= 45.0)
        );

        when(deadlockApiClient.getHeroCounterStats(isNull(), eq(EXPECTED_TIMESTAMP))).thenReturn(stats);

        Optional<CoachInsight> result = provider.provide(createRequest(player, telemetry, null));
        assertThat(result).isPresent();

        MatchupCoachProvider.MatchupPayload payload = (MatchupCoachProvider.MatchupPayload) result.get().payload();
        assertThat(payload.significance()).isEqualTo("major");
    }

    @Test
    void tierHandlingAndClockWindow() {
        PlayerTelemetry player = createPlayer(1001L, 0, 1, 1, 1, 5000);
        PlayerTelemetry opp = createPlayer(1002L, 1, 7, 1, 2, 5000);
        MatchTelemetry telemetry = new MatchTelemetry(999999L, 1800, List.of(player, opp));

        when(deadlockApiClient.getHeroCounterStats(any(), anyLong())).thenReturn(List.of());

        // Tier 5 passes 5
        provider.provide(createRequest(player, telemetry, 5));
        verify(deadlockApiClient, times(1)).getHeroCounterStats(eq(5), eq(EXPECTED_TIMESTAMP));

        // Tier 0 passes null
        provider.provide(createRequest(player, telemetry, 0));
        verify(deadlockApiClient, times(1)).getHeroCounterStats(isNull(), eq(EXPECTED_TIMESTAMP));

        // Tier null passes null
        provider.provide(createRequest(player, telemetry, null));
        verify(deadlockApiClient, times(2)).getHeroCounterStats(isNull(), eq(EXPECTED_TIMESTAMP));

        // Tier 12 (invalid) passes null
        provider.provide(createRequest(player, telemetry, 12));
        verify(deadlockApiClient, times(3)).getHeroCounterStats(isNull(), eq(EXPECTED_TIMESTAMP));
    }

    @Test
    void deadlockApiExceptionFallbackReturnsLaneOnlyPayload() {
        PlayerTelemetry player = createPlayer(1001L, 0, 1, 1, 1, 6000);
        PlayerTelemetry opp = createPlayer(1002L, 1, 7, 1, 2, 5000);
        MatchTelemetry telemetry = new MatchTelemetry(999999L, 1800, List.of(player, opp));

        when(deadlockApiClient.getHeroCounterStats(isNull(), eq(EXPECTED_TIMESTAMP)))
                .thenThrow(new DeadlockApiUnavailableException("Busy", null, null));

        Optional<CoachInsight> result = provider.provide(createRequest(player, telemetry, null));
        assertThat(result).isPresent();

        MatchupCoachProvider.MatchupPayload payload = (MatchupCoachProvider.MatchupPayload) result.get().payload();
        assertThat(payload.enemyAvgWinRatePct()).isNull();
        assertThat(payload.hardest()).isNull();
        assertThat(payload.easiest()).isNull();
        assertThat(payload.laneOpponents()).containsExactly(new MatchupCoachProvider.HeroWinRate("Haze", null));
        assertThat(payload.laneNetWorthDiff()).isEqualTo(1000L);
        assertThat(payload.laneNetWorthDiffPct()).isEqualTo(20.0);
        assertThat(payload.laneResult()).isEqualTo("won");
    }

    @Test
    void deadlockApiExceptionFallbackReturnsEmptyWhenNoLaneDataEither() {
        // assignedLane is null, so no lane data
        PlayerTelemetry player = createPlayer(1001L, 0, 1, null, null, 6000);
        PlayerTelemetry opp = createPlayer(1002L, 1, 7, null, null, 5000);
        MatchTelemetry telemetry = new MatchTelemetry(999999L, 1800, List.of(player, opp));

        when(deadlockApiClient.getHeroCounterStats(isNull(), eq(EXPECTED_TIMESTAMP)))
                .thenThrow(new DeadlockApiUnavailableException("Busy", null, null));

        Optional<CoachInsight> result = provider.provide(createRequest(player, telemetry, null));
        assertThat(result).isEmpty();
    }

    @Test
    void singlePassOverLargeGeneratedPairTable() {
        PlayerTelemetry player = createPlayer(1001L, 0, 1, 1, 1, 5000);
        PlayerTelemetry opp = createPlayer(1002L, 1, 7, 1, 2, 5000);
        MatchTelemetry telemetry = new MatchTelemetry(999999L, 1800, List.of(player, opp));

        // Generate 2,000 rows
        List<HeroCounterStat> largeTable = new ArrayList<>();
        for (int h = 1; h <= 45; h++) {
            for (int e = 1; e <= 45; e++) {
                if (h != e) {
                    largeTable.add(new HeroCounterStat(h, e, 500, 1000));
                }
            }
        }

        when(deadlockApiClient.getHeroCounterStats(isNull(), eq(EXPECTED_TIMESTAMP))).thenReturn(largeTable);

        Optional<CoachInsight> result = provider.provide(createRequest(player, telemetry, null));
        assertThat(result).isPresent();
        assertThat(((MatchupCoachProvider.MatchupPayload) result.get().payload()).enemyAvgWinRatePct()).isEqualTo(50.0);
    }

    @Test
    void missingDataCasesReturnEmpty() {
        PlayerTelemetry player = createPlayer(1001L, 0, 1, 1, 1, 5000);
        MatchTelemetry telemetry = new MatchTelemetry(999999L, 1800, List.of(player));

        // request null
        assertThat(provider.provide(null)).isEmpty();

        // telemetry null
        assertThat(provider.provide(new CoachRequest(1001L, null, List.of(), null, player, null, HEROES, List.of()))).isEmpty();

        // player null
        assertThat(provider.provide(new CoachRequest(1001L, null, List.of(), telemetry, null, null, HEROES, List.of()))).isEmpty();

        // empty players list
        MatchTelemetry emptyTelemetry = new MatchTelemetry(999999L, 1800, List.of());
        assertThat(provider.provide(createRequest(player, emptyTelemetry, null))).isEmpty();

        // no enemy players (only teammates)
        PlayerTelemetry teammate = createPlayer(1002L, 0, 2, 1, 2, 5000);
        MatchTelemetry onlyTeammates = new MatchTelemetry(999999L, 1800, List.of(player, teammate));
        assertThat(provider.provide(createRequest(player, onlyTeammates, null))).isEmpty();
    }

    @Test
    void heroNameResolutionFallbackOrder() {
        PlayerTelemetry player = createPlayer(1001L, 0, 1, null, null, null);
        // Hero 20: name present ("CustomHero")
        // Hero 21: name null, className present ("hero_class_only")
        // Hero 22: neither name nor className present in assets
        PlayerTelemetry opp1 = createPlayer(1002L, 1, 20, null, null, null);
        PlayerTelemetry opp2 = createPlayer(1003L, 1, 21, null, null, null);
        PlayerTelemetry opp3 = createPlayer(1004L, 1, 22, null, null, null);

        MatchTelemetry telemetry = new MatchTelemetry(999999L, 1800, List.of(player, opp1, opp2, opp3));

        List<HeroAsset> customHeroes = List.of(
                new HeroAsset(20, "hero_custom", "CustomHero"),
                new HeroAsset(21, "hero_class_only", null)
        );

        List<HeroCounterStat> stats = List.of(
                new HeroCounterStat(1, 20, 500, 1000),
                new HeroCounterStat(1, 21, 500, 1000),
                new HeroCounterStat(1, 22, 500, 1000)
        );

        when(deadlockApiClient.getHeroCounterStats(isNull(), eq(EXPECTED_TIMESTAMP))).thenReturn(stats);

        CoachRequest req = new CoachRequest(1001L, null, List.of(), telemetry, player, null, customHeroes, List.of());
        Optional<CoachInsight> result = provider.provide(req);

        assertThat(result).isPresent();
        MatchupCoachProvider.MatchupPayload payload = (MatchupCoachProvider.MatchupPayload) result.get().payload();

        assertThat(payload.hardest()).extracting(MatchupCoachProvider.HeroWinRate::hero)
                .containsExactly("CustomHero", "hero_class_only");
    }

    @Test
    void determinismAndImmutability() {
        PlayerTelemetry player = createPlayer(1001L, 0, 1, 1, 1, 5000);
        PlayerTelemetry opp = createPlayer(1002L, 1, 7, 1, 2, 6000);
        MatchTelemetry telemetry = new MatchTelemetry(999999L, 1800, List.of(player, opp));

        when(deadlockApiClient.getHeroCounterStats(isNull(), eq(EXPECTED_TIMESTAMP))).thenReturn(List.of(
                new HeroCounterStat(1, 7, 500, 1000)
        ));

        CoachRequest req = createRequest(player, telemetry, null);
        Optional<CoachInsight> res1 = provider.provide(req);
        Optional<CoachInsight> res2 = provider.provide(req);

        assertThat(res1).isEqualTo(res2);
    }

    private static PlayerTelemetry createPlayer(long accountId, int team, int heroId,
                                                Integer assignedLane, Integer playerSlot,
                                                Number netWorthAt720) {
        List<StatSnapshot> snapshots = new ArrayList<>();
        if (netWorthAt720 != null) {
            snapshots.add(new StatSnapshot(720, netWorthAt720.longValue(), 3000, 0));
        }
        return new PlayerTelemetry(
                accountId,
                team,
                heroId,
                List.of(),
                snapshots,
                List.of(),
                playerSlot,
                assignedLane,
                List.of()
        );
    }

    private static CoachRequest createRequest(PlayerTelemetry player, MatchTelemetry telemetry, Integer rankTier) {
        return new CoachRequest(
                player.accountId(),
                null,
                List.of(),
                telemetry,
                player,
                rankTier,
                HEROES,
                List.of()
        );
    }
}
