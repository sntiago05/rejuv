package sntiago05.rejuv.services;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.DeadlockApiUnavailableException;
import sntiago05.rejuv.clients.dto.HeroAsset;
import sntiago05.rejuv.clients.dto.PerformanceCurvePoint;
import sntiago05.rejuv.domain.DeathEvent;
import sntiago05.rejuv.domain.MatchTelemetry;
import sntiago05.rejuv.domain.PlayerTelemetry;
import sntiago05.rejuv.domain.SnapshotDetail;
import sntiago05.rejuv.domain.StatSnapshot;
import sntiago05.rejuv.services.DeathsCoachProvider.DeathsPayload;
import sntiago05.rejuv.services.DeathsCoachProvider.PhaseDeaths;
import sntiago05.rejuv.services.DeathsCoachProvider.TopKiller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeathsCoachProviderTest {

    private static final long MATCH_ID = 900000001L;
    private static final long TARGET_ACCOUNT_ID = 1000001L;
    private static final int TARGET_HERO_ID = 1;
    private static final int TARGET_SLOT = 0;
    private static final int TARGET_TEAM = 0;

    private DeadlockApiClient deadlockApiClient;
    private DeathsCoachProvider provider;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        deadlockApiClient = mock(DeadlockApiClient.class);
        provider = new DeathsCoachProvider(deadlockApiClient);
        objectMapper = new JsonMapper();
    }

    @Test
    void keyMatchesContract() {
        assertThat(provider.key()).isEqualTo("deaths");
    }

    @Test
    void happyPathRealisticMatchMatchesExpectedJsonAndSizeLimit() throws Exception {
        int durationS = 2450;
        int rankTier = 5;

        // 9 deaths:
        // laning (2): 200 (respawn 15), 300 (respawn 20)
        // mid (3): 800 (respawn 35), 850 (respawn 40), 1000 (respawn 50)
        // late (4): 1300 (respawn 58), 1350 (respawn 62), 1600 (respawn 65), 1900 (respawn 65)
        // Sum respawn = 15 + 20 + 35 + 40 + 50 + 58 + 62 + 65 + 65 = 410 s
        // Longest respawn = 65 (let's calibrate to spec example 62 by using:
        // 15, 20, 35, 40, 50, 60, 62, 64 -> wait, spec example has longest 62, sum 410.
        // Let's use: 15, 20, 35, 40, 50, 62, 62, 63? Wait, 62 max:
        // 15 + 20 + 35 + 40 + 50 + 62 + 62 + 62 + 64? Wait:
        // 20 + 25 + 35 + 45 + 50 + 55 + 58 + 60 + 62 = 410! Max is 62!
        List<DeathEvent> deaths = List.of(
                new DeathEvent(200, 6, 10.0, 20),
                new DeathEvent(300, 6, 10.0, 25),
                new DeathEvent(800, 6, 10.0, 35),
                new DeathEvent(850, 7, 10.0, 45), // back-to-back 1 (gap 50)
                new DeathEvent(1000, 6, 10.0, 50),
                new DeathEvent(1300, 8, 10.0, 55),
                new DeathEvent(1350, 9, 10.0, 58), // back-to-back 2 (gap 50)
                new DeathEvent(1600, 10, 10.0, 60),
                new DeathEvent(1900, 11, 10.0, 62)
        );

        // Snapshots: every 300 s up to 2400 s
        List<Integer> times = List.of(300, 600, 900, 1200, 1500, 1800, 2100, 2400);
        // At 1800 s, cumulative deaths = 8. Curve deathsAvg = 4.0, deathsStd = 2.5 -> z = (8 - 4.0) / 2.5 = 1.6
        List<StatSnapshot> targetSnapshots = new ArrayList<>();
        for (int t : times) {
            int cumulativeDeaths = t < 1800 ? 5 : (t == 1800 ? 8 : 9);
            long goldLoss = (t == 2400) ? 1850L : 1000L;
            SnapshotDetail detail = createDetailWithDeathsAndGoldLoss(cumulativeDeaths, goldLoss);
            targetSnapshots.add(new StatSnapshot(t, 20000L, 10000L, 0L, detail));
        }

        PlayerTelemetry targetPlayer = new PlayerTelemetry(
                TARGET_ACCOUNT_ID, TARGET_TEAM, TARGET_HERO_ID,
                List.of(), targetSnapshots, List.of(), TARGET_SLOT, 1, deaths
        );

        // 12 players:
        // Target player's death 1 is at 200 s, before first common time 300 s -> skipped.
        // Deaths evaluated: 8 (300, 800, 850, 1000, 1300, 1350, 1600, 1900).
        // Common times leads:
        // At 300: team 0 behind (-500) -> death at 300 behind (1)
        // At 600: team 0 behind (-1000)
        // At 800: floor is 600 -> behind (2)
        // At 850: floor is 600 -> behind (3)
        // At 900: team 0 behind (-1200)
        // At 1000: floor is 900 -> behind (4)
        // At 1200: team 0 behind (-800)
        // At 1300: floor is 1200 -> behind (5)
        // At 1350: floor is 1200 -> behind (6? wait, we want 5 behind out of 8!)
        // Let's set:
        // 300: behind (-500) -> death 300 behind (1)
        // 600: behind (-500) -> deaths 800, 850 behind (2, 3)
        // 900: behind (-500) -> death 1000 behind (4)
        // 1200: ahead (+500) -> deaths 1300, 1350 ahead
        // 1500: behind (-500) -> death 1600 behind (5)
        // 1800: ahead (+500) -> death 1900 ahead
        // Exactly 5 behind out of 8 evaluated!
        Map<Integer, Long> team0Nw = Map.of(
                300, 10000L,
                600, 20000L,
                900, 30000L,
                1200, 45000L,
                1500, 50000L,
                1800, 65000L,
                2100, 75000L,
                2400, 85000L
        );
        Map<Integer, Long> team1Nw = Map.of(
                300, 10500L, // -500
                600, 20500L, // -500
                900, 30500L, // -500
                1200, 40000L, // +5000
                1500, 50500L, // -500
                1800, 60000L, // +5000
                2100, 70000L,
                2400, 80000L
        );

        List<PlayerTelemetry> players = create12Players(targetPlayer, times, team0Nw, team1Nw);
        MatchTelemetry telemetry = new MatchTelemetry(MATCH_ID, durationS, players);

        // Killer heroes:
        // Slot 6: hero 10 ("Haze") with 4 kills (deaths at 200, 300, 800, 1000)
        // Total resolved = 9. 4 kills >= 3, and 4/9 = 44.4% >= 40% -> repeat_killer = true!
        List<HeroAsset> heroes = List.of(
                new HeroAsset(1, "hero_infernus", "Infernus"),
                new HeroAsset(10, "hero_haze", "Haze")
        );

        // Rank curve:
        PerformanceCurvePoint curvePoint = createCurvePoint(1800, 4.0, 2.5);
        when(deadlockApiClient.getPerformanceCurve(TARGET_HERO_ID, rankTier))
                .thenReturn(List.of(curvePoint));

        CoachRequest request = new CoachRequest(
                TARGET_ACCOUNT_ID, null, List.of(), telemetry, targetPlayer, rankTier, heroes, List.of()
        );

        Optional<CoachInsight> result = provider.provide(request);
        assertThat(result).isPresent();
        CoachInsight insight = result.get();
        assertThat(insight.key()).isEqualTo("deaths");

        DeathsPayload payload = (DeathsPayload) insight.payload();
        assertThat(payload.significance()).isEqualTo("major");
        assertThat(payload.total()).isEqualTo(9);
        assertThat(payload.byPhase()).isEqualTo(new PhaseDeaths(2, 3, 4));
        assertThat(payload.secondsDead()).isEqualTo(410);
        assertThat(payload.percentOfMatchDead()).isEqualTo(16.7);
        assertThat(payload.longestRespawnS()).isEqualTo(62);
        assertThat(payload.soulsLostOnDeath()).isEqualTo(1850L);
        assertThat(payload.backToBack()).isEqualTo(2);
        assertThat(payload.topKiller()).isEqualTo(new TopKiller("Haze", 4));
        assertThat(payload.repeatKiller()).isTrue();
        assertThat(payload.deathsWhileBehind()).isEqualTo(5);
        assertThat(payload.deathsCounted()).isEqualTo(8);
        assertThat(payload.deathsZ()).isEqualTo(1.6);

        String json = objectMapper.writeValueAsString(payload);
        assertThat(json.length()).isLessThanOrEqualTo(1200);

        JsonNode root = objectMapper.readTree(json);
        assertThat(root.get("significance").asText()).isEqualTo("major");
        assertThat(root.get("total").asInt()).isEqualTo(9);
        assertThat(root.get("by_phase").get("laning").asInt()).isEqualTo(2);
        assertThat(root.get("by_phase").get("mid").asInt()).isEqualTo(3);
        assertThat(root.get("by_phase").get("late").asInt()).isEqualTo(4);
        assertThat(root.get("seconds_dead").asInt()).isEqualTo(410);
        assertThat(root.get("percent_of_match_dead").asDouble()).isEqualTo(16.7);
        assertThat(root.get("longest_respawn_s").asInt()).isEqualTo(62);
        assertThat(root.get("souls_lost_on_death").asLong()).isEqualTo(1850L);
        assertThat(root.get("back_to_back").asInt()).isEqualTo(2);
        assertThat(root.get("top_killer").get("hero").asText()).isEqualTo("Haze");
        assertThat(root.get("top_killer").get("count").asInt()).isEqualTo(4);
        assertThat(root.get("repeat_killer").asBoolean()).isTrue();
        assertThat(root.get("deaths_while_behind").asInt()).isEqualTo(5);
        assertThat(root.get("deaths_counted").asInt()).isEqualTo(8);
        assertThat(root.get("deaths_z").asDouble()).isEqualTo(1.6);
    }

    @Test
    void missingDataReturnsEmpty() {
        // Null request
        assertThat(provider.provide(null)).isEmpty();

        PlayerTelemetry player = createPlayerWithDeaths(List.of(new DeathEvent(100, 1, 5.0, 10)));
        MatchTelemetry telemetry = new MatchTelemetry(MATCH_ID, 1800, List.of(player));

        // Null telemetry
        assertThat(provider.provide(new CoachRequest(TARGET_ACCOUNT_ID, null, List.of(), null, player, null, List.of(), List.of()))).isEmpty();

        // Null player
        assertThat(provider.provide(new CoachRequest(TARGET_ACCOUNT_ID, null, List.of(), telemetry, null, null, List.of(), List.of()))).isEmpty();

        // Duration <= 0
        MatchTelemetry zeroDuration = new MatchTelemetry(MATCH_ID, 0, List.of(player));
        assertThat(provider.provide(new CoachRequest(TARGET_ACCOUNT_ID, null, List.of(), zeroDuration, player, null, List.of(), List.of()))).isEmpty();

        MatchTelemetry negativeDuration = new MatchTelemetry(MATCH_ID, -100, List.of(player));
        assertThat(provider.provide(new CoachRequest(TARGET_ACCOUNT_ID, null, List.of(), negativeDuration, player, null, List.of(), List.of()))).isEmpty();
    }

    @Test
    void rawFallbackTelemetryWithAllNullDetailReturnsEmpty() {
        StatSnapshot s1 = new StatSnapshot(180, 1500L, 500L, 0L, null);
        StatSnapshot s2 = new StatSnapshot(360, 3000L, 1000L, 0L, null);
        PlayerTelemetry player = new PlayerTelemetry(
                TARGET_ACCOUNT_ID, TARGET_TEAM, TARGET_HERO_ID,
                List.of(), List.of(s1, s2), List.of(), TARGET_SLOT, 1,
                List.of(new DeathEvent(200, 1, 5.0, 15))
        );
        MatchTelemetry telemetry = new MatchTelemetry(MATCH_ID, 1800, List.of(player));
        CoachRequest request = new CoachRequest(TARGET_ACCOUNT_ID, null, List.of(), telemetry, player, null, List.of(), List.of());

        assertThat(provider.provide(request)).isEmpty();
    }

    @Test
    void zeroDeathsReturnsEmpty() {
        SnapshotDetail detail = createDetailWithDeathsAndGoldLoss(0, 0L);
        StatSnapshot s1 = new StatSnapshot(180, 1500L, 500L, 0L, detail);
        PlayerTelemetry player = new PlayerTelemetry(
                TARGET_ACCOUNT_ID, TARGET_TEAM, TARGET_HERO_ID,
                List.of(), List.of(s1), List.of(), TARGET_SLOT, 1, List.of()
        );
        MatchTelemetry telemetry = new MatchTelemetry(MATCH_ID, 1800, List.of(player));
        CoachRequest request = new CoachRequest(TARGET_ACCOUNT_ID, null, List.of(), telemetry, player, null, List.of(), List.of());

        assertThat(provider.provide(request)).isEmpty();
    }

    @Test
    void phaseBoundariesAtExactSeconds() {
        List<DeathEvent> deaths = List.of(
                new DeathEvent(719, null, null, 10),  // laning
                new DeathEvent(720, null, null, 10),  // mid
                new DeathEvent(1199, null, null, 10), // mid
                new DeathEvent(1200, null, null, 10)  // late
        );
        PlayerTelemetry player = createPlayerWithDeaths(deaths);
        MatchTelemetry telemetry = new MatchTelemetry(MATCH_ID, 1800, List.of(player));
        CoachRequest request = new CoachRequest(TARGET_ACCOUNT_ID, null, List.of(), telemetry, player, null, List.of(), List.of());

        Optional<CoachInsight> result = provider.provide(request);
        assertThat(result).isPresent();
        DeathsPayload payload = (DeathsPayload) result.get().payload();
        assertThat(payload.byPhase().laning()).isEqualTo(1);
        assertThat(payload.byPhase().mid()).isEqualTo(2);
        assertThat(payload.byPhase().late()).isEqualTo(1);
    }

    @Test
    void backToBackClustersWithGapsAndChainOfThree() {
        // Gaps:
        // 100 to 159: gap 59 s -> counts
        // 159 to 219: gap 60 s -> counts
        // 219 to 280: gap 61 s -> does not count
        // 400, 450, 500: chain of three (gaps 50, 50) -> counts 2
        List<DeathEvent> deaths = List.of(
                new DeathEvent(100, null, null, 10),
                new DeathEvent(159, null, null, 10), // gap 59 (+1)
                new DeathEvent(219, null, null, 10), // gap 60 (+1)
                new DeathEvent(280, null, null, 10), // gap 61 (+0)
                new DeathEvent(400, null, null, 10),
                new DeathEvent(450, null, null, 10), // gap 50 (+1)
                new DeathEvent(500, null, null, 10)  // gap 50 (+1)
        );
        PlayerTelemetry player = createPlayerWithDeaths(deaths);
        MatchTelemetry telemetry = new MatchTelemetry(MATCH_ID, 1800, List.of(player));
        CoachRequest request = new CoachRequest(TARGET_ACCOUNT_ID, null, List.of(), telemetry, player, null, List.of(), List.of());

        DeathsPayload payload = (DeathsPayload) provider.provide(request).orElseThrow().payload();
        assertThat(payload.backToBack()).isEqualTo(4);
    }

    @Test
    void killersHandlingUnresolvedSlotNullSlotAndTieBreaking() {
        // Killer slots:
        // death 1: null slot -> ignored
        // death 2: slot 99 (unresolved) -> ignored
        // death 3, 4, 5: slot 6 (hero 5, 3 kills)
        // death 6, 7, 8: slot 7 (hero 2, 3 kills)
        // Tie-breaker: hero 2 has smaller heroId -> hero 2 wins!
        List<DeathEvent> deaths = List.of(
                new DeathEvent(100, null, null, 10),
                new DeathEvent(200, 99, null, 10),
                new DeathEvent(300, 6, null, 10),
                new DeathEvent(400, 6, null, 10),
                new DeathEvent(500, 6, null, 10),
                new DeathEvent(600, 7, null, 10),
                new DeathEvent(700, 7, null, 10),
                new DeathEvent(800, 7, null, 10)
        );
        PlayerTelemetry target = createPlayerWithDeaths(deaths);
        PlayerTelemetry killerHero5 = new PlayerTelemetry(2001L, 1, 5, List.of(), List.of(), List.of(), 6, 2, List.of());
        PlayerTelemetry killerHero2 = new PlayerTelemetry(2002L, 1, 2, List.of(), List.of(), List.of(), 7, 3, List.of());

        MatchTelemetry telemetry = new MatchTelemetry(MATCH_ID, 1800, List.of(target, killerHero5, killerHero2));
        List<HeroAsset> heroes = List.of(
                new HeroAsset(5, "hero_abrams", "Abrams"),
                new HeroAsset(2, "hero_seven", "Seven")
        );

        CoachRequest request = new CoachRequest(TARGET_ACCOUNT_ID, null, List.of(), telemetry, target, null, heroes, List.of());
        DeathsPayload payload = (DeathsPayload) provider.provide(request).orElseThrow().payload();

        assertThat(payload.topKiller()).isNotNull();
        assertThat(payload.topKiller().hero()).isEqualTo("Seven");
        assertThat(payload.topKiller().count()).isEqualTo(3);
    }

    @Test
    void repeatKillerThresholds() {
        // Case 1: 2 kills out of 2 resolved -> 100% >= 40%, but count < 3 -> false
        List<DeathEvent> deaths2 = List.of(
                new DeathEvent(100, 6, null, 10),
                new DeathEvent(200, 6, null, 10)
        );
        PlayerTelemetry target1 = createPlayerWithDeaths(deaths2);
        PlayerTelemetry killer6 = new PlayerTelemetry(2001L, 1, 5, List.of(), List.of(), List.of(), 6, 1, List.of());
        MatchTelemetry tel1 = new MatchTelemetry(MATCH_ID, 1800, List.of(target1, killer6));
        DeathsPayload p1 = (DeathsPayload) provider.provide(new CoachRequest(TARGET_ACCOUNT_ID, null, List.of(), tel1, target1, null, List.of(), List.of())).orElseThrow().payload();
        assertThat(p1.repeatKiller()).isFalse();

        // Case 2: 3 kills out of 8 resolved -> count = 3 >= 3, but 3/8 = 37.5% < 40% -> false
        List<DeathEvent> deaths8 = new ArrayList<>();
        deaths8.add(new DeathEvent(100, 6, null, 10));
        deaths8.add(new DeathEvent(200, 6, null, 10));
        deaths8.add(new DeathEvent(300, 6, null, 10));
        for (int i = 0; i < 5; i++) {
            deaths8.add(new DeathEvent(400 + i * 10, 7 + i, null, 10));
        }
        PlayerTelemetry target2 = createPlayerWithDeaths(deaths8);
        List<PlayerTelemetry> killersList = new ArrayList<>(List.of(target2, killer6));
        for (int i = 0; i < 5; i++) {
            killersList.add(new PlayerTelemetry(2002L + i, 1, 10 + i, List.of(), List.of(), List.of(), 7 + i, 2, List.of()));
        }
        MatchTelemetry tel2 = new MatchTelemetry(MATCH_ID, 1800, killersList);
        DeathsPayload p2 = (DeathsPayload) provider.provide(new CoachRequest(TARGET_ACCOUNT_ID, null, List.of(), tel2, target2, null, List.of(), List.of())).orElseThrow().payload();
        assertThat(p2.repeatKiller()).isFalse();

        // Case 3: 39% vs 40% (39 kills out of 100 resolved -> false; 40 kills out of 100 resolved -> true)
        List<DeathEvent> deaths100A = new ArrayList<>();
        for (int i = 0; i < 39; i++) {
            deaths100A.add(new DeathEvent(100 + i, 6, null, 10));
        }
        for (int i = 0; i < 61; i++) {
            deaths100A.add(new DeathEvent(200 + i, 7, null, 10));
        }
        // In deaths100A: slot 7 has 61 kills (so slot 7 is top killer).
        // Let's test top killer specifically with 39 kills vs 61 other unique killers!
        List<DeathEvent> deaths100 = new ArrayList<>();
        for (int i = 0; i < 39; i++) {
            deaths100.add(new DeathEvent(100 + i, 6, null, 10));
        }
        // 61 other killers with 1 kill each on slot 7
        // Wait, slot 7 has hero 7, so hero 7 would have 61 kills!
        // To make hero 6 the top killer with 39 kills:
        // Use 61 different slots with heroIds 100..160 each having 1 kill!
        List<PlayerTelemetry> players100 = new ArrayList<>();
        players100.add(killer6); // slot 6, hero 5
        for (int i = 0; i < 61; i++) {
            int slot = 100 + i;
            deaths100.add(new DeathEvent(200 + i, slot, null, 10));
            players100.add(new PlayerTelemetry(3000L + i, 1, 100 + i, List.of(), List.of(), List.of(), slot, 1, List.of()));
        }
        PlayerTelemetry target39 = createPlayerWithDeaths(deaths100);
        players100.add(target39);
        MatchTelemetry tel39 = new MatchTelemetry(MATCH_ID, 1800, players100);
        DeathsPayload p39 = (DeathsPayload) provider.provide(new CoachRequest(TARGET_ACCOUNT_ID, null, List.of(), tel39, target39, null, List.of(), List.of())).orElseThrow().payload();
        assertThat(p39.topKiller().count()).isEqualTo(39);
        assertThat(p39.repeatKiller()).isFalse(); // 39% < 40%

        // Now with 40 kills out of 100:
        List<DeathEvent> deaths100B = new ArrayList<>();
        List<PlayerTelemetry> players100B = new ArrayList<>();
        players100B.add(killer6);
        for (int i = 0; i < 40; i++) {
            deaths100B.add(new DeathEvent(100 + i, 6, null, 10));
        }
        for (int i = 0; i < 60; i++) {
            int slot = 100 + i;
            deaths100B.add(new DeathEvent(200 + i, slot, null, 10));
            players100B.add(new PlayerTelemetry(3000L + i, 1, 100 + i, List.of(), List.of(), List.of(), slot, 1, List.of()));
        }
        PlayerTelemetry target40 = createPlayerWithDeaths(deaths100B);
        players100B.add(target40);
        MatchTelemetry tel40 = new MatchTelemetry(MATCH_ID, 1800, players100B);
        DeathsPayload p40 = (DeathsPayload) provider.provide(new CoachRequest(TARGET_ACCOUNT_ID, null, List.of(), tel40, target40, null, List.of(), List.of())).orElseThrow().payload();
        assertThat(p40.topKiller().count()).isEqualTo(40);
        assertThat(p40.repeatKiller()).isTrue(); // 40% >= 40%

        // Case 4: 3 kills out of 7 resolved -> 3/7 = 42.8% >= 40% and 3 >= 3 -> true
        List<DeathEvent> deaths7 = new ArrayList<>();
        List<PlayerTelemetry> players7 = new ArrayList<>();
        players7.add(killer6);
        for (int i = 0; i < 3; i++) {
            deaths7.add(new DeathEvent(100 + i, 6, null, 10));
        }
        for (int i = 0; i < 4; i++) {
            int slot = 100 + i;
            deaths7.add(new DeathEvent(200 + i, slot, null, 10));
            players7.add(new PlayerTelemetry(3000L + i, 1, 100 + i, List.of(), List.of(), List.of(), slot, 1, List.of()));
        }
        PlayerTelemetry target7 = createPlayerWithDeaths(deaths7);
        players7.add(target7);
        MatchTelemetry tel7 = new MatchTelemetry(MATCH_ID, 1800, players7);
        DeathsPayload p7 = (DeathsPayload) provider.provide(new CoachRequest(TARGET_ACCOUNT_ID, null, List.of(), tel7, target7, null, List.of(), List.of())).orElseThrow().payload();
        assertThat(p7.topKiller().count()).isEqualTo(3);
        assertThat(p7.repeatKiller()).isTrue();
    }

    @Test
    void deathsWhileBehindEvaluations() {
        // Test 1: Team ahead (lead > 0)
        List<DeathEvent> deaths = List.of(new DeathEvent(200, null, null, 10));
        List<Integer> times = List.of(180);
        Map<Integer, Long> team0NwAhead = Map.of(180, 12000L);
        Map<Integer, Long> team1NwAhead = Map.of(180, 10000L);
        PlayerTelemetry targetAhead = createPlayerWithDeathsAndSnapshots(deaths, times);
        List<PlayerTelemetry> playersAhead = create12Players(targetAhead, times, team0NwAhead, team1NwAhead);
        MatchTelemetry telAhead = new MatchTelemetry(MATCH_ID, 1800, playersAhead);

        DeathsPayload pAhead = (DeathsPayload) provider.provide(new CoachRequest(TARGET_ACCOUNT_ID, null, List.of(), telAhead, targetAhead, null, List.of(), List.of())).orElseThrow().payload();
        assertThat(pAhead.deathsCounted()).isEqualTo(1);
        assertThat(pAhead.deathsWhileBehind()).isEqualTo(0);

        // Test 2: Team behind (lead < 0)
        Map<Integer, Long> team0NwBehind = Map.of(180, 8000L);
        Map<Integer, Long> team1NwBehind = Map.of(180, 10000L);
        List<PlayerTelemetry> playersBehind = create12Players(targetAhead, times, team0NwBehind, team1NwBehind);
        MatchTelemetry telBehind = new MatchTelemetry(MATCH_ID, 1800, playersBehind);

        DeathsPayload pBehind = (DeathsPayload) provider.provide(new CoachRequest(TARGET_ACCOUNT_ID, null, List.of(), telBehind, targetAhead, null, List.of(), List.of())).orElseThrow().payload();
        assertThat(pBehind.deathsCounted()).isEqualTo(1);
        assertThat(pBehind.deathsWhileBehind()).isEqualTo(1);

        // Test 3: Death before first common time is skipped
        List<DeathEvent> earlyDeaths = List.of(new DeathEvent(100, null, null, 10)); // 100 < 180
        PlayerTelemetry targetEarly = createPlayerWithDeathsAndSnapshots(earlyDeaths, times);
        List<PlayerTelemetry> playersEarly = create12Players(targetEarly, times, team0NwBehind, team1NwBehind);
        MatchTelemetry telEarly = new MatchTelemetry(MATCH_ID, 1800, playersEarly);

        DeathsPayload pEarly = (DeathsPayload) provider.provide(new CoachRequest(TARGET_ACCOUNT_ID, null, List.of(), telEarly, targetEarly, null, List.of(), List.of())).orElseThrow().payload();
        assertThat(pEarly.deathsCounted()).isNull();
        assertThat(pEarly.deathsWhileBehind()).isNull();

        // Test 4: A snapshot missing for one player -> that time is not common
        // Player 11 has snapshot only at 180, while players 0..10 have snapshots at 180 and 360.
        // At 180: team 0 is ahead (+2000)
        // At 360: team 0 is behind (-2000)
        // Death is at 400.
        // If 360 were common, death at 400 would evaluate 360 (behind -> 1).
        // Since 360 is missing for player 11, common times only includes 180 -> evaluates 180 (ahead -> 0).
        List<Integer> timesBoth = List.of(180, 360);
        PlayerTelemetry targetMissing = createPlayerWithDeathsAndSnapshots(List.of(new DeathEvent(400, null, null, 10)), timesBoth);
        List<PlayerTelemetry> playersWithMissing = create12PlayersWithOnePlayerMissingTime(targetMissing, 180, 360);
        MatchTelemetry telMissing = new MatchTelemetry(MATCH_ID, 1800, playersWithMissing);

        DeathsPayload pMissing = (DeathsPayload) provider.provide(new CoachRequest(TARGET_ACCOUNT_ID, null, List.of(), telMissing, targetMissing, null, List.of(), List.of())).orElseThrow().payload();
        assertThat(pMissing.deathsCounted()).isEqualTo(1);
        assertThat(pMissing.deathsWhileBehind()).isEqualTo(0); // evaluated at 180 (ahead), NOT at 360
    }

    @Test
    void rankCurveBestEffortScenarios() {
        List<DeathEvent> deaths = List.of(new DeathEvent(200, null, null, 10));
        PlayerTelemetry target = createPlayerWithDeaths(deaths);
        MatchTelemetry tel = new MatchTelemetry(MATCH_ID, 1800, List.of(target));

        // 1. Tier null: no client call, deaths_z null
        CoachRequest nullTierReq = new CoachRequest(TARGET_ACCOUNT_ID, null, List.of(), tel, target, null, List.of(), List.of());
        DeathsPayload pNullTier = (DeathsPayload) provider.provide(nullTierReq).orElseThrow().payload();
        assertThat(pNullTier.deathsZ()).isNull();
        verify(deadlockApiClient, never()).getPerformanceCurve(anyInt(), anyInt());

        // 2. Curve present: correctly computes deaths_z
        when(deadlockApiClient.getPerformanceCurve(TARGET_HERO_ID, 6))
                .thenReturn(List.of(
                        createCurvePoint(300, 2.0, 1.0),
                        createCurvePoint(600, 3.0, 2.0)
                ));
        // Target has snapshot at 600 with 5 deaths -> z = (5 - 3.0) / 2.0 = 1.0
        StatSnapshot s600 = new StatSnapshot(600, 2000L, 1000L, 0L, createDetailWithDeathsAndGoldLoss(5, 500L));
        PlayerTelemetry targetWith600 = new PlayerTelemetry(
                TARGET_ACCOUNT_ID, TARGET_TEAM, TARGET_HERO_ID,
                List.of(), List.of(s600), List.of(), TARGET_SLOT, 1, deaths
        );
        MatchTelemetry tel600 = new MatchTelemetry(MATCH_ID, 1800, List.of(targetWith600));
        CoachRequest req6 = new CoachRequest(TARGET_ACCOUNT_ID, null, List.of(), tel600, targetWith600, 6, List.of(), List.of());
        DeathsPayload pCurve = (DeathsPayload) provider.provide(req6).orElseThrow().payload();
        assertThat(pCurve.deathsZ()).isEqualTo(1.0);

        // 3. Curve empty: deaths_z null
        when(deadlockApiClient.getPerformanceCurve(TARGET_HERO_ID, 7)).thenReturn(List.of());
        CoachRequest req7 = new CoachRequest(TARGET_ACCOUNT_ID, null, List.of(), tel600, targetWith600, 7, List.of(), List.of());
        DeathsPayload pEmpty = (DeathsPayload) provider.provide(req7).orElseThrow().payload();
        assertThat(pEmpty.deathsZ()).isNull();

        // 4. deathsStd == 0: deaths_z null
        when(deadlockApiClient.getPerformanceCurve(TARGET_HERO_ID, 8))
                .thenReturn(List.of(createCurvePoint(600, 3.0, 0.0)));
        CoachRequest req8 = new CoachRequest(TARGET_ACCOUNT_ID, null, List.of(), tel600, targetWith600, 8, List.of(), List.of());
        DeathsPayload pStdZero = (DeathsPayload) provider.provide(req8).orElseThrow().payload();
        assertThat(pStdZero.deathsZ()).isNull();

        // 5. Client throws DeadlockApiUnavailableException: catches and continues without deaths_z
        when(deadlockApiClient.getPerformanceCurve(TARGET_HERO_ID, 9))
                .thenThrow(new DeadlockApiUnavailableException("Rate limited", Duration.ofSeconds(5), null));
        CoachRequest req9 = new CoachRequest(TARGET_ACCOUNT_ID, null, List.of(), tel600, targetWith600, 9, List.of(), List.of());
        DeathsPayload pEx = (DeathsPayload) provider.provide(req9).orElseThrow().payload();
        assertThat(pEx.deathsZ()).isNull();
        assertThat(pEx.significance()).isNotNull();
    }

    @Test
    void significanceThresholdBoundaries() {
        // 1. MAJOR_DEATHS_Z = 2.0 (1.9 notable, 2.0 major, 2.1 major)
        assertThat(evaluateSignificanceForZ(1.9)).isEqualTo("notable");
        assertThat(evaluateSignificanceForZ(2.0)).isEqualTo("major");
        assertThat(evaluateSignificanceForZ(2.1)).isEqualTo("major");

        // 2. NOTABLE_DEATHS_Z = 1.0 (0.9 none, 1.0 notable, 1.1 notable)
        assertThat(evaluateSignificanceForZ(0.9)).isEqualTo("none");
        assertThat(evaluateSignificanceForZ(1.0)).isEqualTo("notable");
        assertThat(evaluateSignificanceForZ(1.1)).isEqualTo("notable");

        // 3. MAJOR_PERCENT_DEAD = 15.0 (14.9 notable, 15.0 major, 15.1 major)
        assertThat(evaluateSignificanceForPercentDead(14.9)).isEqualTo("notable");
        assertThat(evaluateSignificanceForPercentDead(15.0)).isEqualTo("major");
        assertThat(evaluateSignificanceForPercentDead(15.1)).isEqualTo("major");

        // 4. NOTABLE_PERCENT_DEAD = 10.0 (9.9 none, 10.0 notable, 10.1 notable)
        assertThat(evaluateSignificanceForPercentDead(9.9)).isEqualTo("none");
        assertThat(evaluateSignificanceForPercentDead(10.0)).isEqualTo("notable");
        assertThat(evaluateSignificanceForPercentDead(10.1)).isEqualTo("notable");

        // 5. repeat_killer = true -> major, false -> none
        assertThat(evaluateSignificanceForRepeatKiller(true)).isEqualTo("major");
        assertThat(evaluateSignificanceForRepeatKiller(false)).isEqualTo("none");

        // 6. MAJOR_DEATHS_WHILE_BEHIND = 3 (2 none, 3 major, 4 major)
        assertThat(evaluateSignificanceForDeathsWhileBehind(2)).isEqualTo("none");
        assertThat(evaluateSignificanceForDeathsWhileBehind(3)).isEqualTo("major");
        assertThat(evaluateSignificanceForDeathsWhileBehind(4)).isEqualTo("major");

        // 7. NOTABLE_BACK_TO_BACK = 2 (1 none, 2 notable, 3 notable)
        assertThat(evaluateSignificanceForBackToBack(1)).isEqualTo("none");
        assertThat(evaluateSignificanceForBackToBack(2)).isEqualTo("notable");
        assertThat(evaluateSignificanceForBackToBack(3)).isEqualTo("notable");

        // 8. NOTABLE_TOTAL_DEATHS = 8 (7 none, 8 notable, 9 notable)
        assertThat(evaluateSignificanceForTotalDeaths(7)).isEqualTo("none");
        assertThat(evaluateSignificanceForTotalDeaths(8)).isEqualTo("notable");
        assertThat(evaluateSignificanceForTotalDeaths(9)).isEqualTo("notable");
    }

    @Test
    void determinismAndNoMutation() {
        List<DeathEvent> deaths = List.of(
                new DeathEvent(200, 6, 10.0, 20),
                new DeathEvent(800, 6, 10.0, 30)
        );
        PlayerTelemetry target = createPlayerWithDeaths(deaths);
        MatchTelemetry telemetry = new MatchTelemetry(MATCH_ID, 1800, List.of(target));
        CoachRequest request = new CoachRequest(TARGET_ACCOUNT_ID, null, List.of(), telemetry, target, null, List.of(), List.of());

        Optional<CoachInsight> first = provider.provide(request);
        Optional<CoachInsight> second = provider.provide(request);

        assertThat(first).isPresent();
        assertThat(second).isPresent();
        assertThat(first.get().payload()).isEqualTo(second.get().payload());
        assertThat(request.player().deaths()).hasSize(2);
    }

    @Test
    void timeToKillHappyPathOddCountAndP90On7Values() {
        // 7 valid values shuffled to test sorting: 18.0, 4.0, 22.0, 11.0, 28.0, 7.0, 13.5
        // Sorted: 4.0, 7.0, 11.0, 13.5, 18.0, 22.0, 28.0
        // Median (odd, 7/2 = 3): 13.5
        // p90 nearest-rank: ceil(0.9 * 7) - 1 = ceil(6.3) - 1 = 6 -> sorted[6] = 28.0
        List<DeathEvent> deaths = List.of(
                new DeathEvent(100, null, 18.0, 10),
                new DeathEvent(200, null, 4.0, 10),
                new DeathEvent(300, null, 22.0, 10),
                new DeathEvent(400, null, 11.0, 10),
                new DeathEvent(500, null, 28.0, 10),
                new DeathEvent(600, null, 7.0, 10),
                new DeathEvent(700, null, 13.5, 10)
        );
        PlayerTelemetry target = createPlayerWithDeaths(deaths);
        MatchTelemetry telemetry = new MatchTelemetry(MATCH_ID, 1800, List.of(target));
        CoachRequest request = new CoachRequest(TARGET_ACCOUNT_ID, null, List.of(), telemetry, target, null, List.of(), List.of());

        DeathsPayload payload = (DeathsPayload) provider.provide(request).orElseThrow().payload();
        assertThat(payload.ttkCounted()).isEqualTo(7);
        assertThat(payload.ttkMedianS()).isEqualTo(13.5);
        assertThat(payload.ttkP90S()).isEqualTo(28.0);
    }

    @Test
    void timeToKillHappyPathEvenCountAndP90On10Values() {
        // 10 valid values shuffled:
        // 20.0, 8.0, 14.0, 5.0, 25.0, 17.0, 10.0, 30.0, 12.0, 18.0
        // Sorted: 5.0, 8.0, 10.0, 12.0, 14.0, 17.0, 18.0, 20.0, 25.0, 30.0
        // Median (even, mean of index 4 and 5): (14.0 + 17.0) / 2.0 = 15.5
        // p90 nearest-rank: ceil(0.9 * 10) - 1 = ceil(9.0) - 1 = 8 -> sorted[8] = 25.0
        List<DeathEvent> deaths = List.of(
                new DeathEvent(100, null, 20.0, 10),
                new DeathEvent(200, null, 8.0, 10),
                new DeathEvent(300, null, 14.0, 10),
                new DeathEvent(400, null, 5.0, 10),
                new DeathEvent(500, null, 25.0, 10),
                new DeathEvent(600, null, 17.0, 10),
                new DeathEvent(700, null, 10.0, 10),
                new DeathEvent(800, null, 30.0, 10),
                new DeathEvent(900, null, 12.0, 10),
                new DeathEvent(1000, null, 18.0, 10)
        );
        PlayerTelemetry target = createPlayerWithDeaths(deaths);
        MatchTelemetry telemetry = new MatchTelemetry(MATCH_ID, 1800, List.of(target));
        CoachRequest request = new CoachRequest(TARGET_ACCOUNT_ID, null, List.of(), telemetry, target, null, List.of(), List.of());

        DeathsPayload payload = (DeathsPayload) provider.provide(request).orElseThrow().payload();
        assertThat(payload.ttkCounted()).isEqualTo(10);
        assertThat(payload.ttkMedianS()).isEqualTo(15.5);
        assertThat(payload.ttkP90S()).isEqualTo(25.0);
    }

    @Test
    void timeToKillExclusionsAndBoundaries() {
        // Exactly 60.0 is included (boundary); 60.1 is excluded (boundary).
        // -1.0, null, 0.0, NaN, >60.0 are excluded.
        List<DeathEvent> deaths = List.of(
                new DeathEvent(100, null, 10.0, 10),                   // valid (1)
                new DeathEvent(200, null, -1.0, 10),                   // excluded: negative
                new DeathEvent(300, null, null, 10),                   // excluded: null
                new DeathEvent(400, null, 0.0, 10),                    // excluded: zero
                new DeathEvent(500, null, Double.NaN, 10),             // excluded: NaN
                new DeathEvent(600, null, 20.0, 10),                   // valid (2)
                new DeathEvent(700, null, 60.0, 10),                   // valid (3) - boundary at TTK_MAX_VALID_S
                new DeathEvent(800, null, 60.1, 10),                   // excluded - boundary above TTK_MAX_VALID_S
                new DeathEvent(900, null, 180.0, 10),                  // excluded: outlier
                new DeathEvent(1000, null, Double.POSITIVE_INFINITY, 10), // excluded: infinite
                new DeathEvent(1100, null, Double.NEGATIVE_INFINITY, 10)  // excluded: infinite
        );
        PlayerTelemetry target = createPlayerWithDeaths(deaths);
        MatchTelemetry telemetry = new MatchTelemetry(MATCH_ID, 1800, List.of(target));
        CoachRequest request = new CoachRequest(TARGET_ACCOUNT_ID, null, List.of(), telemetry, target, null, List.of(), List.of());

        DeathsPayload payload = (DeathsPayload) provider.provide(request).orElseThrow().payload();
        // Valid values: [10.0, 20.0, 60.0] -> counted = 3
        assertThat(payload.ttkCounted()).isEqualTo(3);
        assertThat(payload.ttkMedianS()).isEqualTo(20.0);
        // p90 on 3: ceil(0.9 * 3) - 1 = ceil(2.7) - 1 = 2 -> sorted[2] = 60.0
        assertThat(payload.ttkP90S()).isEqualTo(60.0);
    }

    @Test
    void timeToKillSampleCountThresholdBoundary() {
        // Fewer than MIN_TTK_SAMPLES (2 vs 3) omits all three fields; all values invalid omits them.
        // Case 1: 2 valid samples (10.0, 20.0)
        List<DeathEvent> deaths2 = List.of(
                new DeathEvent(100, null, 10.0, 10),
                new DeathEvent(200, null, 20.0, 10),
                new DeathEvent(300, null, -1.0, 10)
        );
        PlayerTelemetry target2 = createPlayerWithDeaths(deaths2);
        MatchTelemetry tel2 = new MatchTelemetry(MATCH_ID, 1800, List.of(target2));
        DeathsPayload p2 = (DeathsPayload) provider.provide(new CoachRequest(TARGET_ACCOUNT_ID, null, List.of(), tel2, target2, null, List.of(), List.of())).orElseThrow().payload();
        assertThat(p2.ttkCounted()).isNull();
        assertThat(p2.ttkMedianS()).isNull();
        assertThat(p2.ttkP90S()).isNull();

        // Case 2: 3 valid samples (10.0, 20.0, 30.0) -> exactly MIN_TTK_SAMPLES
        List<DeathEvent> deaths3 = List.of(
                new DeathEvent(100, null, 10.0, 10),
                new DeathEvent(200, null, 20.0, 10),
                new DeathEvent(300, null, 30.0, 10)
        );
        PlayerTelemetry target3 = createPlayerWithDeaths(deaths3);
        MatchTelemetry tel3 = new MatchTelemetry(MATCH_ID, 1800, List.of(target3));
        DeathsPayload p3 = (DeathsPayload) provider.provide(new CoachRequest(TARGET_ACCOUNT_ID, null, List.of(), tel3, target3, null, List.of(), List.of())).orElseThrow().payload();
        assertThat(p3.ttkCounted()).isEqualTo(3);
        assertThat(p3.ttkMedianS()).isEqualTo(20.0);
        assertThat(p3.ttkP90S()).isEqualTo(30.0);

        // Case 3: All values invalid
        List<DeathEvent> deathsInvalid = List.of(
                new DeathEvent(100, null, -1.0, 10),
                new DeathEvent(200, null, null, 10),
                new DeathEvent(300, null, 0.0, 10),
                new DeathEvent(400, null, 75.0, 10)
        );
        PlayerTelemetry targetInvalid = createPlayerWithDeaths(deathsInvalid);
        MatchTelemetry telInvalid = new MatchTelemetry(MATCH_ID, 1800, List.of(targetInvalid));
        DeathsPayload pInvalid = (DeathsPayload) provider.provide(new CoachRequest(TARGET_ACCOUNT_ID, null, List.of(), telInvalid, targetInvalid, null, List.of(), List.of())).orElseThrow().payload();
        assertThat(pInvalid.ttkCounted()).isNull();
        assertThat(pInvalid.ttkMedianS()).isNull();
        assertThat(pInvalid.ttkP90S()).isNull();
    }

    @Test
    void timeToKillDoesNotAffectSignificanceOrExistingFields() {
        // significance and other fields are identical with and without valid TTK values
        List<DeathEvent> deathsWithTtk = List.of(
                new DeathEvent(100, 6, 12.5, 15),
                new DeathEvent(300, 6, 15.0, 20),
                new DeathEvent(500, 6, 18.0, 25),
                new DeathEvent(700, 7, 22.0, 30),
                new DeathEvent(900, 8, 25.0, 35)
        );
        List<DeathEvent> deathsWithoutTtk = List.of(
                new DeathEvent(100, 6, null, 15),
                new DeathEvent(300, 6, null, 20),
                new DeathEvent(500, 6, null, 25),
                new DeathEvent(700, 7, null, 30),
                new DeathEvent(900, 8, null, 35)
        );

        PlayerTelemetry killer6 = new PlayerTelemetry(2001L, 1, 5, List.of(), List.of(), List.of(), 6, 1, List.of());
        PlayerTelemetry killer7 = new PlayerTelemetry(2002L, 1, 6, List.of(), List.of(), List.of(), 7, 2, List.of());
        PlayerTelemetry killer8 = new PlayerTelemetry(2003L, 1, 7, List.of(), List.of(), List.of(), 8, 3, List.of());

        PlayerTelemetry targetWith = createPlayerWithDeaths(deathsWithTtk);
        PlayerTelemetry targetWithout = createPlayerWithDeaths(deathsWithoutTtk);

        MatchTelemetry telWith = new MatchTelemetry(MATCH_ID, 1800, List.of(targetWith, killer6, killer7, killer8));
        MatchTelemetry telWithout = new MatchTelemetry(MATCH_ID, 1800, List.of(targetWithout, killer6, killer7, killer8));

        CoachRequest reqWith = new CoachRequest(TARGET_ACCOUNT_ID, null, List.of(), telWith, targetWith, null, List.of(), List.of());
        CoachRequest reqWithout = new CoachRequest(TARGET_ACCOUNT_ID, null, List.of(), telWithout, targetWithout, null, List.of(), List.of());

        DeathsPayload payloadWith = (DeathsPayload) provider.provide(reqWith).orElseThrow().payload();
        DeathsPayload payloadWithout = (DeathsPayload) provider.provide(reqWithout).orElseThrow().payload();

        assertThat(payloadWith.significance()).isEqualTo(payloadWithout.significance());
        assertThat(payloadWith.total()).isEqualTo(payloadWithout.total());
        assertThat(payloadWith.byPhase()).isEqualTo(payloadWithout.byPhase());
        assertThat(payloadWith.secondsDead()).isEqualTo(payloadWithout.secondsDead());
        assertThat(payloadWith.percentOfMatchDead()).isEqualTo(payloadWithout.percentOfMatchDead());
        assertThat(payloadWith.longestRespawnS()).isEqualTo(payloadWithout.longestRespawnS());
        assertThat(payloadWith.soulsLostOnDeath()).isEqualTo(payloadWithout.soulsLostOnDeath());
        assertThat(payloadWith.backToBack()).isEqualTo(payloadWithout.backToBack());
        assertThat(payloadWith.topKiller()).isEqualTo(payloadWithout.topKiller());
        assertThat(payloadWith.repeatKiller()).isEqualTo(payloadWithout.repeatKiller());
        assertThat(payloadWith.deathsWhileBehind()).isEqualTo(payloadWithout.deathsWhileBehind());
        assertThat(payloadWith.deathsCounted()).isEqualTo(payloadWithout.deathsCounted());
        assertThat(payloadWith.deathsZ()).isEqualTo(payloadWithout.deathsZ());

        // TTK fields are present on payloadWith and absent on payloadWithout
        assertThat(payloadWith.ttkCounted()).isEqualTo(5);
        assertThat(payloadWith.ttkMedianS()).isEqualTo(18.0);
        assertThat(payloadWith.ttkP90S()).isEqualTo(25.0);

        assertThat(payloadWithout.ttkCounted()).isNull();
        assertThat(payloadWithout.ttkMedianS()).isNull();
        assertThat(payloadWithout.ttkP90S()).isNull();
    }

    @Test
    void timeToKillSerializationSizeAndOmission() throws Exception {
        // Serialized payload of a realistic full match (12 players, 8-10 deaths with resolved killers,
        // top_killer, deaths_while_behind, valid ttk values) is <= 1200 characters and contains ttk fields.
        int durationS = 2450;
        int rankTier = 5;

        List<DeathEvent> deaths = List.of(
                new DeathEvent(200, 6, 12.3, 20),
                new DeathEvent(300, 6, 15.6, 25),
                new DeathEvent(800, 6, 18.2, 35),
                new DeathEvent(850, 7, 9.5, 45),
                new DeathEvent(1000, 6, 22.1, 50),
                new DeathEvent(1300, 8, 14.0, 55),
                new DeathEvent(1350, 9, 28.4, 58),
                new DeathEvent(1600, 10, 11.7, 60),
                new DeathEvent(1900, 11, 16.5, 62)
        );

        List<Integer> times = List.of(300, 600, 900, 1200, 1500, 1800, 2100, 2400);
        List<StatSnapshot> targetSnapshots = new ArrayList<>();
        for (int t : times) {
            int cumulativeDeaths = t < 1800 ? 5 : (t == 1800 ? 8 : 9);
            long goldLoss = (t == 2400) ? 1850L : 1000L;
            SnapshotDetail detail = createDetailWithDeathsAndGoldLoss(cumulativeDeaths, goldLoss);
            targetSnapshots.add(new StatSnapshot(t, 20000L, 10000L, 0L, detail));
        }

        PlayerTelemetry target = new PlayerTelemetry(
                TARGET_ACCOUNT_ID, TARGET_TEAM, TARGET_HERO_ID,
                List.of(), targetSnapshots, List.of(), TARGET_SLOT, 1, deaths
        );

        Map<Integer, Long> team0Nw = Map.of(
                300, 10000L,
                600, 20000L,
                900, 30000L,
                1200, 45000L,
                1500, 50000L,
                1800, 65000L,
                2100, 75000L,
                2400, 85000L
        );
        Map<Integer, Long> team1Nw = Map.of(
                300, 10500L,
                600, 20500L,
                900, 30500L,
                1200, 40000L,
                1500, 50500L,
                1800, 60000L,
                2100, 70000L,
                2400, 80000L
        );

        List<PlayerTelemetry> players = create12Players(target, times, team0Nw, team1Nw);
        MatchTelemetry telemetry = new MatchTelemetry(MATCH_ID, durationS, players);

        List<HeroAsset> heroes = List.of(
                new HeroAsset(1, "hero_infernus", "Infernus"),
                new HeroAsset(10, "hero_haze", "Haze")
        );

        when(deadlockApiClient.getPerformanceCurve(TARGET_HERO_ID, rankTier))
                .thenReturn(List.of(createCurvePoint(1800, 4.0, 2.5)));

        CoachRequest request = new CoachRequest(
                TARGET_ACCOUNT_ID, null, List.of(), telemetry, target, rankTier, heroes, List.of()
        );

        DeathsPayload payload = (DeathsPayload) provider.provide(request).orElseThrow().payload();
        assertThat(payload.total()).isEqualTo(9);
        assertThat(payload.topKiller()).isNotNull();
        assertThat(payload.topKiller().hero()).isEqualTo("Haze");
        assertThat(payload.deathsWhileBehind()).isEqualTo(5);
        assertThat(payload.deathsCounted()).isEqualTo(8);

        String json = objectMapper.writeValueAsString(payload);
        assertThat(json.length()).isLessThanOrEqualTo(1200);

        JsonNode root = objectMapper.readTree(json);
        assertThat(root.has("ttk_median_s")).isTrue();
        assertThat(root.has("ttk_p90_s")).isTrue();
        assertThat(root.has("ttk_counted")).isTrue();
        assertThat(root.get("ttk_counted").asInt()).isEqualTo(9);
        assertThat(root.has("top_killer")).isTrue();
        assertThat(root.has("deaths_while_behind")).isTrue();

        // When TTK fields are omitted (null), they must not appear in the JSON
        List<DeathEvent> deathsNoTtk = List.of(
                new DeathEvent(200, 6, null, 20),
                new DeathEvent(400, 6, null, 25)
        );
        PlayerTelemetry targetNoTtk = createPlayerWithDeaths(deathsNoTtk);
        MatchTelemetry telNoTtk = new MatchTelemetry(MATCH_ID, 1800, List.of(targetNoTtk));
        DeathsPayload payloadNoTtk = (DeathsPayload) provider.provide(new CoachRequest(TARGET_ACCOUNT_ID, null, List.of(), telNoTtk, targetNoTtk, null, List.of(), List.of())).orElseThrow().payload();
        String jsonNoTtk = objectMapper.writeValueAsString(payloadNoTtk);

        assertThat(jsonNoTtk.length()).isLessThanOrEqualTo(1200);
        JsonNode rootNoTtk = objectMapper.readTree(jsonNoTtk);
        assertThat(rootNoTtk.has("ttk_median_s")).isFalse();
        assertThat(rootNoTtk.has("ttk_p90_s")).isFalse();
        assertThat(rootNoTtk.has("ttk_counted")).isFalse();
    }

    @Test
    void timeToKillDeterminismAndNoMutation() {
        List<DeathEvent> deaths = List.of(
                new DeathEvent(200, 6, 12.0, 20),
                new DeathEvent(400, 6, 18.0, 25),
                new DeathEvent(800, 6, 24.0, 30)
        );
        PlayerTelemetry target = createPlayerWithDeaths(deaths);
        MatchTelemetry telemetry = new MatchTelemetry(MATCH_ID, 1800, List.of(target));
        CoachRequest request = new CoachRequest(TARGET_ACCOUNT_ID, null, List.of(), telemetry, target, null, List.of(), List.of());

        Optional<CoachInsight> first = provider.provide(request);
        Optional<CoachInsight> second = provider.provide(request);

        assertThat(first).isPresent();
        assertThat(second).isPresent();
        assertThat(first.get().payload()).isEqualTo(second.get().payload());
        DeathsPayload payload = (DeathsPayload) first.get().payload();
        assertThat(payload.ttkCounted()).isEqualTo(3);
        assertThat(payload.ttkMedianS()).isEqualTo(18.0);
        assertThat(payload.ttkP90S()).isEqualTo(24.0);
        assertThat(request.player().deaths()).hasSize(3);
    }

    // Helper methods for significance testing

    private String evaluateSignificanceForZ(double z) {
        int durationS = 2000;
        List<DeathEvent> deaths = List.of(new DeathEvent(100, null, null, 10));
        SnapshotDetail detail = createDetailWithDeathsAndGoldLoss(1, 100L);
        StatSnapshot snap = new StatSnapshot(300, 2000L, 1000L, 0L, detail);
        PlayerTelemetry target = new PlayerTelemetry(
                TARGET_ACCOUNT_ID, TARGET_TEAM, TARGET_HERO_ID,
                List.of(), List.of(snap), List.of(), TARGET_SLOT, 1, deaths
        );
        MatchTelemetry telemetry = new MatchTelemetry(MATCH_ID, durationS, List.of(target));

        int rankTier = 1;
        double avg = 1.0 - z;
        when(deadlockApiClient.getPerformanceCurve(TARGET_HERO_ID, rankTier))
                .thenReturn(List.of(createCurvePoint(300, avg, 1.0)));

        CoachRequest req = new CoachRequest(TARGET_ACCOUNT_ID, null, List.of(), telemetry, target, rankTier, List.of(), List.of());
        DeathsPayload payload = (DeathsPayload) provider.provide(req).orElseThrow().payload();
        return payload.significance();
    }

    private String evaluateSignificanceForPercentDead(double targetPercent) {
        int durationS = 1000;
        int respawnS = (int) Math.round((targetPercent / 100.0) * durationS);
        List<DeathEvent> deaths = List.of(new DeathEvent(100, null, null, respawnS));
        PlayerTelemetry target = createPlayerWithDeaths(deaths);
        MatchTelemetry telemetry = new MatchTelemetry(MATCH_ID, durationS, List.of(target));
        CoachRequest req = new CoachRequest(TARGET_ACCOUNT_ID, null, List.of(), telemetry, target, null, List.of(), List.of());
        DeathsPayload payload = (DeathsPayload) provider.provide(req).orElseThrow().payload();
        return payload.significance();
    }

    private String evaluateSignificanceForRepeatKiller(boolean isRepeat) {
        int durationS = 2000;
        int count = isRepeat ? 3 : 2;
        List<DeathEvent> deaths = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            deaths.add(new DeathEvent(100 + i * 200, 6, 10.0, 10));
        }
        PlayerTelemetry target = createPlayerWithDeaths(deaths);
        PlayerTelemetry killer = new PlayerTelemetry(2001L, 1, 5, List.of(), List.of(), List.of(), 6, 1, List.of());
        MatchTelemetry telemetry = new MatchTelemetry(MATCH_ID, durationS, List.of(target, killer));
        CoachRequest req = new CoachRequest(TARGET_ACCOUNT_ID, null, List.of(), telemetry, target, null, List.of(), List.of());
        DeathsPayload payload = (DeathsPayload) provider.provide(req).orElseThrow().payload();
        return payload.significance();
    }

    private String evaluateSignificanceForDeathsWhileBehind(int behindCount) {
        int durationS = 5000;
        List<DeathEvent> deaths = new ArrayList<>();
        for (int i = 0; i < behindCount; i++) {
            deaths.add(new DeathEvent(100 + i * 200, null, null, 10));
        }
        List<Integer> times = List.of(50);
        PlayerTelemetry target = createPlayerWithDeathsAndSnapshots(deaths, times);
        Map<Integer, Long> team0 = Map.of(50, 1000L);
        Map<Integer, Long> team1 = Map.of(50, 5000L); // behind
        List<PlayerTelemetry> players = create12Players(target, times, team0, team1);
        MatchTelemetry telemetry = new MatchTelemetry(MATCH_ID, durationS, players);
        CoachRequest req = new CoachRequest(TARGET_ACCOUNT_ID, null, List.of(), telemetry, target, null, List.of(), List.of());
        DeathsPayload payload = (DeathsPayload) provider.provide(req).orElseThrow().payload();
        return payload.significance();
    }

    private String evaluateSignificanceForBackToBack(int b2b) {
        int durationS = 10000;
        // Total deaths = 5, gap is 50 for b2b occurrences, 200 otherwise
        List<DeathEvent> deaths = new ArrayList<>();
        int t = 100;
        deaths.add(new DeathEvent(t, null, null, 10));
        for (int i = 1; i < 5; i++) {
            int gap = (i <= b2b) ? 50 : 200;
            t += gap;
            deaths.add(new DeathEvent(t, null, null, 10));
        }
        PlayerTelemetry target = createPlayerWithDeaths(deaths);
        MatchTelemetry telemetry = new MatchTelemetry(MATCH_ID, durationS, List.of(target));
        CoachRequest req = new CoachRequest(TARGET_ACCOUNT_ID, null, List.of(), telemetry, target, null, List.of(), List.of());
        DeathsPayload payload = (DeathsPayload) provider.provide(req).orElseThrow().payload();
        return payload.significance();
    }

    private String evaluateSignificanceForTotalDeaths(int total) {
        int durationS = 10000;
        List<DeathEvent> deaths = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            deaths.add(new DeathEvent(100 + i * 200, null, null, 5));
        }
        PlayerTelemetry target = createPlayerWithDeaths(deaths);
        MatchTelemetry telemetry = new MatchTelemetry(MATCH_ID, durationS, List.of(target));
        CoachRequest req = new CoachRequest(TARGET_ACCOUNT_ID, null, List.of(), telemetry, target, null, List.of(), List.of());
        DeathsPayload payload = (DeathsPayload) provider.provide(req).orElseThrow().payload();
        return payload.significance();
    }

    private static SnapshotDetail createDetailWithDeathsAndGoldLoss(int deaths, long goldDeathLoss) {
        return new SnapshotDetail(
                2, deaths, 1,
                30, 40,
                150, 30,
                5,
                1500L,
                1000L, 500L,
                200L, goldDeathLoss
        );
    }

    private static PlayerTelemetry createPlayerWithDeaths(List<DeathEvent> deaths) {
        SnapshotDetail detail = createDetailWithDeathsAndGoldLoss(deaths.size(), 1000L);
        StatSnapshot snap = new StatSnapshot(180, 2000L, 1000L, 0L, detail);
        return new PlayerTelemetry(
                TARGET_ACCOUNT_ID, TARGET_TEAM, TARGET_HERO_ID,
                List.of(), List.of(snap), List.of(), TARGET_SLOT, 1, deaths
        );
    }

    private static PlayerTelemetry createPlayerWithDeathsAndSnapshots(List<DeathEvent> deaths, List<Integer> snapshotTimes) {
        List<StatSnapshot> snapshots = new ArrayList<>();
        for (int t : snapshotTimes) {
            SnapshotDetail detail = createDetailWithDeathsAndGoldLoss(deaths.size(), 1000L);
            snapshots.add(new StatSnapshot(t, 2000L, 1000L, 0L, detail));
        }
        return new PlayerTelemetry(
                TARGET_ACCOUNT_ID, TARGET_TEAM, TARGET_HERO_ID,
                List.of(), snapshots, List.of(), TARGET_SLOT, 1, deaths
        );
    }

    private static List<PlayerTelemetry> create12Players(PlayerTelemetry target, List<Integer> times,
                                                        Map<Integer, Long> team0Nw, Map<Integer, Long> team1Nw) {
        List<PlayerTelemetry> players = new ArrayList<>();
        players.add(target);

        // 5 more on team 0 (slots 1..5)
        for (int i = 1; i <= 5; i++) {
            List<StatSnapshot> snaps = new ArrayList<>();
            for (int t : times) {
                // target net worth is in target snapshots (2000L)
                long remaining = team0Nw.get(t) - target.snapshots().stream().filter(s -> s.timeS() == t).findFirst().map(StatSnapshot::netWorth).orElse(0L);
                snaps.add(new StatSnapshot(t, remaining / 5, 500L, 0L, null));
            }
            players.add(new PlayerTelemetry(1000000L + i, TARGET_TEAM, 2 + i, List.of(), snaps, List.of(), i, 1, List.of()));
        }

        // 6 on team 1 (slots 6..11)
        for (int i = 0; i < 6; i++) {
            List<StatSnapshot> snaps = new ArrayList<>();
            for (int t : times) {
                long totalTeam1 = team1Nw.get(t);
                snaps.add(new StatSnapshot(t, totalTeam1 / 6, 500L, 0L, null));
            }
            players.add(new PlayerTelemetry(2000000L + i, 1, 10 + i, List.of(), snaps, List.of(), 6 + i, 2, List.of()));
        }
        return players;
    }

    private static List<PlayerTelemetry> create12PlayersWithOnePlayerMissingTime(PlayerTelemetry target, int commonTime, int missingTime) {
        List<PlayerTelemetry> players = new ArrayList<>();
        players.add(target);

        // 5 more on team 0:
        for (int i = 1; i <= 5; i++) {
            List<StatSnapshot> snaps = List.of(
                    new StatSnapshot(commonTime, 2000L, 500L, 0L, null), // team 0 total at 180: 12000
                    new StatSnapshot(missingTime, 1000L, 500L, 0L, null)  // team 0 total at 360: 7000
            );
            players.add(new PlayerTelemetry(1000000L + i, TARGET_TEAM, 2 + i, List.of(), snaps, List.of(), i, 1, List.of()));
        }

        // 5 on team 1 with both snapshots:
        for (int i = 0; i < 5; i++) {
            List<StatSnapshot> snaps = List.of(
                    new StatSnapshot(commonTime, 1500L, 500L, 0L, null), // 5 * 1500 = 7500
                    new StatSnapshot(missingTime, 2000L, 500L, 0L, null)  // 5 * 2000 = 10000
            );
            players.add(new PlayerTelemetry(2000000L + i, 1, 10 + i, List.of(), snaps, List.of(), 6 + i, 2, List.of()));
        }

        // 1 player on team 1 missing the second snapshot:
        // Has only commonTime snapshot!
        List<StatSnapshot> player11Snaps = List.of(
                new StatSnapshot(commonTime, 2000L, 500L, 0L, null) // team 1 total at 180: 7500 + 2000 = 9500 (lead +2500)
        );
        players.add(new PlayerTelemetry(2000011L, 1, 20, List.of(), player11Snaps, List.of(), 11, 3, List.of()));

        return players;
    }

    private static PerformanceCurvePoint createCurvePoint(int gameTime, double deathsAvg, double deathsStd) {
        return new PerformanceCurvePoint(
                gameTime,
                15000.0, 2000.0,
                5.0, 2.0,
                deathsAvg, deathsStd,
                8.0, 3.0,
                20000.0, 5000.0,
                4.0, 1.5,
                150.0, 30.0
        );
    }
}
