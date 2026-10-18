package sntiago05.rejuv.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.never;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

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
import sntiago05.rejuv.clients.dto.AccountRank;
import sntiago05.rejuv.config.DeadlockApiConfig;
import sntiago05.rejuv.config.DeadlockApiProperties;

class PlayerRanksClientTest {

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
    void singleAccountIdSendsCorrectUrlAndQuery() {
        server.expect(once(), requestTo(BASE_URL + "/v1/players/rank?account_ids=1000001"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("account_ids", "1000001"))
                .andRespond(withSuccess(fixture("player-ranks.json"), MediaType.APPLICATION_JSON));

        Map<Long, AccountRank> result = client.getPlayerRanks(List.of(1000001L));

        assertThat(result).containsKey(1000001L);
    }

    @Test
    void threeAccountIdsSendsCommaSeparatedQuery() {
        server.expect(once(), requestTo(BASE_URL + "/v1/players/rank?account_ids=1000001,1000002,1000003"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("account_ids", "1000001,1000002,1000003"))
                .andRespond(withSuccess(fixture("player-ranks.json"), MediaType.APPLICATION_JSON));

        Map<Long, AccountRank> result = client.getPlayerRanks(List.of(1000001L, 1000002L, 1000003L));

        assertThat(result).hasSize(4);
    }

    @Test
    void parsesFixtureAndIgnoresUnknownFieldsAndLastMatch() {
        server.expect(once(), requestTo(BASE_URL + "/v1/players/rank?account_ids=1000001"))
                .andRespond(withSuccess(fixture("player-ranks.json"), MediaType.APPLICATION_JSON));

        Map<Long, AccountRank> ranks = client.getPlayerRanks(List.of(1000001L));

        assertThat(ranks).hasSize(4);
        assertThat(ranks.get(1000001L)).isEqualTo(new AccountRank(1000001L, 74, 7, 4));
        assertThat(ranks.get(1000002L)).isEqualTo(new AccountRank(1000002L, 116, 11, 6));
        assertThat(ranks.get(1000003L)).isEqualTo(new AccountRank(1000003L, 0, 0, 0));
        assertThat(ranks.get(1000004L)).isEqualTo(new AccountRank(1000004L, 82, 8, 2));
    }

    @Test
    void emptyArrayReturnsEmptyMap() {
        server.expect(once(), requestTo(BASE_URL + "/v1/players/rank?account_ids=1000001"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        Map<Long, AccountRank> ranks = client.getPlayerRanks(List.of(1000001L));

        assertThat(ranks).isEmpty();
    }

    @Test
    void emptyListThrowsIllegalArgumentExceptionWithNoRequest() {
        assertThatThrownBy(() -> client.getPlayerRanks(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullListThrowsIllegalArgumentExceptionWithNoRequest() {
        assertThatThrownBy(() -> client.getPlayerRanks(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void moreThan1000IdsThrowsIllegalArgumentExceptionWithNoRequest() {
        List<Long> ids = IntStream.rangeClosed(1, 1001).mapToLong(i -> (long) i).boxed().toList();

        assertThatThrownBy(() -> client.getPlayerRanks(ids))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeIdThrowsIllegalArgumentExceptionWithNoRequest() {
        List<Long> ids = List.of(1L, -5L, 3L);

        assertThatThrownBy(() -> client.getPlayerRanks(ids))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void retriesOnceOn503WithRetryAfterThenThrowsUnavailable() {
        server.expect(twice(), requestTo(BASE_URL + "/v1/players/rank?account_ids=1000001"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "1"));

        assertThatThrownBy(() -> client.getPlayerRanks(List.of(1000001L)))
                .isInstanceOfSatisfying(DeadlockApiUnavailableException.class,
                        e -> assertThat(e.retryAfter()).isPresent());

        assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
    }

    @Test
    void internalServerError500ThrowsDeadlockApiException() {
        server.expect(once(), requestTo(BASE_URL + "/v1/players/rank?account_ids=1000001"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.getPlayerRanks(List.of(1000001L)))
                .isInstanceOf(DeadlockApiException.class)
                .isNotInstanceOf(DeadlockApiUnavailableException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{}", "[{\"account_id\":", "not json"})
    void malformedBodyThrowsDeadlockApiException(String body) {
        server.expect(once(), requestTo(BASE_URL + "/v1/players/rank?account_ids=1000001"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.getPlayerRanks(List.of(1000001L)))
                .isInstanceOf(DeadlockApiException.class);
    }

    private static final String STEAM_URL = BASE_URL + "/v1/players/steam?account_ids=";

    @Test
    void playerNamesSendsOnlyAccountIdsWithoutRefreshAndMapsNames() {
        server.expect(once(), requestTo(STEAM_URL + "1000001,1000002"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("account_ids", "1000001,1000002"))
                .andExpect(request -> assertThat(request.getURI().getQuery()).isEqualTo("account_ids=1000001,1000002"))
                .andRespond(withSuccess(fixture("players-steam.json"), MediaType.APPLICATION_JSON));

        Map<Long, String> names = client.getPlayerNames(List.of(1000001L, 1000002L));

        assertThat(names).containsOnly(Map.entry(1000001L, "FakeAlpha"), Map.entry(1000002L, "FakeBravo"));
    }

    @Test
    void playerNamesOmitsMissingNullAndBlankNames() {
        server.expect(once(), requestTo(STEAM_URL + "1,2,3,4"))
                .andRespond(withSuccess("[{\"account_id\":1,\"personaname\":\"One\"},{\"account_id\":2},"
                        + "{\"account_id\":3,\"personaname\":\"   \"},{\"account_id\":1,\"personaname\":\"Dup\"}]",
                        MediaType.APPLICATION_JSON));

        Map<Long, String> names = client.getPlayerNames(List.of(1L, 2L, 3L, 4L));

        assertThat(names).containsOnly(Map.entry(1L, "One"));
    }

    @Test
    void playerNamesEmptyArrayGivesEmptyMap() {
        server.expect(once(), requestTo(STEAM_URL + "1"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(client.getPlayerNames(List.of(1L))).isEmpty();
    }

    @Test
    void playerNamesServerErrorThrowsDeadlockApiException() {
        server.expect(once(), requestTo(STEAM_URL + "1")).andRespond(withServerError());

        assertThatThrownBy(() -> client.getPlayerNames(List.of(1L)))
                .isInstanceOf(DeadlockApiException.class)
                .isNotInstanceOf(DeadlockApiUnavailableException.class);
    }

    @Test
    void playerNames503OnceThenSuccessRetries() {
        server.expect(once(), requestTo(STEAM_URL + "1000001"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "1"));
        server.expect(once(), requestTo(STEAM_URL + "1000001"))
                .andRespond(withSuccess(fixture("players-steam.json"), MediaType.APPLICATION_JSON));

        assertThat(client.getPlayerNames(List.of(1000001L))).containsEntry(1000001L, "FakeAlpha");
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
    }

    @Test
    void playerNames429ThrowsUnavailable() {
        server.expect(once(), requestTo(STEAM_URL + "1"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.getPlayerNames(List.of(1L)))
                .isInstanceOf(DeadlockApiUnavailableException.class);
    }

    @Test
    void playerNamesInvalidListsThrowWithNoRequest() {
        List<Long> tooMany = IntStream.rangeClosed(1, 1001).mapToLong(i -> (long) i).boxed().toList();

        assertThatThrownBy(() -> client.getPlayerNames(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.getPlayerNames(tooMany)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.getPlayerNames(List.of(1L, -5L))).isInstanceOf(IllegalArgumentException.class);
    }
}
