package sntiago05.rejuv.services;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import sntiago05.rejuv.clients.dto.MatchHistoryEntry;
import sntiago05.rejuv.domain.MatchTelemetry;
import sntiago05.rejuv.domain.PlayerTelemetry;
import sntiago05.rejuv.domain.SnapshotDetail;
import sntiago05.rejuv.domain.StatSnapshot;
import sntiago05.rejuv.services.AimMechanicsCoachProvider.AimMechanicsInsight;

import static org.assertj.core.api.Assertions.assertThat;

class AimMechanicsCoachProviderTest {

    private static final long MATCH_ID = 900000001L;
    private static final long TARGET_ACCOUNT_ID = 1000001L;
    private static final int DEFAULT_HERO_ID = 13; // Haze

    private AimMechanicsCoachProvider provider;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        provider = new AimMechanicsCoachProvider();
        objectMapper = new JsonMapper();
    }

    private MatchHistoryEntry createMatch(int heroId) {
        return new MatchHistoryEntry(
                MATCH_ID, heroId, 10, 1700000000L, 1800, 4,
                0, 0, 0, 5, 2, 8, 20000L, 100, 10, 50
        );
    }

    private PlayerTelemetry createPlayer(int heroId, List<StatSnapshot> snapshots) {
        return new PlayerTelemetry(
                TARGET_ACCOUNT_ID, 0, heroId,
                List.of(), snapshots, List.of(),
                0, 1, List.of(), 5000L
        );
    }

    private StatSnapshot createSnapshot(int timeS, int shotsHit, int shotsMissed, Long heroBulletsHit, Long heroBulletsHitCrit) {
        SnapshotDetail detail = new SnapshotDetail(
                0, 0, 0, 0, 0,
                shotsHit, shotsMissed, 0, 0L, 0L, 0L, 0L, 0L,
                heroBulletsHit, heroBulletsHitCrit
        );
        return new StatSnapshot(timeS, 10000L, 5000L, 0L, detail);
    }

    private CoachRequest createRequest(MatchHistoryEntry match, MatchTelemetry telemetry, PlayerTelemetry player) {
        return new CoachRequest(
                TARGET_ACCOUNT_ID, match, List.of(match), telemetry, player, 5, List.of(), List.of()
        );
    }

    @Test
    void keyMatchesContract() {
        assertThat(provider.key()).isEqualTo("aim_mechanics");
        assertThat(AimMechanicsCoachProvider.KEY).isEqualTo("aim_mechanics");
    }

    @Test
    void happyPathRealisticMatchAssertsExactPayloadAndJson() throws Exception {
        MatchHistoryEntry match = createMatch(DEFAULT_HERO_ID);
        StatSnapshot snapshot = createSnapshot(1800, 600, 600, 500L, 75L);
        PlayerTelemetry player = createPlayer(DEFAULT_HERO_ID, List.of(snapshot));
        MatchTelemetry telemetry = new MatchTelemetry(MATCH_ID, 1800, List.of(player), 50, List.of());
        CoachRequest request = createRequest(match, telemetry, player);

        Optional<CoachInsight> insightOpt = provider.provide(request);
        assertThat(insightOpt).isPresent();

        CoachInsight insight = insightOpt.get();
        assertThat(insight.key()).isEqualTo("aim_mechanics");
        assertThat(insight.payload()).isInstanceOf(AimMechanicsInsight.class);

        AimMechanicsInsight payload = (AimMechanicsInsight) insight.payload();
        assertThat(payload.significance()).isEqualTo("none");
        assertThat(payload.accuracyPct()).isEqualTo(50.0);
        assertThat(payload.shots()).isEqualTo(1200);
        assertThat(payload.critPct()).isEqualTo(15.0);

        String json = objectMapper.writeValueAsString(payload);
        assertThat(json).isEqualTo(
                "{\"significance\":\"none\"," +
                "\"accuracy_pct\":50.0," +
                "\"shots\":1200," +
                "\"crit_pct\":15.0}"
        );
        assertThat(json.length()).isLessThanOrEqualTo(1200);
    }

    @ParameterizedTest
    @ValueSource(ints = {13, 25, 58, 80, 84})
    void everyHeroInGunHeroesSetIsAccepted(int heroId) {
        MatchHistoryEntry match = createMatch(heroId);
        StatSnapshot snapshot = createSnapshot(1800, 500, 500, 400L, 60L);
        PlayerTelemetry player = createPlayer(heroId, List.of(snapshot));
        MatchTelemetry telemetry = new MatchTelemetry(MATCH_ID, 1800, List.of(player), 50, List.of());
        CoachRequest request = createRequest(match, telemetry, player);

        Optional<CoachInsight> insightOpt = provider.provide(request);
        assertThat(insightOpt).isPresent();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 7, 31, 99})
    void nonGunHeroReturnsEmpty(int heroId) {
        MatchHistoryEntry match = createMatch(heroId);
        StatSnapshot snapshot = createSnapshot(1800, 500, 500, 400L, 60L);
        PlayerTelemetry player = createPlayer(heroId, List.of(snapshot));
        MatchTelemetry telemetry = new MatchTelemetry(MATCH_ID, 1800, List.of(player), 50, List.of());
        CoachRequest request = createRequest(match, telemetry, player);

        Optional<CoachInsight> insightOpt = provider.provide(request);
        assertThat(insightOpt).isEmpty();
    }

    @Test
    void minShotsBoundary499ReturnsEmptyAnd500IsAccepted() {
        MatchHistoryEntry match = createMatch(DEFAULT_HERO_ID);

        // 499 shots (250 hit, 249 missed) -> empty
        StatSnapshot snapshot499 = createSnapshot(1800, 250, 249, 300L, 30L);
        PlayerTelemetry player499 = createPlayer(DEFAULT_HERO_ID, List.of(snapshot499));
        MatchTelemetry telemetry499 = new MatchTelemetry(MATCH_ID, 1800, List.of(player499), 50, List.of());
        CoachRequest request499 = createRequest(match, telemetry499, player499);
        assertThat(provider.provide(request499)).isEmpty();

        // 500 shots (250 hit, 250 missed) -> present
        StatSnapshot snapshot500 = createSnapshot(1800, 250, 250, 300L, 30L);
        PlayerTelemetry player500 = createPlayer(DEFAULT_HERO_ID, List.of(snapshot500));
        MatchTelemetry telemetry500 = new MatchTelemetry(MATCH_ID, 1800, List.of(player500), 50, List.of());
        CoachRequest request500 = createRequest(match, telemetry500, player500);

        Optional<CoachInsight> insightOpt = provider.provide(request500);
        assertThat(insightOpt).isPresent();
        AimMechanicsInsight payload = (AimMechanicsInsight) insightOpt.get().payload();
        assertThat(payload.shots()).isEqualTo(500);
        assertThat(payload.accuracyPct()).isEqualTo(50.0);
        assertThat(payload.significance()).isEqualTo("none");
    }

    @Test
    void notableAccuracyBoundary47Point9Vs48Point0() {
        MatchHistoryEntry match = createMatch(DEFAULT_HERO_ID);

        // 47.9% accuracy with 1000 shots (479 hit, 521 missed) -> notable
        StatSnapshot snapshot479 = createSnapshot(1800, 479, 521, 500L, 50L);
        PlayerTelemetry player479 = createPlayer(DEFAULT_HERO_ID, List.of(snapshot479));
        MatchTelemetry telemetry479 = new MatchTelemetry(MATCH_ID, 1800, List.of(player479), 50, List.of());
        CoachRequest request479 = createRequest(match, telemetry479, player479);

        AimMechanicsInsight payload479 = (AimMechanicsInsight) provider.provide(request479).orElseThrow().payload();
        assertThat(payload479.accuracyPct()).isEqualTo(47.9);
        assertThat(payload479.significance()).isEqualTo("notable");

        // 48.0% accuracy with 1000 shots (480 hit, 520 missed) -> none
        StatSnapshot snapshot480 = createSnapshot(1800, 480, 520, 500L, 50L);
        PlayerTelemetry player480 = createPlayer(DEFAULT_HERO_ID, List.of(snapshot480));
        MatchTelemetry telemetry480 = new MatchTelemetry(MATCH_ID, 1800, List.of(player480), 50, List.of());
        CoachRequest request480 = createRequest(match, telemetry480, player480);

        AimMechanicsInsight payload480 = (AimMechanicsInsight) provider.provide(request480).orElseThrow().payload();
        assertThat(payload480.accuracyPct()).isEqualTo(48.0);
        assertThat(payload480.significance()).isEqualTo("none");
    }

    @Test
    void majorAccuracyBoundaries31Point9Vs32Point0With999And1000Shots() {
        MatchHistoryEntry match = createMatch(DEFAULT_HERO_ID);

        // 1000 shots, 319 hit (31.9% accuracy): shots >= 1000 and accuracy < 32.0 -> major
        StatSnapshot s1000_319 = createSnapshot(1800, 319, 681, 500L, 50L);
        PlayerTelemetry p1000_319 = createPlayer(DEFAULT_HERO_ID, List.of(s1000_319));
        AimMechanicsInsight res1000_319 = (AimMechanicsInsight) provider.provide(
                createRequest(match, new MatchTelemetry(MATCH_ID, 1800, List.of(p1000_319), 50, List.of()), p1000_319)
        ).orElseThrow().payload();
        assertThat(res1000_319.shots()).isEqualTo(1000);
        assertThat(res1000_319.accuracyPct()).isEqualTo(31.9);
        assertThat(res1000_319.significance()).isEqualTo("major");

        // 1000 shots, 320 hit (32.0% accuracy): shots >= 1000 but accuracy not < 32.0 -> notable
        StatSnapshot s1000_320 = createSnapshot(1800, 320, 680, 500L, 50L);
        PlayerTelemetry p1000_320 = createPlayer(DEFAULT_HERO_ID, List.of(s1000_320));
        AimMechanicsInsight res1000_320 = (AimMechanicsInsight) provider.provide(
                createRequest(match, new MatchTelemetry(MATCH_ID, 1800, List.of(p1000_320), 50, List.of()), p1000_320)
        ).orElseThrow().payload();
        assertThat(res1000_320.shots()).isEqualTo(1000);
        assertThat(res1000_320.accuracyPct()).isEqualTo(32.0);
        assertThat(res1000_320.significance()).isEqualTo("notable");

        // 999 shots, 319 hit (319 / 999 * 100 = 31.93... -> 31.9%): shots < 1000 so NOT major, but < 48.0 -> notable
        StatSnapshot s999_319 = createSnapshot(1800, 319, 680, 500L, 50L);
        PlayerTelemetry p999_319 = createPlayer(DEFAULT_HERO_ID, List.of(s999_319));
        AimMechanicsInsight res999_319 = (AimMechanicsInsight) provider.provide(
                createRequest(match, new MatchTelemetry(MATCH_ID, 1800, List.of(p999_319), 50, List.of()), p999_319)
        ).orElseThrow().payload();
        assertThat(res999_319.shots()).isEqualTo(999);
        assertThat(res999_319.accuracyPct()).isEqualTo(31.9);
        assertThat(res999_319.significance()).isEqualTo("notable");

        // 999 shots, 320 hit (320 / 999 * 100 = 32.03... -> 32.0%): shots < 1000, < 48.0 -> notable
        StatSnapshot s999_320 = createSnapshot(1800, 320, 679, 500L, 50L);
        PlayerTelemetry p999_320 = createPlayer(DEFAULT_HERO_ID, List.of(s999_320));
        AimMechanicsInsight res999_320 = (AimMechanicsInsight) provider.provide(
                createRequest(match, new MatchTelemetry(MATCH_ID, 1800, List.of(p999_320), 50, List.of()), p999_320)
        ).orElseThrow().payload();
        assertThat(res999_320.shots()).isEqualTo(999);
        assertThat(res999_320.accuracyPct()).isEqualTo(32.0);
        assertThat(res999_320.significance()).isEqualTo("notable");
    }

    @Test
    void critOmittedAt249BulletsAndPresentAt250() throws Exception {
        MatchHistoryEntry match = createMatch(DEFAULT_HERO_ID);

        // 249 bullets hit -> critPct null and omitted from JSON
        StatSnapshot s249 = createSnapshot(1800, 300, 300, 249L, 50L);
        PlayerTelemetry p249 = createPlayer(DEFAULT_HERO_ID, List.of(s249));
        AimMechanicsInsight res249 = (AimMechanicsInsight) provider.provide(
                createRequest(match, new MatchTelemetry(MATCH_ID, 1800, List.of(p249), 50, List.of()), p249)
        ).orElseThrow().payload();
        assertThat(res249.critPct()).isNull();
        String json249 = objectMapper.writeValueAsString(res249);
        assertThat(json249).doesNotContain("crit_pct");

        // 250 bullets hit -> critPct present (50 / 250 * 100 = 20.0%)
        StatSnapshot s250 = createSnapshot(1800, 300, 300, 250L, 50L);
        PlayerTelemetry p250 = createPlayer(DEFAULT_HERO_ID, List.of(s250));
        AimMechanicsInsight res250 = (AimMechanicsInsight) provider.provide(
                createRequest(match, new MatchTelemetry(MATCH_ID, 1800, List.of(p250), 50, List.of()), p250)
        ).orElseThrow().payload();
        assertThat(res250.critPct()).isEqualTo(20.0);
        String json250 = objectMapper.writeValueAsString(res250);
        assertThat(json250).contains("\"crit_pct\":20.0");
    }

    @Test
    void critOmittedWhenCritFieldIsNull() throws Exception {
        MatchHistoryEntry match = createMatch(DEFAULT_HERO_ID);

        // heroBulletsHit is null
        StatSnapshot sNullHit = createSnapshot(1800, 300, 300, null, 50L);
        PlayerTelemetry pNullHit = createPlayer(DEFAULT_HERO_ID, List.of(sNullHit));
        AimMechanicsInsight resNullHit = (AimMechanicsInsight) provider.provide(
                createRequest(match, new MatchTelemetry(MATCH_ID, 1800, List.of(pNullHit), 50, List.of()), pNullHit)
        ).orElseThrow().payload();
        assertThat(resNullHit.critPct()).isNull();
        assertThat(objectMapper.writeValueAsString(resNullHit)).doesNotContain("crit_pct");

        // heroBulletsHitCrit is null
        StatSnapshot sNullCrit = createSnapshot(1800, 300, 300, 500L, null);
        PlayerTelemetry pNullCrit = createPlayer(DEFAULT_HERO_ID, List.of(sNullCrit));
        AimMechanicsInsight resNullCrit = (AimMechanicsInsight) provider.provide(
                createRequest(match, new MatchTelemetry(MATCH_ID, 1800, List.of(pNullCrit), 50, List.of()), pNullCrit)
        ).orElseThrow().payload();
        assertThat(resNullCrit.critPct()).isNull();
        assertThat(objectMapper.writeValueAsString(resNullCrit)).doesNotContain("crit_pct");

        // both are null
        StatSnapshot sBothNull = createSnapshot(1800, 300, 300, null, null);
        PlayerTelemetry pBothNull = createPlayer(DEFAULT_HERO_ID, List.of(sBothNull));
        AimMechanicsInsight resBothNull = (AimMechanicsInsight) provider.provide(
                createRequest(match, new MatchTelemetry(MATCH_ID, 1800, List.of(pBothNull), 50, List.of()), pBothNull)
        ).orElseThrow().payload();
        assertThat(resBothNull.critPct()).isNull();
        assertThat(objectMapper.writeValueAsString(resBothNull)).doesNotContain("crit_pct");
    }

    @Test
    void finalSnapshotIsUsedNotTheFirst() {
        MatchHistoryEntry match = createMatch(DEFAULT_HERO_ID);

        // Snapshot 1 at 300s: 200 hit, 800 missed (20.0% accuracy, would be major if evaluated alone)
        StatSnapshot sEarly = createSnapshot(300, 200, 800, 300L, 30L);
        // Snapshot 2 at 1800s: 600 hit, 400 missed (60.0% accuracy -> significance none)
        StatSnapshot sFinal = createSnapshot(1800, 600, 400, 800L, 160L);

        // In chronological order
        PlayerTelemetry pOrder = createPlayer(DEFAULT_HERO_ID, List.of(sEarly, sFinal));
        AimMechanicsInsight resOrder = (AimMechanicsInsight) provider.provide(
                createRequest(match, new MatchTelemetry(MATCH_ID, 1800, List.of(pOrder), 50, List.of()), pOrder)
        ).orElseThrow().payload();
        assertThat(resOrder.accuracyPct()).isEqualTo(60.0);
        assertThat(resOrder.critPct()).isEqualTo(20.0);
        assertThat(resOrder.significance()).isEqualTo("none");

        // In reverse order (sFinal first in list, sEarly second): greatest timeS must still win
        PlayerTelemetry pReverse = createPlayer(DEFAULT_HERO_ID, List.of(sFinal, sEarly));
        AimMechanicsInsight resReverse = (AimMechanicsInsight) provider.provide(
                createRequest(match, new MatchTelemetry(MATCH_ID, 1800, List.of(pReverse), 50, List.of()), pReverse)
        ).orElseThrow().payload();
        assertThat(resReverse.accuracyPct()).isEqualTo(60.0);
        assertThat(resReverse.critPct()).isEqualTo(20.0);
        assertThat(resReverse.significance()).isEqualTo("none");
    }

    @Test
    void critNeverChangesSignificance() {
        MatchHistoryEntry match = createMatch(DEFAULT_HERO_ID);

        // Accuracy 50.0% (none): high crit vs zero crit vs null crit
        StatSnapshot sNoneHighCrit = createSnapshot(1800, 500, 500, 500L, 250L); // 50.0% crit
        StatSnapshot sNoneZeroCrit = createSnapshot(1800, 500, 500, 500L, 0L);   // 0.0% crit
        StatSnapshot sNoneNullCrit = createSnapshot(1800, 500, 500, null, null);

        PlayerTelemetry pNoneHigh = createPlayer(DEFAULT_HERO_ID, List.of(sNoneHighCrit));
        PlayerTelemetry pNoneZero = createPlayer(DEFAULT_HERO_ID, List.of(sNoneZeroCrit));
        PlayerTelemetry pNoneNull = createPlayer(DEFAULT_HERO_ID, List.of(sNoneNullCrit));

        assertThat(((AimMechanicsInsight) provider.provide(createRequest(match, new MatchTelemetry(MATCH_ID, 1800, List.of(pNoneHigh), 50, List.of()), pNoneHigh)).orElseThrow().payload()).significance()).isEqualTo("none");
        assertThat(((AimMechanicsInsight) provider.provide(createRequest(match, new MatchTelemetry(MATCH_ID, 1800, List.of(pNoneZero), 50, List.of()), pNoneZero)).orElseThrow().payload()).significance()).isEqualTo("none");
        assertThat(((AimMechanicsInsight) provider.provide(createRequest(match, new MatchTelemetry(MATCH_ID, 1800, List.of(pNoneNull), 50, List.of()), pNoneNull)).orElseThrow().payload()).significance()).isEqualTo("none");

        // Accuracy 30.0% with 1000 shots (major): high crit vs zero crit
        StatSnapshot sMajorHighCrit = createSnapshot(1800, 300, 700, 500L, 250L);
        StatSnapshot sMajorZeroCrit = createSnapshot(1800, 300, 700, 500L, 0L);

        PlayerTelemetry pMajorHigh = createPlayer(DEFAULT_HERO_ID, List.of(sMajorHighCrit));
        PlayerTelemetry pMajorZero = createPlayer(DEFAULT_HERO_ID, List.of(sMajorZeroCrit));

        assertThat(((AimMechanicsInsight) provider.provide(createRequest(match, new MatchTelemetry(MATCH_ID, 1800, List.of(pMajorHigh), 50, List.of()), pMajorHigh)).orElseThrow().payload()).significance()).isEqualTo("major");
        assertThat(((AimMechanicsInsight) provider.provide(createRequest(match, new MatchTelemetry(MATCH_ID, 1800, List.of(pMajorZero), 50, List.of()), pMajorZero)).orElseThrow().payload()).significance()).isEqualTo("major");

        // Accuracy 40.0% with 1000 shots (notable): high crit vs zero crit
        StatSnapshot sNotableHighCrit = createSnapshot(1800, 400, 600, 500L, 250L);
        StatSnapshot sNotableZeroCrit = createSnapshot(1800, 400, 600, 500L, 0L);

        PlayerTelemetry pNotableHigh = createPlayer(DEFAULT_HERO_ID, List.of(sNotableHighCrit));
        PlayerTelemetry pNotableZero = createPlayer(DEFAULT_HERO_ID, List.of(sNotableZeroCrit));

        assertThat(((AimMechanicsInsight) provider.provide(createRequest(match, new MatchTelemetry(MATCH_ID, 1800, List.of(pNotableHigh), 50, List.of()), pNotableHigh)).orElseThrow().payload()).significance()).isEqualTo("notable");
        assertThat(((AimMechanicsInsight) provider.provide(createRequest(match, new MatchTelemetry(MATCH_ID, 1800, List.of(pNotableZero), 50, List.of()), pNotableZero)).orElseThrow().payload()).significance()).isEqualTo("notable");
    }

    @Test
    void degradationReturnsEmpty() {
        MatchHistoryEntry match = createMatch(DEFAULT_HERO_ID);
        StatSnapshot validSnapshot = createSnapshot(1800, 500, 500, 500L, 50L);
        PlayerTelemetry validPlayer = createPlayer(DEFAULT_HERO_ID, List.of(validSnapshot));
        MatchTelemetry validTelemetry = new MatchTelemetry(MATCH_ID, 1800, List.of(validPlayer), 50, List.of());

        // null request
        assertThat(provider.provide(null)).isEmpty();

        // null telemetry
        assertThat(provider.provide(new CoachRequest(TARGET_ACCOUNT_ID, match, List.of(), null, validPlayer, 5, List.of(), List.of()))).isEmpty();

        // null player
        assertThat(provider.provide(new CoachRequest(TARGET_ACCOUNT_ID, match, List.of(), validTelemetry, null, 5, List.of(), List.of()))).isEmpty();

        // snapshots is empty
        PlayerTelemetry emptySnapshotsPlayer = createPlayer(DEFAULT_HERO_ID, List.of());
        assertThat(provider.provide(createRequest(match, validTelemetry, emptySnapshotsPlayer))).isEmpty();

        // final detail == null (raw fallback)
        StatSnapshot nullDetailSnapshot = new StatSnapshot(1800, 10000L, 5000L, 0L, null);
        PlayerTelemetry nullDetailPlayer = createPlayer(DEFAULT_HERO_ID, List.of(nullDetailSnapshot));
        assertThat(provider.provide(createRequest(match, validTelemetry, nullDetailPlayer))).isEmpty();
    }

    @Test
    void determinismAndPayloadSize() throws Exception {
        MatchHistoryEntry match = createMatch(DEFAULT_HERO_ID);
        StatSnapshot snapshot = createSnapshot(1800, 310, 690, 500L, 50L);
        PlayerTelemetry player = createPlayer(DEFAULT_HERO_ID, List.of(snapshot));
        MatchTelemetry telemetry = new MatchTelemetry(MATCH_ID, 1800, List.of(player), 50, List.of());
        CoachRequest request = createRequest(match, telemetry, player);

        Optional<CoachInsight> call1 = provider.provide(request);
        Optional<CoachInsight> call2 = provider.provide(request);

        assertThat(call1).isPresent();
        assertThat(call2).isPresent();
        assertThat(call1.get()).isEqualTo(call2.get());
        assertThat(call1.get().payload()).isEqualTo(call2.get().payload());

        String json = objectMapper.writeValueAsString(call1.get().payload());
        assertThat(json.length()).isLessThanOrEqualTo(1200);
    }
}
