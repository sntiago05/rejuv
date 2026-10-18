package sntiago05.rejuv.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.twice;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import sntiago05.rejuv.config.DeadlockApiConfig;
import sntiago05.rejuv.config.DeadlockApiProperties;
import sntiago05.rejuv.domain.DeathEvent;
import sntiago05.rejuv.domain.ItemPurchase;
import sntiago05.rejuv.domain.MatchTelemetry;
import sntiago05.rejuv.domain.PlayerTelemetry;
import sntiago05.rejuv.domain.PowerUpBuff;
import sntiago05.rejuv.domain.SnapshotDetail;
import sntiago05.rejuv.domain.StatSnapshot;

class JsonMatchMetadataSourceTest {

    static final String BASE_URL = "https://deadlock-api.test";
    static final long MATCH_ID = 900000003L;
    static final String METADATA_URL = BASE_URL + "/v1/matches/" + MATCH_ID + "/metadata";

    static final DeadlockApiProperties PROPERTIES = new DeadlockApiProperties(BASE_URL, Duration.ofSeconds(3),
            Duration.ofSeconds(10), Duration.ofSeconds(2), Duration.ofSeconds(10));

    private final List<Duration> sleeps = new ArrayList<>();
    private MockRestServiceServer server;
    private JsonMatchMetadataSource source;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = DeadlockApiConfig.restClientBuilder(PROPERTIES);
        server = MockRestServiceServer.bindTo(builder).build();
        source = new JsonMatchMetadataSource(builder.build(), PROPERTIES, sleeps::add);
    }

    @AfterEach
    void verifyServer() {
        server.verify();
    }

    static ClassPathResource fixture(String name) {
        return new ClassPathResource("fixtures/deadlock-api/" + name);
    }

    @Test
    void parsesFixtureIntoTelemetry() {
        server.expect(once(), requestTo(METADATA_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(request -> assertThat(request.getURI().getQuery()).isNull())
                .andRespond(withSuccess(fixture("match-metadata.json"), MediaType.APPLICATION_JSON));

        MatchTelemetry telemetry = source.getMatch(MATCH_ID);

        assertThat(telemetry.matchId()).isEqualTo(MATCH_ID);
        assertThat(telemetry.durationS()).isEqualTo(1952);
        assertThat(telemetry.players()).hasSize(2);

        PlayerTelemetry p1 = telemetry.players().get(0);
        assertThat(p1.accountId()).isEqualTo(1000001L);
        assertThat(p1.team()).isEqualTo(1);
        assertThat(p1.heroId()).isEqualTo(1);

        PlayerTelemetry p2 = telemetry.players().get(1);
        assertThat(p2.accountId()).isEqualTo(1000002L);
        assertThat(p2.team()).isZero();
        assertThat(p2.heroId()).isEqualTo(13);
    }

    @Test
    void mapsItemsSortedWithSoldTimeAndLongIds() {
        server.expect(once(), requestTo(METADATA_URL))
                .andRespond(withSuccess(fixture("match-metadata.json"), MediaType.APPLICATION_JSON));

        MatchTelemetry telemetry = source.getMatch(MATCH_ID);
        PlayerTelemetry p1 = telemetry.players().get(0);

        assertThat(p1.items()).containsExactly(
                new ItemPurchase(1001L, 200, 600),
                new ItemPurchase(1002L, 200, null),
                new ItemPurchase(3612874202L, 400, null));
    }

    private static SnapshotDetail snapshotDetailWithCreeps(int creepKills) {
        return new SnapshotDetail(0, 0, 0, creepKills, 0, 0, 0, 0, 0L, 0L, 0L, 0L, 0L);
    }

    @Test
    void mapsSnapshotsSortedIncludingFinal() {
        server.expect(once(), requestTo(METADATA_URL))
                .andRespond(withSuccess(fixture("match-metadata.json"), MediaType.APPLICATION_JSON));

        MatchTelemetry telemetry = source.getMatch(MATCH_ID);
        PlayerTelemetry p1 = telemetry.players().get(0);

        assertThat(p1.snapshots()).containsExactly(
                new StatSnapshot(180, 1500L, 500L, 0L, snapshotDetailWithCreeps(12)),
                new StatSnapshot(360, 3200L, 1200L, 100L, snapshotDetailWithCreeps(25)),
                new StatSnapshot(540, 5100L, 2200L, 200L, snapshotDetailWithCreeps(40)),
                new StatSnapshot(720, 7500L, 3800L, 300L, snapshotDetailWithCreeps(55)),
                new StatSnapshot(900, 10200L, 5500L, 450L, snapshotDetailWithCreeps(70)),
                new StatSnapshot(1200, 14000L, 8000L, 700L, snapshotDetailWithCreeps(90)),
                new StatSnapshot(1500, 18500L, 12000L, 950L, snapshotDetailWithCreeps(115)),
                new StatSnapshot(1800, 23000L, 16500L, 1200L, snapshotDetailWithCreeps(140)),
                new StatSnapshot(1952, 26000L, 19500L, 1500L, snapshotDetailWithCreeps(155)));
        assertThat(p1.playerSlot()).isNull();
        assertThat(p1.assignedLane()).isNull();
        assertThat(p1.deaths()).isEmpty();
    }

    @Test
    void mapsCoachFixtureWithRichTelemetry() {
        server.expect(once(), requestTo(METADATA_URL))
                .andRespond(withSuccess(fixture("match-metadata-coach.json"), MediaType.APPLICATION_JSON));

        MatchTelemetry telemetry = source.getMatch(MATCH_ID);
        assertThat(telemetry.players()).hasSize(3);

        PlayerTelemetry p1 = telemetry.players().get(0);
        assertThat(p1.accountId()).isEqualTo(1000001L);
        assertThat(p1.playerSlot()).isEqualTo(2);
        assertThat(p1.assignedLane()).isEqualTo(3);
        // deaths out of order in JSON (500s then 250s) are sorted ascending
        assertThat(p1.deaths()).containsExactly(
                new DeathEvent(250, 6, 10.0, 15),
                new DeathEvent(500, 8, 15.5, 25));
        assertThat(p1.snapshots()).containsExactly(
                new StatSnapshot(180, 1500L, 500L, 0L,
                        new SnapshotDetail(1, 0, 2, 20, 25, 100, 20, 3, 800L, 1200L, 300L, 150L, 0L)));

        PlayerTelemetry p2 = telemetry.players().get(1);
        assertThat(p2.accountId()).isEqualTo(1000002L);
        assertThat(p2.playerSlot()).isEqualTo(8);
        assertThat(p2.assignedLane()).isNull(); // absent lane = null
        assertThat(p2.deaths()).isEmpty(); // death_details absent = empty
        // missing detail fields default to 0
        assertThat(p2.snapshots()).containsExactly(
                new StatSnapshot(180, 1200L, 300L, 50L,
                        new SnapshotDetail(0, 0, 0, 0, 0, 0, 0, 0, 0L, 0L, 0L, 0L, 0L)));

        PlayerTelemetry p3 = telemetry.players().get(2);
        assertThat(p3.accountId()).isEqualTo(1000003L);
        assertThat(p3.playerSlot()).isNull();
        assertThat(p3.assignedLane()).isEqualTo(1);
        assertThat(p3.deaths()).isEmpty(); // empty death_details
        assertThat(p3.snapshots()).containsExactly(
                new StatSnapshot(180, 1100L, 200L, 0L,
                        new SnapshotDetail(4, 0, 0, 0, 0, 0, 0, 0, 0L, 0L, 0L, 0L, 0L)));
    }

    @Test
    void mapsBuffsAndNullBuffsAsEmpty() {
        server.expect(once(), requestTo(METADATA_URL))
                .andRespond(withSuccess(fixture("match-metadata.json"), MediaType.APPLICATION_JSON));

        MatchTelemetry telemetry = source.getMatch(MATCH_ID);
        PlayerTelemetry p1 = telemetry.players().get(0);
        PlayerTelemetry p2 = telemetry.players().get(1);

        assertThat(p1.powerUpBuffs()).containsExactly(
                new PowerUpBuff("spirit_permanent_pickup_lv3", true, List.of(420, 650)),
                new PowerUpBuff("gun_powerup_pickup", false, List.of(800)));

        assertThat(p2.powerUpBuffs()).isEmpty();
    }

    @Test
    void retriesOnceOn503WithinCap() {
        server.expect(once(), requestTo(METADATA_URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "1"));
        server.expect(once(), requestTo(METADATA_URL))
                .andRespond(withSuccess(fixture("match-metadata.json"), MediaType.APPLICATION_JSON));

        MatchTelemetry telemetry = source.getMatch(MATCH_ID);

        assertThat(telemetry.matchId()).isEqualTo(MATCH_ID);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
    }

    @Test
    void status503WithoutRetryAfterUsesDefaultWait() {
        server.expect(once(), requestTo(METADATA_URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(once(), requestTo(METADATA_URL))
                .andRespond(withSuccess(fixture("match-metadata.json"), MediaType.APPLICATION_JSON));

        MatchTelemetry telemetry = source.getMatch(MATCH_ID);

        assertThat(telemetry.matchId()).isEqualTo(MATCH_ID);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(2));
    }

    @Test
    void status503AboveCapIsNotRetried() {
        server.expect(once(), requestTo(METADATA_URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "1265"));

        assertThatThrownBy(() -> source.getMatch(MATCH_ID))
                .isInstanceOfSatisfying(DeadlockApiUnavailableException.class,
                        e -> assertThat(e.retryAfter()).contains(Duration.ofSeconds(1265)));
        assertThat(sleeps).isEmpty();
    }

    @Test
    void status503TwiceThrowsUnavailable() {
        server.expect(twice(), requestTo(METADATA_URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "1"));

        assertThatThrownBy(() -> source.getMatch(MATCH_ID))
                .isInstanceOfSatisfying(DeadlockApiUnavailableException.class,
                        e -> assertThat(e.retryAfter()).isPresent());
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
    }

    @Test
    void rateLimited429IsNotRetried() {
        server.expect(once(), requestTo(METADATA_URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "5"));

        assertThatThrownBy(() -> source.getMatch(MATCH_ID))
                .isInstanceOfSatisfying(DeadlockApiUnavailableException.class,
                        e -> assertThat(e.retryAfter()).contains(Duration.ofSeconds(5)));
        assertThat(sleeps).isEmpty();
    }

    @Test
    void notFound404ThrowsNotFound() {
        server.expect(once(), requestTo(METADATA_URL))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> source.getMatch(MATCH_ID))
                .isInstanceOf(MatchMetadataNotFoundException.class);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void serverError500ThrowsApiException() {
        server.expect(once(), requestTo(METADATA_URL))
                .andRespond(withServerError());

        assertThatThrownBy(() -> source.getMatch(MATCH_ID))
                .isInstanceOf(DeadlockApiException.class)
                .isNotInstanceOf(DeadlockApiUnavailableException.class);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void ioErrorThrowsUnavailable() {
        server.expect(once(), requestTo(METADATA_URL))
                .andRespond(withException(new IOException("connection reset")));

        assertThatThrownBy(() -> source.getMatch(MATCH_ID))
                .isInstanceOf(DeadlockApiUnavailableException.class)
                .hasCauseInstanceOf(Exception.class);
        assertThat(sleeps).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"match_info\":{\"match_id\":900000003,\"duration_s\":10,\"players\":[]}}",
            "{\"match_info\":{\"match_id\":999999999,\"duration_s\":10,\"players\":[{\"account_id\":1}]}}",
            "malformed JSON"
    })
    void invalidBodiesThrowApiException(String body) {
        server.expect(once(), requestTo(METADATA_URL))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> source.getMatch(MATCH_ID))
                .isInstanceOf(DeadlockApiException.class)
                .isNotInstanceOf(DeadlockApiUnavailableException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"team\":1,\"hero_id\":1}",
            "{\"account_id\":1000001,\"hero_id\":1}",
            "{\"account_id\":1000001,\"team\":1}",
            "{\"account_id\":1000001,\"team\":1,\"hero_id\":1,\"items\":[null]}",
            "{\"account_id\":1000001,\"team\":1,\"hero_id\":1,\"items\":[{\"game_time_s\":200}]}",
            "{\"account_id\":1000001,\"team\":1,\"hero_id\":1,\"items\":[{\"item_id\":1001}]}",
            "{\"account_id\":1000001,\"team\":1,\"hero_id\":1,\"stats\":[{\"net_worth\":1500,\"player_damage\":500,\"player_healing\":0}]}",
            "{\"account_id\":1000001,\"team\":1,\"hero_id\":1,\"stats\":[{\"time_stamp_s\":180,\"player_damage\":500,\"player_healing\":0}]}",
            "{\"account_id\":1000001,\"team\":1,\"hero_id\":1,\"stats\":[{\"time_stamp_s\":180,\"net_worth\":1500,\"player_healing\":0}]}",
            "{\"account_id\":1000001,\"team\":1,\"hero_id\":1,\"stats\":[{\"time_stamp_s\":180,\"net_worth\":1500,\"player_damage\":500}]}"
    })
    void missingRequiredFieldsThrowApiException(String player) {
        String body = "{\"match_info\":{\"match_id\":900000003,\"duration_s\":1952,\"players\":[" + player + "]}}";
        server.expect(once(), requestTo(METADATA_URL))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> source.getMatch(MATCH_ID))
                .isInstanceOf(DeadlockApiException.class)
                .isNotInstanceOf(DeadlockApiUnavailableException.class)
                .hasMessageStartingWith("Invalid body:");
    }

    @Test
    void missingSoldTimeMeansNotSold() {
        String body = """
                {"match_info":{"match_id":900000003,"duration_s":1952,"players":[
                {"account_id":1000001,"team":1,"hero_id":1,"items":[{"item_id":1001,"game_time_s":200}]}]}}""";
        server.expect(once(), requestTo(METADATA_URL))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThat(source.getMatch(MATCH_ID).players().get(0).items())
                .containsExactly(new ItemPurchase(1001L, 200, null));
    }
}
