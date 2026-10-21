package sntiago05.rejuv.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.twice;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
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
import sntiago05.rejuv.clients.dto.RankDistributionEntry;
import sntiago05.rejuv.config.DeadlockApiConfig;
import sntiago05.rejuv.config.DeadlockApiProperties;

class RankDistributionClientTest {

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
    void urlIsExactAndFixtureParses() {
        server.expect(once(), requestTo(BASE_URL + "/v1/players/rank/distribution"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fixture("rank-distribution.json"), MediaType.APPLICATION_JSON));

        List<RankDistributionEntry> distribution = client.getRankDistribution();

        assertThat(distribution).hasSize(3);
        assertThat(distribution.get(0)).isEqualTo(new RankDistributionEntry(11, 1000L));
        assertThat(distribution.get(1)).isEqualTo(new RankDistributionEntry(12, 2000L));
        assertThat(distribution.get(2)).isEqualTo(new RankDistributionEntry(21, 3000L));
    }

    @Test
    void emptyArrayReturnsEmptyList() {
        server.expect(once(), requestTo(BASE_URL + "/v1/players/rank/distribution"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        List<RankDistributionEntry> distribution = client.getRankDistribution();

        assertThat(distribution).isEmpty();
    }

    @Test
    void serviceUnavailableRetriedOnceThenThrows() {
        server.expect(twice(), requestTo(BASE_URL + "/v1/players/rank/distribution"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "1"));

        assertThatThrownBy(() -> client.getRankDistribution())
                .isInstanceOf(DeadlockApiUnavailableException.class);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
    }

    @Test
    void serverErrorThrowsDeadlockApiException() {
        server.expect(once(), requestTo(BASE_URL + "/v1/players/rank/distribution"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.getRankDistribution())
                .isInstanceOf(DeadlockApiException.class);
    }
}
