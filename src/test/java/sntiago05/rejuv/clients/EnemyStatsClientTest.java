package sntiago05.rejuv.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.twice;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import sntiago05.rejuv.clients.dto.EnemyStat;
import sntiago05.rejuv.config.DeadlockApiConfig;
import sntiago05.rejuv.config.DeadlockApiProperties;

class EnemyStatsClientTest {

    private static final String BASE_URL = "https://deadlock-api.test";
    private static final DeadlockApiProperties PROPERTIES = new DeadlockApiProperties(
            BASE_URL, Duration.ofSeconds(3), Duration.ofSeconds(10), Duration.ofSeconds(2), Duration.ofSeconds(10));

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

    private static ClassPathResource fixture(String filename) {
        return new ClassPathResource("fixtures/deadlock-api/" + filename);
    }

    @Test
    void urlAndQueryParametersAreExactAndFixtureParses() {
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/players/1000001/enemy-stats?")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("min_unix_timestamp", "1784497204"))
                .andRespond(withSuccess(fixture("enemy-stats.json"), MediaType.APPLICATION_JSON));

        List<EnemyStat> stats = client.getEnemyStats(1000001L, 1784497204L);

        assertThat(stats).hasSize(3);
        assertThat(stats.get(0)).isEqualTo(new EnemyStat(4000001L, 20L, 5L));
        assertThat(stats.get(1)).isEqualTo(new EnemyStat(4000002L, 10L, 2L));
        assertThat(stats.get(2)).isEqualTo(new EnemyStat(4000003L, 6L, 1L));
    }

    @Test
    void emptyArrayReturnsEmptyList() {
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/players/1000001/enemy-stats?")))
                .andExpect(queryParam("min_unix_timestamp", "1784497204"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        List<EnemyStat> stats = client.getEnemyStats(1000001L, 1784497204L);

        assertThat(stats).isEmpty();
    }

    @Test
    void serviceUnavailableRetriedOnceThenThrows() {
        server.expect(twice(), requestTo(startsWith(BASE_URL + "/v1/players/1000001/enemy-stats?")))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "1"));

        assertThatThrownBy(() -> client.getEnemyStats(1000001L, 1784497204L))
                .isInstanceOf(DeadlockApiUnavailableException.class);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
    }

    @Test
    void serverErrorThrowsDeadlockApiException() {
        server.expect(once(), requestTo(startsWith(BASE_URL + "/v1/players/1000001/enemy-stats?")))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.getEnemyStats(1000001L, 1784497204L))
                .isInstanceOf(DeadlockApiException.class);
    }
}
