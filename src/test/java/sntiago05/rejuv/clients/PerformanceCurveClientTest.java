package sntiago05.rejuv.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

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
import sntiago05.rejuv.clients.dto.PerformanceCurvePoint;
import sntiago05.rejuv.config.DeadlockApiConfig;
import sntiago05.rejuv.config.DeadlockApiProperties;

class PerformanceCurveClientTest {

    private static final String BASE_URL = "https://deadlock-api.test";
    private static final DeadlockApiProperties PROPERTIES = new DeadlockApiProperties(BASE_URL, Duration.ofSeconds(3),
            Duration.ofSeconds(10), Duration.ofSeconds(2), Duration.ofSeconds(10));

    private final List<Duration> sleeps = new ArrayList<>();
    private MockRestServiceServer server;
    private DeadlockApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = DeadlockApiConfig.restClientBuilder(PROPERTIES);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new DeadlockApiClient(builder.build(), PROPERTIES, sleeps::add);
    }

    @AfterEach
    void verifyServer() {
        server.verify();
    }

    private static ClassPathResource fixture(String name) {
        return new ClassPathResource("fixtures/deadlock-api/" + name);
    }

    @Test
    void parsesFixtureIntoSortedPointsWithExactQuery() {
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/analytics/player-performance-curve?")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("hero_ids", "31"))
                .andExpect(queryParam("min_average_badge", "100"))
                .andExpect(queryParam("max_average_badge", "109"))
                .andExpect(queryParam("resolution", "0"))
                .andRespond(withSuccess(fixture("player-performance-curve.json"), MediaType.APPLICATION_JSON));

        List<PerformanceCurvePoint> points = client.getPerformanceCurve(31, 10);

        assertThat(points).hasSize(4);
        assertThat(points).extracting(PerformanceCurvePoint::gameTime)
                .containsExactly(180, 360, 720, 1200);

        PerformanceCurvePoint p180 = points.get(0);
        assertThat(p180.gameTime()).isEqualTo(180);
        assertThat(p180.netWorthAvg()).isEqualTo(1804.0);
        assertThat(p180.netWorthStd()).isEqualTo(229.0);
        assertThat(p180.killsAvg()).isEqualTo(0.5);
        assertThat(p180.killsStd()).isEqualTo(0.3);
        assertThat(p180.deathsAvg()).isEqualTo(0.3);
        assertThat(p180.deathsStd()).isEqualTo(0.2);
        assertThat(p180.assistsAvg()).isEqualTo(0.4);
        assertThat(p180.assistsStd()).isEqualTo(0.3);
        assertThat(p180.playerDamageAvg()).isEqualTo(800.0);
        assertThat(p180.playerDamageStd()).isEqualTo(200.0);
        assertThat(p180.deniesAvg()).isEqualTo(1.0);
        assertThat(p180.deniesStd()).isEqualTo(0.5);
        assertThat(p180.creepKillsAvg()).isEqualTo(15.0);
        assertThat(p180.creepKillsStd()).isEqualTo(3.0);
    }

    @Test
    void emptyArrayReturnsEmptyList() {
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/analytics/player-performance-curve?")))
                .andExpect(queryParam("hero_ids", "31"))
                .andExpect(queryParam("min_average_badge", "100"))
                .andExpect(queryParam("max_average_badge", "109"))
                .andExpect(queryParam("resolution", "0"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        List<PerformanceCurvePoint> points = client.getPerformanceCurve(31, 10);

        assertThat(points).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 12, 100})
    void invalidTierThrowsBeforeHttpCall(int invalidTier) {
        server.expect(never(), requestTo(startsWith(BASE_URL)));

        assertThatThrownBy(() -> client.getPerformanceCurve(31, invalidTier))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rankTier must be in 0..11");
    }

    @Test
    void retriesOnceOn503WithinCapThenSucceeds() {
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/analytics/player-performance-curve?")))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "1"));
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/analytics/player-performance-curve?")))
                .andRespond(withSuccess(fixture("player-performance-curve.json"), MediaType.APPLICATION_JSON));

        List<PerformanceCurvePoint> points = client.getPerformanceCurve(31, 10);

        assertThat(points).hasSize(4);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
    }
}
