package sntiago05.rejuv.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.dto.MatchHistoryEntry;
import sntiago05.rejuv.clients.dto.PerformanceCurvePoint;
import sntiago05.rejuv.domain.MatchTelemetry;
import sntiago05.rejuv.domain.PlayerTelemetry;
import sntiago05.rejuv.domain.SnapshotDetail;
import sntiago05.rejuv.domain.StatSnapshot;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class PhasesCoachProviderTest {

    private static final int HERO_ID = 1;
    private static final int RANK_TIER = 7;
    private static final long ACCOUNT_ID = 1000001L;

    private DeadlockApiClient deadlockApiClient;
    private PhasesCoachProvider provider;

    @BeforeEach
    void setUp() {
        deadlockApiClient = mock(DeadlockApiClient.class);
        provider = new PhasesCoachProvider(deadlockApiClient);
    }

    private static PerformanceCurvePoint curvePoint(int gameTime, double nwAvg, double nwStd,
                                                    double dmgAvg, double dmgStd,
                                                    double farmAvg, double farmStd) {
        return new PerformanceCurvePoint(
                gameTime, nwAvg, nwStd,
                5.0, 1.0, 3.0, 1.0, 4.0, 1.0,
                dmgAvg, dmgStd, 2.0, 1.0,
                farmAvg, farmStd
        );
    }

    private static StatSnapshot snapshot(int timeS, long netWorth, long damage, Integer creepKills) {
        SnapshotDetail detail = creepKills == null ? null : new SnapshotDetail(
                1, 0, 2, creepKills, 100, 50, 10, 5, 1000L, 500L, 500L, 100L, 0L
        );
        return new StatSnapshot(timeS, netWorth, damage, 100L, detail);
    }

    private static CoachRequest buildRequest(int durationS, List<StatSnapshot> selfSnapshots,
                                             List<PlayerTelemetry> otherPlayers) {
        PlayerTelemetry self = new PlayerTelemetry(
                ACCOUNT_ID, 0, HERO_ID,
                List.of(), selfSnapshots, List.of(),
                0, 1, List.of()
        );
        List<PlayerTelemetry> allPlayers = new ArrayList<>();
        allPlayers.add(self);
        if (otherPlayers != null) {
            allPlayers.addAll(otherPlayers);
        }
        MatchTelemetry telemetry = new MatchTelemetry(900000001L, durationS, allPlayers);
        MatchHistoryEntry match = new MatchHistoryEntry(
                900000001L, HERO_ID, 0, 1700000000L, durationS,
                4, 0, 0, 1, 5, 3, 7, 30000L, 150, 10, 73
        );
        return new CoachRequest(ACCOUNT_ID, match, List.of(), telemetry, self, RANK_TIER, List.of(), List.of());
    }

    @Test
    void keyEqualsPhases() {
        assertThat(provider.key()).isEqualTo("phases");
    }

    @Test
    void fortyMinuteMatchWithAllFourCheckpoints() {
        // Curve points at 360, 720, 1200, 1800
        List<PerformanceCurvePoint> curve = List.of(
                curvePoint(360, 3777.0, 537.27, 2000.0, 777.78, 35.0, 7.69),
                curvePoint(720, 8500.0, 1000.0, 6000.0, 1000.0, 90.0, 15.0),
                curvePoint(1200, 17000.0, 2000.0, 14000.0, 2000.0, 170.0, 25.0),
                curvePoint(1800, 27000.0, 3000.0, 24000.0, 3000.0, 260.0, 30.0)
        );
        when(deadlockApiClient.getPerformanceCurve(HERO_ID, RANK_TIER)).thenReturn(curve);

        List<StatSnapshot> selfSnapshots = List.of(
                snapshot(360, 4368, 2700, 45),
                snapshot(720, 10000, 7000, 110),
                snapshot(1200, 22000, 18000, 200),
                snapshot(1800, 26000, 25000, 250)
        );

        PlayerTelemetry teammate = new PlayerTelemetry(
                1000002L, 0, 2, List.of(),
                List.of(
                        snapshot(360, 4037, 1500, 30),
                        snapshot(720, 9000, 5000, 80),
                        snapshot(1200, 18000, 12000, 150),
                        snapshot(1800, 27000, 20000, 220)
                ),
                List.of(), 1, 1, List.of()
        );

        CoachRequest request = buildRequest(2400, selfSnapshots, List.of(teammate));

        Optional<CoachInsight> insightOpt = provider.provide(request);
        assertThat(insightOpt).isPresent();

        CoachInsight insight = insightOpt.get();
        assertThat(insight.key()).isEqualTo("phases");
        assertThat(insight.payload()).isInstanceOf(PhasesCoachProvider.Payload.class);

        PhasesCoachProvider.Payload payload = (PhasesCoachProvider.Payload) insight.payload();

        // Checkpoints verification
        assertThat(payload.checkpoints()).hasSize(4);

        // Checkpoint 0: minute 6
        PhasesCoachProvider.Checkpoint cp6 = payload.checkpoints().get(0);
        assertThat(cp6.minute()).isEqualTo(6);
        assertThat(cp6.netWorth()).isEqualTo(4368);
        assertThat(cp6.netWorthAvg()).isEqualTo(3777);
        assertThat(cp6.netWorthZ()).isEqualTo(1.1);
        assertThat(cp6.damageZ()).isEqualTo(0.9);
        assertThat(cp6.farmZ()).isEqualTo(1.3);
        assertThat(cp6.vsTeammatesPct()).isEqualTo(8.2);

        // Checkpoint 1: minute 12
        PhasesCoachProvider.Checkpoint cp12 = payload.checkpoints().get(1);
        assertThat(cp12.minute()).isEqualTo(12);
        assertThat(cp12.netWorth()).isEqualTo(10000);
        assertThat(cp12.netWorthAvg()).isEqualTo(8500);
        assertThat(cp12.netWorthZ()).isEqualTo(1.5);
        assertThat(cp12.damageZ()).isEqualTo(1.0);
        assertThat(cp12.farmZ()).isEqualTo(1.3);
        assertThat(cp12.vsTeammatesPct()).isEqualTo(11.1);

        // Checkpoint 2: minute 20
        PhasesCoachProvider.Checkpoint cp20 = payload.checkpoints().get(2);
        assertThat(cp20.minute()).isEqualTo(20);
        assertThat(cp20.netWorth()).isEqualTo(22000);
        assertThat(cp20.netWorthAvg()).isEqualTo(17000);
        assertThat(cp20.netWorthZ()).isEqualTo(2.5);
        assertThat(cp20.damageZ()).isEqualTo(2.0);
        assertThat(cp20.farmZ()).isEqualTo(1.2);
        assertThat(cp20.vsTeammatesPct()).isEqualTo(22.2);

        // Checkpoint 3: minute 30
        PhasesCoachProvider.Checkpoint cp30 = payload.checkpoints().get(3);
        assertThat(cp30.minute()).isEqualTo(30);
        assertThat(cp30.netWorth()).isEqualTo(26000);
        assertThat(cp30.netWorthAvg()).isEqualTo(27000);
        assertThat(cp30.netWorthZ()).isEqualTo(-0.3);
        assertThat(cp30.damageZ()).isEqualTo(0.3);
        assertThat(cp30.farmZ()).isEqualTo(-0.3);
        assertThat(cp30.vsTeammatesPct()).isEqualTo(-3.7);

        // Phases: laning, mid, late
        assertThat(payload.phases()).hasSize(3);
        assertThat(payload.phases().get(0)).isEqualTo(new PhasesCoachProvider.Phase("laning", 0.4));
        assertThat(payload.phases().get(1)).isEqualTo(new PhasesCoachProvider.Phase("mid", 1.0));
        assertThat(payload.phases().get(2)).isEqualTo(new PhasesCoachProvider.Phase("late", -2.8));

        // Strongest & weakest phases
        assertThat(payload.strongestPhase()).isEqualTo("mid");
        assertThat(payload.weakestPhase()).isEqualTo("late");

        // Significance: major because cp20 net_worth_z = 2.5 (>= 2.0) and late change = -2.8 (|change| >= 2.0)
        assertThat(payload.significance()).isEqualTo("major");
    }

    @Test
    void fifteenMinuteMatchOnlyHasLaningPhase() {
        List<PerformanceCurvePoint> curve = List.of(
                curvePoint(360, 3777.0, 500.0, 2000.0, 500.0, 35.0, 10.0),
                curvePoint(720, 8500.0, 1000.0, 6000.0, 1000.0, 90.0, 15.0),
                curvePoint(1200, 17000.0, 2000.0, 14000.0, 2000.0, 170.0, 25.0)
        );
        when(deadlockApiClient.getPerformanceCurve(HERO_ID, RANK_TIER)).thenReturn(curve);

        List<StatSnapshot> selfSnapshots = List.of(
                snapshot(360, 4000, 2000, 35),
                snapshot(720, 8800, 6000, 90)
        );

        CoachRequest request = buildRequest(900, selfSnapshots, List.of()); // 15 min match = 900s
        Optional<CoachInsight> insightOpt = provider.provide(request);

        assertThat(insightOpt).isPresent();
        PhasesCoachProvider.Payload payload = (PhasesCoachProvider.Payload) insightOpt.get().payload();

        assertThat(payload.checkpoints()).hasSize(2);
        assertThat(payload.checkpoints().get(0).minute()).isEqualTo(6);
        assertThat(payload.checkpoints().get(1).minute()).isEqualTo(12);

        assertThat(payload.phases()).hasSize(1);
        assertThat(payload.phases().get(0).name()).isEqualTo("laning");
        assertThat(payload.phases().get(0).change()).isEqualTo(-0.1);

        assertThat(payload.strongestPhase()).isNull();
        assertThat(payload.weakestPhase()).isNull();
        assertThat(payload.significance()).isEqualTo("none");
    }

    @Test
    void checkpointSkippedWhenSnapshotOrCurveMissingOrStdZero() {
        // Curve missing 1200, and 1800 has netWorthStd == 0
        List<PerformanceCurvePoint> curve = List.of(
                curvePoint(360, 3777.0, 500.0, 2000.0, 500.0, 35.0, 10.0),
                curvePoint(720, 8500.0, 1000.0, 6000.0, 1000.0, 90.0, 15.0),
                curvePoint(1800, 27000.0, 0.0, 24000.0, 3000.0, 260.0, 30.0) // netWorthStd == 0
        );
        when(deadlockApiClient.getPerformanceCurve(HERO_ID, RANK_TIER)).thenReturn(curve);

        // Snapshot missing 720
        List<StatSnapshot> selfSnapshots = List.of(
                snapshot(360, 4000, 2000, 35),
                snapshot(1200, 17000, 14000, 170),
                snapshot(1800, 27000, 24000, 260)
        );

        // 360 is valid. 720 missing snapshot. 1200 missing curve point. 1800 std <= 0.
        // Total valid checkpoints = 1 (< 2 required) -> returns empty
        CoachRequest request = buildRequest(2400, selfSnapshots, List.of());
        Optional<CoachInsight> insightOpt = provider.provide(request);

        assertThat(insightOpt).isEmpty();
    }

    @Test
    void detailNullOmitsFarmZ() {
        List<PerformanceCurvePoint> curve = List.of(
                curvePoint(360, 3777.0, 500.0, 2000.0, 500.0, 35.0, 10.0),
                curvePoint(720, 8500.0, 1000.0, 6000.0, 1000.0, 90.0, 15.0)
        );
        when(deadlockApiClient.getPerformanceCurve(HERO_ID, RANK_TIER)).thenReturn(curve);

        List<StatSnapshot> selfSnapshots = List.of(
                snapshot(360, 4000, 2000, null), // detail is null
                snapshot(720, 8800, 6000, null)
        );

        CoachRequest request = buildRequest(900, selfSnapshots, List.of());
        Optional<CoachInsight> insightOpt = provider.provide(request);

        assertThat(insightOpt).isPresent();
        PhasesCoachProvider.Payload payload = (PhasesCoachProvider.Payload) insightOpt.get().payload();

        for (PhasesCoachProvider.Checkpoint cp : payload.checkpoints()) {
            assertThat(cp.farmZ()).isNull();
            assertThat(cp.damageZ()).isNotNull();
            assertThat(cp.netWorthZ()).isNotNull();
        }
    }

    @Test
    void damageStdZeroOmitsDamageZ() {
        List<PerformanceCurvePoint> curve = List.of(
                curvePoint(360, 3777.0, 500.0, 2000.0, 0.0, 35.0, 10.0), // dmgStd == 0
                curvePoint(720, 8500.0, 1000.0, 6000.0, 1000.0, 90.0, 15.0)
        );
        when(deadlockApiClient.getPerformanceCurve(HERO_ID, RANK_TIER)).thenReturn(curve);

        List<StatSnapshot> selfSnapshots = List.of(
                snapshot(360, 4000, 2000, 35),
                snapshot(720, 8800, 6000, 90)
        );

        CoachRequest request = buildRequest(900, selfSnapshots, List.of());
        Optional<CoachInsight> insightOpt = provider.provide(request);

        assertThat(insightOpt).isPresent();
        PhasesCoachProvider.Payload payload = (PhasesCoachProvider.Payload) insightOpt.get().payload();

        assertThat(payload.checkpoints().get(0).damageZ()).isNull();
        assertThat(payload.checkpoints().get(1).damageZ()).isNotNull();
    }

    @Test
    void teammateComparisonVariants() {
        List<PerformanceCurvePoint> curve = List.of(
                curvePoint(360, 3777.0, 500.0, 2000.0, 500.0, 35.0, 10.0),
                curvePoint(720, 8500.0, 1000.0, 6000.0, 1000.0, 90.0, 15.0)
        );
        when(deadlockApiClient.getPerformanceCurve(HERO_ID, RANK_TIER)).thenReturn(curve);

        List<StatSnapshot> selfSnapshots = List.of(
                snapshot(360, 5000, 2000, 35),
                snapshot(720, 10000, 6000, 90)
        );

        // Case 1: 0 teammates
        CoachRequest req0 = buildRequest(900, selfSnapshots, List.of());
        Optional<CoachInsight> insight0 = provider.provide(req0);
        assertThat(insight0).isPresent();
        PhasesCoachProvider.Payload payload0 = (PhasesCoachProvider.Payload) insight0.get().payload();
        assertThat(payload0.checkpoints().get(0).vsTeammatesPct()).isNull();
        assertThat(payload0.checkpoints().get(1).vsTeammatesPct()).isNull();

        // Case 2: 1 teammate
        PlayerTelemetry tm1 = new PlayerTelemetry(
                2001L, 0, 2, List.of(),
                List.of(snapshot(360, 4000, 1000, 30), snapshot(720, 8000, 5000, 80)),
                List.of(), 1, 1, List.of()
        );
        CoachRequest req1 = buildRequest(900, selfSnapshots, List.of(tm1));
        Optional<CoachInsight> insight1 = provider.provide(req1);
        assertThat(insight1).isPresent();
        PhasesCoachProvider.Payload payload1 = (PhasesCoachProvider.Payload) insight1.get().payload();
        // (5000 - 4000) / 4000 * 100 = 25.0%
        assertThat(payload1.checkpoints().get(0).vsTeammatesPct()).isEqualTo(25.0);
        // (10000 - 8000) / 8000 * 100 = 25.0%
        assertThat(payload1.checkpoints().get(1).vsTeammatesPct()).isEqualTo(25.0);

        // Case 3: 5 teammates, one without snapshot at 360s, one enemy player (team 1)
        PlayerTelemetry tm2 = new PlayerTelemetry(
                2002L, 0, 3, List.of(),
                List.of(snapshot(360, 4000, 1000, 30), snapshot(720, 8000, 5000, 80)),
                List.of(), 2, 2, List.of()
        );
        PlayerTelemetry tm3 = new PlayerTelemetry(
                2003L, 0, 4, List.of(),
                List.of(snapshot(360, 4000, 1000, 30), snapshot(720, 8000, 5000, 80)),
                List.of(), 3, 2, List.of()
        );
        PlayerTelemetry tm4 = new PlayerTelemetry(
                2004L, 0, 5, List.of(),
                List.of(snapshot(360, 4000, 1000, 30), snapshot(720, 8000, 5000, 80)),
                List.of(), 4, 3, List.of()
        );
        // tm5 lacks snapshot at 360s
        PlayerTelemetry tm5 = new PlayerTelemetry(
                2005L, 0, 6, List.of(),
                List.of(snapshot(720, 8000, 5000, 80)),
                List.of(), 5, 3, List.of()
        );
        // Enemy player on team 1
        PlayerTelemetry enemy = new PlayerTelemetry(
                3001L, 1, 7, List.of(),
                List.of(snapshot(360, 1000, 1000, 10), snapshot(720, 2000, 2000, 20)),
                List.of(), 6, 1, List.of()
        );

        CoachRequest req5 = buildRequest(900, selfSnapshots, List.of(tm1, tm2, tm3, tm4, tm5, enemy));
        Optional<CoachInsight> insight5 = provider.provide(req5);
        assertThat(insight5).isPresent();
        PhasesCoachProvider.Payload payload5 = (PhasesCoachProvider.Payload) insight5.get().payload();
        // At 360s: tm1..tm4 each have 4000 -> avg = 4000 -> (5000 - 4000) / 4000 * 100 = 25.0
        assertThat(payload5.checkpoints().get(0).vsTeammatesPct()).isEqualTo(25.0);
        // At 720s: tm1..tm5 each have 8000 -> avg = 8000 -> (10000 - 8000) / 8000 * 100 = 25.0
        assertThat(payload5.checkpoints().get(1).vsTeammatesPct()).isEqualTo(25.0);
    }

    @Test
    void singleCurveCallAndNoOtherInteractionsAndEmptyCurveReturnsEmpty() {
        when(deadlockApiClient.getPerformanceCurve(HERO_ID, RANK_TIER)).thenReturn(List.of());

        List<StatSnapshot> selfSnapshots = List.of(
                snapshot(360, 4000, 2000, 35),
                snapshot(720, 8800, 6000, 90)
        );
        CoachRequest request = buildRequest(900, selfSnapshots, List.of());

        Optional<CoachInsight> insight = provider.provide(request);
        assertThat(insight).isEmpty();

        verify(deadlockApiClient, times(1)).getPerformanceCurve(HERO_ID, RANK_TIER);
        verifyNoMoreInteractions(deadlockApiClient);
    }

    @Test
    void missingDataReturnsEmpty() {
        List<StatSnapshot> selfSnapshots = List.of(
                snapshot(360, 4000, 2000, 35),
                snapshot(720, 8800, 6000, 90)
        );
        CoachRequest normal = buildRequest(900, selfSnapshots, List.of());

        assertThat(provider.provide(null)).isEmpty();
        assertThat(provider.provide(new CoachRequest(ACCOUNT_ID, normal.match(), List.of(), null, normal.player(), RANK_TIER, List.of(), List.of()))).isEmpty();
        assertThat(provider.provide(new CoachRequest(ACCOUNT_ID, normal.match(), List.of(), normal.telemetry(), null, RANK_TIER, List.of(), List.of()))).isEmpty();
        assertThat(provider.provide(new CoachRequest(ACCOUNT_ID, normal.match(), List.of(), normal.telemetry(), normal.player(), null, List.of(), List.of()))).isEmpty();
        assertThat(provider.provide(new CoachRequest(ACCOUNT_ID, normal.match(), List.of(), normal.telemetry(), normal.player(), -1, List.of(), List.of()))).isEmpty();
        assertThat(provider.provide(new CoachRequest(ACCOUNT_ID, normal.match(), List.of(), normal.telemetry(), normal.player(), 12, List.of(), List.of()))).isEmpty();

        // Match shorter than 360s
        CoachRequest shortMatch = buildRequest(300, selfSnapshots, List.of());
        when(deadlockApiClient.getPerformanceCurve(HERO_ID, RANK_TIER)).thenReturn(List.of(
                curvePoint(360, 3777.0, 500.0, 2000.0, 500.0, 35.0, 10.0),
                curvePoint(720, 8500.0, 1000.0, 6000.0, 1000.0, 90.0, 15.0)
        ));
        assertThat(provider.provide(shortMatch)).isEmpty();
    }

    @Test
    void significanceThresholdBoundaries() {
        // Curve: nwAvg = 1000, nwStd = 1000 -> z = (nw - 1000) / 1000
        List<PerformanceCurvePoint> curve = List.of(
                curvePoint(360, 1000.0, 1000.0, 1000.0, 100.0, 10.0, 5.0),
                curvePoint(720, 1000.0, 1000.0, 1000.0, 100.0, 10.0, 5.0)
        );
        when(deadlockApiClient.getPerformanceCurve(anyInt(), anyInt())).thenReturn(curve);

        // 1. |z| = 0.9, phase change = 0.0 -> significance none
        CoachRequest reqNone = buildRequest(900, List.of(snapshot(360, 1900, 1000, 10), snapshot(720, 1900, 1000, 10)), List.of());
        assertThat(((PhasesCoachProvider.Payload) provider.provide(reqNone).get().payload()).significance()).isEqualTo("none");

        // 2. |z| = 1.0 (at notable threshold), phase change = 0.0 -> notable
        CoachRequest reqNotableZ = buildRequest(900, List.of(snapshot(360, 2000, 1000, 10), snapshot(720, 2000, 1000, 10)), List.of());
        assertThat(((PhasesCoachProvider.Payload) provider.provide(reqNotableZ).get().payload()).significance()).isEqualTo("notable");

        // 3. |z| = 1.9, phase change = 0.0 -> notable
        CoachRequest reqNotable19 = buildRequest(900, List.of(snapshot(360, 2900, 1000, 10), snapshot(720, 2900, 1000, 10)), List.of());
        assertThat(((PhasesCoachProvider.Payload) provider.provide(reqNotable19).get().payload()).significance()).isEqualTo("notable");

        // 4. |z| = 2.0 (at major threshold) -> major
        CoachRequest reqMajorZ = buildRequest(900, List.of(snapshot(360, 3000, 1000, 10), snapshot(720, 3000, 1000, 10)), List.of());
        assertThat(((PhasesCoachProvider.Payload) provider.provide(reqMajorZ).get().payload()).significance()).isEqualTo("major");

        // 5. Negative z-score: z = -2.0 -> major
        CoachRequest reqMajorNegZ = buildRequest(900, List.of(snapshot(360, -1000, 1000, 10), snapshot(720, -1000, 1000, 10)), List.of());
        assertThat(((PhasesCoachProvider.Payload) provider.provide(reqMajorNegZ).get().payload()).significance()).isEqualTo("major");

        // 6. Phase change triggers significance:
        // z(360) = 0.0 (nw 1000), z(720) = 1.0 (nw 2000) -> change = 1.0 -> notable
        CoachRequest reqPhaseNotable = buildRequest(900, List.of(snapshot(360, 1000, 1000, 10), snapshot(720, 2000, 1000, 10)), List.of());
        assertThat(((PhasesCoachProvider.Payload) provider.provide(reqPhaseNotable).get().payload()).significance()).isEqualTo("notable");

        // z(360) = 0.0 (nw 1000), z(720) = 2.0 (nw 3000) -> change = 2.0 -> major
        CoachRequest reqPhaseMajor = buildRequest(900, List.of(snapshot(360, 1000, 1000, 10), snapshot(720, 3000, 1000, 10)), List.of());
        assertThat(((PhasesCoachProvider.Payload) provider.provide(reqPhaseMajor).get().payload()).significance()).isEqualTo("major");

        // Negative phase change: z(360) = 0.0, z(720) = -2.0 (nw -1000) -> change = -2.0 -> major
        CoachRequest reqPhaseNegMajor = buildRequest(900, List.of(snapshot(360, 1000, 1000, 10), snapshot(720, -1000, 1000, 10)), List.of());
        assertThat(((PhasesCoachProvider.Payload) provider.provide(reqPhaseNegMajor).get().payload()).significance()).isEqualTo("major");
    }

    @Test
    void phaseSwingBoundariesAndTies() {
        List<PerformanceCurvePoint> curve = List.of(
                curvePoint(360, 1000.0, 1000.0, 1000.0, 100.0, 10.0, 5.0),
                curvePoint(720, 1000.0, 1000.0, 1000.0, 100.0, 10.0, 5.0),
                curvePoint(1200, 1000.0, 1000.0, 1000.0, 100.0, 10.0, 5.0),
                curvePoint(1800, 1000.0, 1000.0, 1000.0, 100.0, 10.0, 5.0)
        );
        when(deadlockApiClient.getPerformanceCurve(anyInt(), anyInt())).thenReturn(curve);

        // Test boundary at 0.9 (below swing) vs 1.0 (at swing)
        // cp360 = 0.0, cp720 = 0.9 -> change = 0.9 (< 1.0) -> strongest null
        CoachRequest reqSwing09 = buildRequest(900, List.of(snapshot(360, 1000, 1000, 10), snapshot(720, 1900, 1000, 10)), List.of());
        PhasesCoachProvider.Payload payload09 = (PhasesCoachProvider.Payload) provider.provide(reqSwing09).get().payload();
        assertThat(payload09.strongestPhase()).isNull();

        // cp360 = 0.0, cp720 = 1.0 -> change = 1.0 (>= 1.0) -> strongest "laning"
        CoachRequest reqSwing10 = buildRequest(900, List.of(snapshot(360, 1000, 1000, 10), snapshot(720, 2000, 1000, 10)), List.of());
        PhasesCoachProvider.Payload payload10 = (PhasesCoachProvider.Payload) provider.provide(reqSwing10).get().payload();
        assertThat(payload10.strongestPhase()).isEqualTo("laning");

        // Negative swing: cp360 = 0.0, cp720 = -0.9 -> change = -0.9 (> -1.0) -> weakest null
        CoachRequest reqWeak09 = buildRequest(900, List.of(snapshot(360, 1000, 1000, 10), snapshot(720, 100, 1000, 10)), List.of());
        PhasesCoachProvider.Payload payloadWeak09 = (PhasesCoachProvider.Payload) provider.provide(reqWeak09).get().payload();
        assertThat(payloadWeak09.weakestPhase()).isNull();

        // cp360 = 0.0, cp720 = -1.0 -> change = -1.0 (<= -1.0) -> weakest "laning"
        CoachRequest reqWeak10 = buildRequest(900, List.of(snapshot(360, 1000, 1000, 10), snapshot(720, 0, 1000, 10)), List.of());
        PhasesCoachProvider.Payload payloadWeak10 = (PhasesCoachProvider.Payload) provider.provide(reqWeak10).get().payload();
        assertThat(payloadWeak10.weakestPhase()).isEqualTo("laning");

        // Tie breaking: earlier phase first
        // laning: 0.0 -> 1.5 (change = 1.5)
        // mid: 1.5 -> 3.0 (change = 1.5)
        // Both >= 1.0 and equal. "laning" should win.
        List<StatSnapshot> tieSnapshots = List.of(
                snapshot(360, 1000, 1000, 10), // z = 0.0
                snapshot(720, 2500, 1000, 10), // z = 1.5 (laning change = 1.5)
                snapshot(1200, 4000, 1000, 10)  // z = 3.0 (mid change = 1.5)
        );
        CoachRequest reqTie = buildRequest(1500, tieSnapshots, List.of());
        PhasesCoachProvider.Payload payloadTie = (PhasesCoachProvider.Payload) provider.provide(reqTie).get().payload();
        assertThat(payloadTie.strongestPhase()).isEqualTo("laning");

        // Weakest tie: earlier phase first
        // laning: 0.0 -> -1.5 (change = -1.5)
        // mid: -1.5 -> -3.0 (change = -1.5)
        List<StatSnapshot> weakTieSnapshots = List.of(
                snapshot(360, 1000, 1000, 10),  // z = 0.0
                snapshot(720, -500, 1000, 10),  // z = -1.5 (laning change = -1.5)
                snapshot(1200, -2000, 1000, 10) // z = -3.0 (mid change = -1.5)
        );
        CoachRequest reqWeakTie = buildRequest(1500, weakTieSnapshots, List.of());
        PhasesCoachProvider.Payload payloadWeakTie = (PhasesCoachProvider.Payload) provider.provide(reqWeakTie).get().payload();
        assertThat(payloadWeakTie.weakestPhase()).isEqualTo("laning");
    }

    @Test
    void payloadSerializationAndSizeUnder1200Chars() {
        List<PerformanceCurvePoint> curve = List.of(
                curvePoint(360, 3777.0, 537.27, 2000.0, 777.78, 35.0, 7.69),
                curvePoint(720, 8500.0, 1000.0, 6000.0, 1000.0, 90.0, 15.0),
                curvePoint(1200, 17000.0, 2000.0, 14000.0, 2000.0, 170.0, 25.0),
                curvePoint(1800, 27000.0, 3000.0, 24000.0, 3000.0, 260.0, 30.0)
        );
        when(deadlockApiClient.getPerformanceCurve(HERO_ID, RANK_TIER)).thenReturn(curve);

        List<StatSnapshot> selfSnapshots = List.of(
                snapshot(360, 4368, 2700, 45),
                snapshot(720, 10000, 7000, 110),
                snapshot(1200, 22000, 18000, 200),
                snapshot(1800, 26000, 25000, 250)
        );
        PlayerTelemetry teammate = new PlayerTelemetry(
                1000002L, 0, 2, List.of(),
                List.of(
                        snapshot(360, 4037, 1500, 30),
                        snapshot(720, 9000, 5000, 80),
                        snapshot(1200, 18000, 12000, 150),
                        snapshot(1800, 27000, 20000, 220)
                ),
                List.of(), 1, 1, List.of()
        );

        CoachRequest request = buildRequest(2400, selfSnapshots, List.of(teammate));
        Optional<CoachInsight> insightOpt = provider.provide(request);
        assertThat(insightOpt).isPresent();

        JsonMapper mapper = new JsonMapper();
        String json = mapper.writeValueAsString(insightOpt.get().payload());

        assertThat(json.length()).isLessThanOrEqualTo(1200);

        JsonNode root = mapper.readTree(json);
        assertThat(root.has("significance")).isTrue();
        assertThat(root.has("checkpoints")).isTrue();
        assertThat(root.has("phases")).isTrue();
        assertThat(root.has("weakest_phase")).isTrue();
        assertThat(root.has("strongest_phase")).isTrue();
        assertThat(root.get("checkpoints")).hasSize(4);
        assertThat(root.get("phases")).hasSize(3);
    }

    @Test
    void deterministicOutputWithoutMutatingRequest() {
        List<PerformanceCurvePoint> curve = List.of(
                curvePoint(360, 3777.0, 500.0, 2000.0, 500.0, 35.0, 10.0),
                curvePoint(720, 8500.0, 1000.0, 6000.0, 1000.0, 90.0, 15.0)
        );
        when(deadlockApiClient.getPerformanceCurve(HERO_ID, RANK_TIER)).thenReturn(curve);

        List<StatSnapshot> selfSnapshots = List.of(
                snapshot(360, 4000, 2000, 35),
                snapshot(720, 8800, 6000, 90)
        );
        CoachRequest request = buildRequest(900, selfSnapshots, List.of());

        Optional<CoachInsight> first = provider.provide(request);
        Optional<CoachInsight> second = provider.provide(request);

        assertThat(first).isPresent();
        assertThat(second).isPresent();
        assertThat(first.get().key()).isEqualTo(second.get().key());
        assertThat(first.get().payload()).isEqualTo(second.get().payload());
    }
}
