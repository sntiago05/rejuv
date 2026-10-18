package sntiago05.rejuv.services;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import sntiago05.rejuv.clients.dto.MatchHistoryEntry;
import sntiago05.rejuv.domain.MatchTelemetry;
import sntiago05.rejuv.domain.MidBossEvent;
import sntiago05.rejuv.domain.PlayerTelemetry;
import sntiago05.rejuv.services.ObjectivesCoachProvider.ObjectivesInsight;

import static org.assertj.core.api.Assertions.assertThat;

class ObjectivesCoachProviderTest {

    private static final long MATCH_ID = 900000001L;
    private static final long TARGET_ACCOUNT_ID = 1000001L;
    private static final int TARGET_HERO_ID = 1;
    private static final int TARGET_SLOT = 0;
    private static final int TARGET_TEAM = 0;

    private ObjectivesCoachProvider provider;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        provider = new ObjectivesCoachProvider();
        objectMapper = new JsonMapper();
    }

    private MatchHistoryEntry createMatch(int matchResult, int playerTeam, int durationS) {
        return new MatchHistoryEntry(
                MATCH_ID, TARGET_HERO_ID, 10, 1700000000L, durationS, 4,
                matchResult, playerTeam, 0, 5, 2, 8, 20000L, 100, 10, 50
        );
    }

    private PlayerTelemetry createPlayer(long accountId, int team, Long bossDamage) {
        return new PlayerTelemetry(
                accountId, team, TARGET_HERO_ID,
                List.of(), List.of(), List.of(),
                TARGET_SLOT, 1, List.of(), bossDamage
        );
    }

    private CoachRequest createRequest(MatchHistoryEntry match, MatchTelemetry telemetry, PlayerTelemetry player) {
        return new CoachRequest(
                TARGET_ACCOUNT_ID, match, List.of(match), telemetry, player, 5, List.of(), List.of()
        );
    }

    @Test
    void keyMatchesContract() {
        assertThat(provider.key()).isEqualTo("objectives");
    }

    @Test
    void happyPathRealisticMatchMatchesExpectedJsonAndSizeLimit() throws Exception {
        int durationS = 2400; // 40 minutes
        MatchHistoryEntry match = createMatch(TARGET_TEAM, TARGET_TEAM, durationS); // win

        // 2 Mid Boss kills:
        // 1st at 1110s (18.5 min): team 0 killed and claimed
        // 2nd at 1800s (30.0 min): team 0 killed and claimed
        List<MidBossEvent> events = List.of(
                new MidBossEvent(1110, TARGET_TEAM, TARGET_TEAM),
                new MidBossEvent(1800, TARGET_TEAM, TARGET_TEAM)
        );

        // Player boss damage: 12,500
        PlayerTelemetry targetPlayer = createPlayer(TARGET_ACCOUNT_ID, TARGET_TEAM, 12500L);

        // Team 0 players: 12500 + 10000 + 8000 + 3000 + 2000 + 0 = 35,500 total boss damage
        // Target share = 12500 / 35500 * 100 = 35.211... -> 35.2%
        List<PlayerTelemetry> allPlayers = List.of(
                targetPlayer,
                createPlayer(1000002L, TARGET_TEAM, 10000L),
                createPlayer(1000003L, TARGET_TEAM, 8000L),
                createPlayer(1000004L, TARGET_TEAM, 3000L),
                createPlayer(1000005L, TARGET_TEAM, 2000L),
                createPlayer(1000006L, TARGET_TEAM, 0L),
                createPlayer(1000007L, 1, 5000L),
                createPlayer(1000008L, 1, 4000L)
        );

        MatchTelemetry telemetry = new MatchTelemetry(MATCH_ID, durationS, allPlayers, 50, events);
        CoachRequest request = createRequest(match, telemetry, targetPlayer);

        Optional<CoachInsight> insightOpt = provider.provide(request);
        assertThat(insightOpt).isPresent();

        CoachInsight insight = insightOpt.get();
        assertThat(insight.key()).isEqualTo("objectives");
        assertThat(insight.payload()).isInstanceOf(ObjectivesInsight.class);

        ObjectivesInsight payload = (ObjectivesInsight) insight.payload();
        assertThat(payload.significance()).isEqualTo("notable");
        assertThat(payload.midBossTeam()).isEqualTo(2);
        assertThat(payload.midBossEnemy()).isZero();
        assertThat(payload.rejuvenatorStolenByEnemy()).isZero();
        assertThat(payload.rejuvenatorStolenByTeam()).isZero();
        assertThat(payload.playerBossDamage()).isEqualTo(12500L);
        assertThat(payload.bossDamageSharePct()).isEqualTo(35.2);
        assertThat(payload.firstMidBossMin()).isEqualTo(18.5);

        String json = objectMapper.writeValueAsString(payload);
        assertThat(json).isEqualTo(
                "{\"significance\":\"notable\"," +
                "\"mid_boss_team\":2," +
                "\"mid_boss_enemy\":0," +
                "\"rejuvenator_stolen_by_enemy\":0," +
                "\"rejuvenator_stolen_by_team\":0," +
                "\"player_boss_damage\":12500," +
                "\"boss_damage_share_pct\":35.2," +
                "\"first_mid_boss_min\":18.5}"
        );
        assertThat(json.length()).isLessThanOrEqualTo(1200);
    }

    @Test
    void enemyTwoClaimsGivesMajor() {
        int durationS = 1800;
        MatchHistoryEntry match = createMatch(TARGET_TEAM, TARGET_TEAM, durationS); // win

        List<MidBossEvent> events = List.of(
                new MidBossEvent(900, 1, 1),
                new MidBossEvent(1400, 1, 1)
        );
        PlayerTelemetry targetPlayer = createPlayer(TARGET_ACCOUNT_ID, TARGET_TEAM, 2000L);
        MatchTelemetry telemetry = new MatchTelemetry(MATCH_ID, durationS, List.of(targetPlayer), 50, events);
        CoachRequest request = createRequest(match, telemetry, targetPlayer);

        Optional<CoachInsight> insightOpt = provider.provide(request);
        assertThat(insightOpt).isPresent();
        ObjectivesInsight payload = (ObjectivesInsight) insightOpt.get().payload();
        assertThat(payload.significance()).isEqualTo("major");
        assertThat(payload.midBossEnemy()).isEqualTo(2);
    }

    @Test
    void stealByEnemyGivesMajor() {
        int durationS = 1800;
        MatchHistoryEntry match = createMatch(TARGET_TEAM, TARGET_TEAM, durationS); // win

        // Team 0 killed Mid Boss, but Enemy (team 1) claimed it
        List<MidBossEvent> events = List.of(
                new MidBossEvent(1200, TARGET_TEAM, 1)
        );
        PlayerTelemetry targetPlayer = createPlayer(TARGET_ACCOUNT_ID, TARGET_TEAM, 8000L);
        MatchTelemetry telemetry = new MatchTelemetry(MATCH_ID, durationS, List.of(targetPlayer), 50, events);
        CoachRequest request = createRequest(match, telemetry, targetPlayer);

        Optional<CoachInsight> insightOpt = provider.provide(request);
        assertThat(insightOpt).isPresent();
        ObjectivesInsight payload = (ObjectivesInsight) insightOpt.get().payload();
        assertThat(payload.significance()).isEqualTo("major");
        assertThat(payload.rejuvenatorStolenByEnemy()).isEqualTo(1);
        assertThat(payload.midBossEnemy()).isEqualTo(1);
    }

    @Test
    void lossWithOneEnemyClaimGivesNotable() {
        int durationS = 1800;
        MatchHistoryEntry match = createMatch(1, TARGET_TEAM, durationS); // loss (result=1, playerTeam=0)

        List<MidBossEvent> events = List.of(
                new MidBossEvent(1200, 1, 1)
        );
        PlayerTelemetry targetPlayer = createPlayer(TARGET_ACCOUNT_ID, TARGET_TEAM, 1000L);
        MatchTelemetry telemetry = new MatchTelemetry(MATCH_ID, durationS, List.of(targetPlayer), 50, events);
        CoachRequest request = createRequest(match, telemetry, targetPlayer);

        Optional<CoachInsight> insightOpt = provider.provide(request);
        assertThat(insightOpt).isPresent();
        ObjectivesInsight payload = (ObjectivesInsight) insightOpt.get().payload();
        assertThat(payload.significance()).isEqualTo("notable");
        assertThat(payload.midBossEnemy()).isEqualTo(1);
    }

    @Test
    void winWithOneEnemyClaimGivesNone() {
        int durationS = 1800;
        MatchHistoryEntry match = createMatch(TARGET_TEAM, TARGET_TEAM, durationS); // win

        List<MidBossEvent> events = List.of(
                new MidBossEvent(1200, 1, 1)
        );
        PlayerTelemetry targetPlayer = createPlayer(TARGET_ACCOUNT_ID, TARGET_TEAM, 1000L);
        MatchTelemetry telemetry = new MatchTelemetry(MATCH_ID, durationS, List.of(targetPlayer), 50, events);
        CoachRequest request = createRequest(match, telemetry, targetPlayer);

        Optional<CoachInsight> insightOpt = provider.provide(request);
        assertThat(insightOpt).isPresent();
        ObjectivesInsight payload = (ObjectivesInsight) insightOpt.get().payload();
        assertThat(payload.significance()).isEqualTo("none");
        assertThat(payload.midBossEnemy()).isEqualTo(1);
    }

    @Test
    void stealByTeamGivesNotable() {
        int durationS = 1800;
        MatchHistoryEntry match = createMatch(TARGET_TEAM, TARGET_TEAM, durationS); // win

        // Enemy (1) killed it, Our team (0) claimed it
        List<MidBossEvent> events = List.of(
                new MidBossEvent(1200, 1, TARGET_TEAM)
        );
        PlayerTelemetry targetPlayer = createPlayer(TARGET_ACCOUNT_ID, TARGET_TEAM, 5000L);
        MatchTelemetry telemetry = new MatchTelemetry(MATCH_ID, durationS, List.of(targetPlayer), 50, events);
        CoachRequest request = createRequest(match, telemetry, targetPlayer);

        Optional<CoachInsight> insightOpt = provider.provide(request);
        assertThat(insightOpt).isPresent();
        ObjectivesInsight payload = (ObjectivesInsight) insightOpt.get().payload();
        assertThat(payload.significance()).isEqualTo("notable");
        assertThat(payload.rejuvenatorStolenByTeam()).isEqualTo(1);
        assertThat(payload.midBossTeam()).isEqualTo(1);
    }

    @Test
    void bossDamageShareBoundary25PercentNotableVs24Point9None() {
        int durationS = 1800;
        MatchHistoryEntry match = createMatch(TARGET_TEAM, TARGET_TEAM, durationS); // win

        List<MidBossEvent> events = List.of(
                new MidBossEvent(1200, TARGET_TEAM, TARGET_TEAM)
        );

        // Team total damage = 10,000L
        // At 25.0% share: 2,500L
        PlayerTelemetry targetPlayer25 = createPlayer(TARGET_ACCOUNT_ID, TARGET_TEAM, 2500L);
        PlayerTelemetry teammate1 = createPlayer(1000002L, TARGET_TEAM, 7500L);
        MatchTelemetry telemetry25 = new MatchTelemetry(MATCH_ID, durationS, List.of(targetPlayer25, teammate1), 50, events);
        CoachRequest request25 = createRequest(match, telemetry25, targetPlayer25);

        ObjectivesInsight payload25 = (ObjectivesInsight) provider.provide(request25).orElseThrow().payload();
        assertThat(payload25.bossDamageSharePct()).isEqualTo(25.0);
        assertThat(payload25.significance()).isEqualTo("notable");

        // At 24.9% share: 2,490L
        PlayerTelemetry targetPlayer249 = createPlayer(TARGET_ACCOUNT_ID, TARGET_TEAM, 2490L);
        PlayerTelemetry teammate2 = createPlayer(1000002L, TARGET_TEAM, 7510L);
        MatchTelemetry telemetry249 = new MatchTelemetry(MATCH_ID, durationS, List.of(targetPlayer249, teammate2), 50, events);
        CoachRequest request249 = createRequest(match, telemetry249, targetPlayer249);

        ObjectivesInsight payload249 = (ObjectivesInsight) provider.provide(request249).orElseThrow().payload();
        assertThat(payload249.bossDamageSharePct()).isEqualTo(24.9);
        assertThat(payload249.significance()).isEqualTo("none");
    }

    @Test
    void noMidBossEventsReturnsInsightWithSignificanceNone() {
        int durationS = 1800;
        MatchHistoryEntry match = createMatch(TARGET_TEAM, TARGET_TEAM, durationS); // win
        PlayerTelemetry targetPlayer = createPlayer(TARGET_ACCOUNT_ID, TARGET_TEAM, 5000L);
        MatchTelemetry telemetry = new MatchTelemetry(MATCH_ID, durationS, List.of(targetPlayer), 50, List.of());
        CoachRequest request = createRequest(match, telemetry, targetPlayer);

        Optional<CoachInsight> insightOpt = provider.provide(request);
        assertThat(insightOpt).isPresent();
        ObjectivesInsight payload = (ObjectivesInsight) insightOpt.get().payload();
        assertThat(payload.significance()).isEqualTo("none");
        assertThat(payload.midBossTeam()).isZero();
        assertThat(payload.midBossEnemy()).isZero();
        assertThat(payload.rejuvenatorStolenByEnemy()).isZero();
        assertThat(payload.rejuvenatorStolenByTeam()).isZero();
        assertThat(payload.firstMidBossMin()).isNull();
    }

    @Test
    void bothTeamsEqualClaimsInWinWithLowShareGivesNone() {
        int durationS = 1800;
        MatchHistoryEntry match = createMatch(TARGET_TEAM, TARGET_TEAM, durationS); // win

        // 1 claim for team 0, 1 claim for team 1 (no steals)
        List<MidBossEvent> events = List.of(
                new MidBossEvent(900, TARGET_TEAM, TARGET_TEAM),
                new MidBossEvent(1400, 1, 1)
        );
        PlayerTelemetry targetPlayer = createPlayer(TARGET_ACCOUNT_ID, TARGET_TEAM, 1000L);
        PlayerTelemetry teammate = createPlayer(1000002L, TARGET_TEAM, 9000L);
        MatchTelemetry telemetry = new MatchTelemetry(MATCH_ID, durationS, List.of(targetPlayer, teammate), 50, events);
        CoachRequest request = createRequest(match, telemetry, targetPlayer);

        Optional<CoachInsight> insightOpt = provider.provide(request);
        assertThat(insightOpt).isPresent();
        ObjectivesInsight payload = (ObjectivesInsight) insightOpt.get().payload();
        assertThat(payload.significance()).isEqualTo("none");
        assertThat(payload.midBossTeam()).isEqualTo(1);
        assertThat(payload.midBossEnemy()).isEqualTo(1);
        assertThat(payload.bossDamageSharePct()).isEqualTo(10.0);
    }

    @Test
    void degradationCasesReturnEmpty() {
        MatchHistoryEntry match = createMatch(TARGET_TEAM, TARGET_TEAM, 1800);
        PlayerTelemetry player = createPlayer(TARGET_ACCOUNT_ID, TARGET_TEAM, 1000L);
        MatchTelemetry telemetry = new MatchTelemetry(MATCH_ID, 1800, List.of(player), 50, List.of());

        // null request
        assertThat(provider.provide(null)).isEmpty();

        // null match
        CoachRequest nullMatch = new CoachRequest(TARGET_ACCOUNT_ID, null, List.of(), telemetry, player, 5, List.of(), List.of());
        assertThat(provider.provide(nullMatch)).isEmpty();

        // null telemetry
        CoachRequest nullTelemetry = new CoachRequest(TARGET_ACCOUNT_ID, match, List.of(), null, player, 5, List.of(), List.of());
        assertThat(provider.provide(nullTelemetry)).isEmpty();

        // null player
        CoachRequest nullPlayer = new CoachRequest(TARGET_ACCOUNT_ID, match, List.of(), telemetry, null, 5, List.of(), List.of());
        assertThat(provider.provide(nullPlayer)).isEmpty();

        // midBoss == null (e.g. raw protobuf fallback)
        MatchTelemetry rawTelemetry = new MatchTelemetry(MATCH_ID, 1800, List.of(player), 50, null);
        CoachRequest rawRequest = createRequest(match, rawTelemetry, player);
        assertThat(provider.provide(rawRequest)).isEmpty();

        // duration below 720 s
        MatchTelemetry duration719 = new MatchTelemetry(MATCH_ID, 719, List.of(player), 50, List.of());
        CoachRequest req719 = createRequest(match, duration719, player);
        assertThat(provider.provide(req719)).isEmpty();

        // duration exactly 720 s returns present
        MatchTelemetry duration720 = new MatchTelemetry(MATCH_ID, 720, List.of(player), 50, List.of());
        CoachRequest req720 = createRequest(match, duration720, player);
        assertThat(provider.provide(req720)).isPresent();
    }

    @Test
    void playerTeamZeroAndTeamOneAreSymmetric() {
        int durationS = 1800;

        // Perspective 1: Player on Team 0, Enemy on Team 1
        // Event 1: Team 0 claims
        // Event 2: Team 1 killed, Team 0 claimed (steal by team)
        // Event 3: Team 1 claims
        List<MidBossEvent> eventsTeam0 = List.of(
                new MidBossEvent(900, 0, 0),
                new MidBossEvent(1200, 1, 0),
                new MidBossEvent(1500, 1, 1)
        );
        MatchHistoryEntry matchTeam0 = createMatch(0, 0, durationS); // win
        PlayerTelemetry playerTeam0 = createPlayer(TARGET_ACCOUNT_ID, 0, 4000L);
        PlayerTelemetry teammateTeam0 = createPlayer(1000002L, 0, 6000L);
        PlayerTelemetry enemyTeam1 = createPlayer(1000003L, 1, 5000L);
        MatchTelemetry telemetryTeam0 = new MatchTelemetry(MATCH_ID, durationS, List.of(playerTeam0, teammateTeam0, enemyTeam1), 50, eventsTeam0);
        CoachRequest requestTeam0 = createRequest(matchTeam0, telemetryTeam0, playerTeam0);

        ObjectivesInsight payload0 = (ObjectivesInsight) provider.provide(requestTeam0).orElseThrow().payload();

        // Perspective 2: Inverted teams! Player is on Team 1, Enemy is on Team 0
        // Swap team IDs in events: 0 <-> 1
        List<MidBossEvent> eventsTeam1 = List.of(
                new MidBossEvent(900, 1, 1),
                new MidBossEvent(1200, 0, 1),
                new MidBossEvent(1500, 0, 0)
        );
        MatchHistoryEntry matchTeam1 = createMatch(1, 1, durationS); // win
        PlayerTelemetry playerTeam1 = createPlayer(TARGET_ACCOUNT_ID, 1, 4000L);
        PlayerTelemetry teammateTeam1 = createPlayer(1000002L, 1, 6000L);
        PlayerTelemetry enemyTeam0 = createPlayer(1000003L, 0, 5000L);
        MatchTelemetry telemetryTeam1 = new MatchTelemetry(MATCH_ID, durationS, List.of(playerTeam1, teammateTeam1, enemyTeam0), 50, eventsTeam1);
        CoachRequest requestTeam1 = createRequest(matchTeam1, telemetryTeam1, playerTeam1);

        ObjectivesInsight payload1 = (ObjectivesInsight) provider.provide(requestTeam1).orElseThrow().payload();

        assertThat(payload0.significance()).isEqualTo(payload1.significance());
        assertThat(payload0.midBossTeam()).isEqualTo(payload1.midBossTeam());
        assertThat(payload0.midBossEnemy()).isEqualTo(payload1.midBossEnemy());
        assertThat(payload0.rejuvenatorStolenByTeam()).isEqualTo(payload1.rejuvenatorStolenByTeam());
        assertThat(payload0.rejuvenatorStolenByEnemy()).isEqualTo(payload1.rejuvenatorStolenByEnemy());
        assertThat(payload0.bossDamageSharePct()).isEqualTo(payload1.bossDamageSharePct());
        assertThat(payload0.playerBossDamage()).isEqualTo(payload1.playerBossDamage());
        assertThat(payload0.firstMidBossMin()).isEqualTo(payload1.firstMidBossMin());
    }

    @Test
    void damageFieldsOmittedWhenPlayerDamageNullOrTeamTotalZero() throws Exception {
        int durationS = 1800;
        MatchHistoryEntry match = createMatch(TARGET_TEAM, TARGET_TEAM, durationS);

        // Case 1: Player boss damage is null
        PlayerTelemetry playerNullDamage = createPlayer(TARGET_ACCOUNT_ID, TARGET_TEAM, null);
        PlayerTelemetry teammate = createPlayer(1000002L, TARGET_TEAM, 5000L);
        MatchTelemetry telemetryNullDamage = new MatchTelemetry(MATCH_ID, durationS, List.of(playerNullDamage, teammate), 50, List.of());
        CoachRequest requestNullDamage = createRequest(match, telemetryNullDamage, playerNullDamage);

        ObjectivesInsight payloadNullDamage = (ObjectivesInsight) provider.provide(requestNullDamage).orElseThrow().payload();
        assertThat(payloadNullDamage.playerBossDamage()).isNull();
        assertThat(payloadNullDamage.bossDamageSharePct()).isNull();

        String jsonNullDamage = objectMapper.writeValueAsString(payloadNullDamage);
        assertThat(jsonNullDamage).doesNotContain("player_boss_damage");
        assertThat(jsonNullDamage).doesNotContain("boss_damage_share_pct");

        // Case 2: Team total damage <= 0 (player 0L, teammate 0L)
        PlayerTelemetry playerZeroDamage = createPlayer(TARGET_ACCOUNT_ID, TARGET_TEAM, 0L);
        PlayerTelemetry teammateZero = createPlayer(1000002L, TARGET_TEAM, 0L);
        MatchTelemetry telemetryZeroDamage = new MatchTelemetry(MATCH_ID, durationS, List.of(playerZeroDamage, teammateZero), 50, List.of());
        CoachRequest requestZeroDamage = createRequest(match, telemetryZeroDamage, playerZeroDamage);

        ObjectivesInsight payloadZeroDamage = (ObjectivesInsight) provider.provide(requestZeroDamage).orElseThrow().payload();
        assertThat(payloadZeroDamage.playerBossDamage()).isEqualTo(0L);
        assertThat(payloadZeroDamage.bossDamageSharePct()).isNull();

        String jsonZeroDamage = objectMapper.writeValueAsString(payloadZeroDamage);
        assertThat(jsonZeroDamage).contains("\"player_boss_damage\":0");
        assertThat(jsonZeroDamage).doesNotContain("boss_damage_share_pct");
    }

    @Test
    void teammateWithNullBossDamageTreatedAsZero() {
        int durationS = 1800;
        MatchHistoryEntry match = createMatch(TARGET_TEAM, TARGET_TEAM, durationS);
        List<MidBossEvent> events = List.of(new MidBossEvent(1200, TARGET_TEAM, TARGET_TEAM));

        // Player has 2000L, teammate has null bossDamage (treated as 0L)
        // Team total = 2000L, share = 100.0%
        PlayerTelemetry player = createPlayer(TARGET_ACCOUNT_ID, TARGET_TEAM, 2000L);
        PlayerTelemetry teammate = createPlayer(1000002L, TARGET_TEAM, null);
        MatchTelemetry telemetry = new MatchTelemetry(MATCH_ID, durationS, List.of(player, teammate), 50, events);
        CoachRequest request = createRequest(match, telemetry, player);

        ObjectivesInsight payload = (ObjectivesInsight) provider.provide(request).orElseThrow().payload();
        assertThat(payload.playerBossDamage()).isEqualTo(2000L);
        assertThat(payload.bossDamageSharePct()).isEqualTo(100.0);
    }

    @Test
    void constantBoundaries() {
        int durationS = 1800;

        // MAJOR_MID_BOSS_ENEMY: 1 enemy claim vs 2 enemy claims
        MatchHistoryEntry winMatch = createMatch(TARGET_TEAM, TARGET_TEAM, durationS);
        PlayerTelemetry player = createPlayer(TARGET_ACCOUNT_ID, TARGET_TEAM, 100L);
        PlayerTelemetry teammate = createPlayer(1000002L, TARGET_TEAM, 900L);

        List<MidBossEvent> oneEnemyClaim = List.of(new MidBossEvent(1000, 1, 1));
        CoachRequest req1Enemy = createRequest(winMatch, new MatchTelemetry(MATCH_ID, durationS, List.of(player, teammate), 50, oneEnemyClaim), player);
        assertThat(((ObjectivesInsight) provider.provide(req1Enemy).orElseThrow().payload()).significance()).isEqualTo("none");

        List<MidBossEvent> twoEnemyClaims = List.of(new MidBossEvent(1000, 1, 1), new MidBossEvent(1500, 1, 1));
        CoachRequest req2Enemy = createRequest(winMatch, new MatchTelemetry(MATCH_ID, durationS, List.of(player, teammate), 50, twoEnemyClaims), player);
        assertThat(((ObjectivesInsight) provider.provide(req2Enemy).orElseThrow().payload()).significance()).isEqualTo("major");

        // MAJOR_STOLEN_BY_ENEMY: 0 steals vs 1 steal
        List<MidBossEvent> zeroStealsByEnemy = List.of(new MidBossEvent(1000, 1, 1)); // enemy killed and claimed
        CoachRequest req0Steals = createRequest(winMatch, new MatchTelemetry(MATCH_ID, durationS, List.of(player, teammate), 50, zeroStealsByEnemy), player);
        assertThat(((ObjectivesInsight) provider.provide(req0Steals).orElseThrow().payload()).significance()).isNotEqualTo("major");

        List<MidBossEvent> oneStealByEnemy = List.of(new MidBossEvent(1000, TARGET_TEAM, 1)); // our team killed, enemy claimed
        CoachRequest req1Steal = createRequest(winMatch, new MatchTelemetry(MATCH_ID, durationS, List.of(player, teammate), 50, oneStealByEnemy), player);
        assertThat(((ObjectivesInsight) provider.provide(req1Steal).orElseThrow().payload()).significance()).isEqualTo("major");

        // NOTABLE_MID_BOSS_ENEMY_LOSS: 0 enemy claims on loss vs 1 enemy claim on loss
        MatchHistoryEntry lossMatch = createMatch(1, TARGET_TEAM, durationS);
        List<MidBossEvent> zeroClaimsLoss = List.of();
        CoachRequest reqLoss0 = createRequest(lossMatch, new MatchTelemetry(MATCH_ID, durationS, List.of(player, teammate), 50, zeroClaimsLoss), player);
        assertThat(((ObjectivesInsight) provider.provide(reqLoss0).orElseThrow().payload()).significance()).isEqualTo("none");

        CoachRequest reqLoss1 = createRequest(lossMatch, new MatchTelemetry(MATCH_ID, durationS, List.of(player, teammate), 50, oneEnemyClaim), player);
        assertThat(((ObjectivesInsight) provider.provide(reqLoss1).orElseThrow().payload()).significance()).isEqualTo("notable");

        // NOTABLE_STOLEN_BY_TEAM: 0 steals vs 1 steal
        List<MidBossEvent> zeroStealsByTeam = List.of(new MidBossEvent(1000, TARGET_TEAM, TARGET_TEAM));
        CoachRequest req0StealsTeam = createRequest(winMatch, new MatchTelemetry(MATCH_ID, durationS, List.of(player, teammate), 50, zeroStealsByTeam), player);
        assertThat(((ObjectivesInsight) provider.provide(req0StealsTeam).orElseThrow().payload()).rejuvenatorStolenByTeam()).isZero();

        List<MidBossEvent> oneStealByTeam = List.of(new MidBossEvent(1000, 1, TARGET_TEAM));
        CoachRequest req1StealTeam = createRequest(winMatch, new MatchTelemetry(MATCH_ID, durationS, List.of(player, teammate), 50, oneStealByTeam), player);
        assertThat(((ObjectivesInsight) provider.provide(req1StealTeam).orElseThrow().payload()).significance()).isEqualTo("notable");

        // NOTABLE_MID_BOSS_TEAM: 0 team claims vs 1 team claim (with share >= 25%)
        PlayerTelemetry strongPlayer = createPlayer(TARGET_ACCOUNT_ID, TARGET_TEAM, 500L);
        PlayerTelemetry normalTeammate = createPlayer(1000002L, TARGET_TEAM, 500L); // share 50%
        CoachRequest req0TeamClaim = createRequest(winMatch, new MatchTelemetry(MATCH_ID, durationS, List.of(strongPlayer, normalTeammate), 50, List.of()), strongPlayer);
        assertThat(((ObjectivesInsight) provider.provide(req0TeamClaim).orElseThrow().payload()).significance()).isEqualTo("none");

        CoachRequest req1TeamClaim = createRequest(winMatch, new MatchTelemetry(MATCH_ID, durationS, List.of(strongPlayer, normalTeammate), 50, List.of(new MidBossEvent(1000, TARGET_TEAM, TARGET_TEAM))), strongPlayer);
        assertThat(((ObjectivesInsight) provider.provide(req1TeamClaim).orElseThrow().payload()).significance()).isEqualTo("notable");
    }

    @Test
    void determinismAndNoMutation() {
        int durationS = 1800;
        MatchHistoryEntry match = createMatch(TARGET_TEAM, TARGET_TEAM, durationS);
        List<MidBossEvent> events = List.of(
                new MidBossEvent(1200, 1, TARGET_TEAM),
                new MidBossEvent(900, TARGET_TEAM, TARGET_TEAM)
        );
        PlayerTelemetry player = createPlayer(TARGET_ACCOUNT_ID, TARGET_TEAM, 3000L);
        PlayerTelemetry teammate = createPlayer(1000002L, TARGET_TEAM, 7000L);
        MatchTelemetry telemetry = new MatchTelemetry(MATCH_ID, durationS, List.of(player, teammate), 50, events);
        CoachRequest request = createRequest(match, telemetry, player);

        Optional<CoachInsight> call1 = provider.provide(request);
        Optional<CoachInsight> call2 = provider.provide(request);

        assertThat(call1).isPresent();
        assertThat(call2).isPresent();
        assertThat(call1.get()).isEqualTo(call2.get());
        assertThat(call1.get().payload()).isEqualTo(call2.get().payload());

        // Verify telemetry list remains sorted by timeS
        assertThat(telemetry.midBoss()).containsExactly(
                new MidBossEvent(900, TARGET_TEAM, TARGET_TEAM),
                new MidBossEvent(1200, 1, TARGET_TEAM)
        );
    }
}
