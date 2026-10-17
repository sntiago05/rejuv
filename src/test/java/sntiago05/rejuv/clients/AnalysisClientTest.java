package sntiago05.rejuv.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.twice;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import sntiago05.rejuv.config.AnthropicConfig;
import sntiago05.rejuv.config.AnthropicProperties;
import sntiago05.rejuv.domain.AnalysisInput;
import sntiago05.rejuv.domain.AnalysisOutcome;
import sntiago05.rejuv.domain.AnalysisResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class AnalysisClientTest {

    static final String BASE_URL = "https://api.anthropic.test";
    static final String API_KEY = "sk-test-key-12345";
    static final AnthropicProperties PROPERTIES = new AnthropicProperties(
            BASE_URL,
            API_KEY,
            "claude-haiku-5-5",
            "low",
            1500,
            Duration.ofSeconds(3),
            Duration.ofSeconds(30),
            Duration.ofSeconds(1),
            Duration.ofSeconds(10)
    );

    private final List<Duration> sleeps = new ArrayList<>();
    private MockRestServiceServer server;
    private AnalysisClient client;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = AnthropicConfig.restClientBuilder(PROPERTIES);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AnalysisClient(builder.build(), PROPERTIES, sleeps::add);
        objectMapper = new JsonMapper();
    }

    @AfterEach
    void verifyServer() {
        server.verify();
    }

    static ClassPathResource fixture(String name) {
        return new ClassPathResource("fixtures/anthropic/" + name);
    }

    static AnalysisInput sampleInput() {
        return new AnalysisInput(
                "Infernus",
                "Archon 3",
                "win",
                32.5,
                Map.of(
                        "souls_per_min", new AnalysisInput.MetricComparison(750.0, 600.0, 680.0, 780.0),
                        "deaths", new AnalysisInput.MetricComparison(9.0, 4.0, 5.0, 7.0),
                        "damage_per_min", new AnalysisInput.MetricComparison(1200.0, null, 1150.0, null)
                ),
                List.of(
                        new AnalysisInput.ItemTiming("Extra Health", 2.5, 3.0),
                        new AnalysisInput.ItemTiming("Toxic Bullets", 14.2, null)
                ),
                "en"
        );
    }

    @Test
    void sendsExpectedRequest() throws Exception {
        String systemPrompt = new String(
                new ClassPathResource("prompts/analysis-system.txt").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8
        );

        server.expect(once(), requestTo(BASE_URL + "/v1/messages"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-api-key", API_KEY))
                .andExpect(header("anthropic-version", "2023-06-01"))
                .andExpect(jsonPath("$.model").value("claude-haiku-5-5"))
                .andExpect(jsonPath("$.max_tokens").value(1500))
                .andExpect(jsonPath("$.system").value(systemPrompt))
                .andExpect(jsonPath("$.output_config.effort").value("low"))
                .andExpect(jsonPath("$.output_config.format.type").value("json_schema"))
                .andExpect(jsonPath("$.output_config.format.schema.type").value("object"))
                .andExpect(jsonPath("$.thinking").doesNotExist())
                .andExpect(jsonPath("$.temperature").doesNotExist())
                .andExpect(jsonPath("$.fallbacks").doesNotExist())
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(request -> {
                    MockClientHttpRequest mockRequest = (MockClientHttpRequest) request;
                    String body = mockRequest.getBodyAsString();
                    JsonNode root = objectMapper.readTree(body);
                    String userContent = root.path("messages").get(0).path("content").asText();
                    AnalysisInput parsedInput = objectMapper.readValue(userContent, AnalysisInput.class);
                    assertThat(parsedInput.hero()).isEqualTo("Infernus");
                    assertThat(parsedInput.rank()).isEqualTo("Archon 3");
                    assertThat(parsedInput.result()).isEqualTo("win");
                    assertThat(parsedInput.durationMin()).isEqualTo(32.5);
                    assertThat(parsedInput.language()).isEqualTo("en");
                    assertThat(parsedInput.metrics().get("souls_per_min").p50()).isEqualTo(680.0);
                    assertThat(parsedInput.items().get(0).name()).isEqualTo("Extra Health");

                    JsonNode contentTree = objectMapper.readTree(userContent);
                    JsonNode damageMetric = contentTree.path("metrics").path("damage_per_min");
                    assertThat(damageMetric.has("p25")).isFalse();
                    assertThat(damageMetric.has("p75")).isFalse();
                    assertThat(damageMetric.has("p50")).isTrue();

                    JsonNode toxicItem = contentTree.path("items").get(1);
                    assertThat(toxicItem.has("avg_bought_at_min")).isFalse();
                    assertThat(toxicItem.has("bought_at_min")).isTrue();
                })
                .andRespond(withSuccess(fixture("messages-success.json"), MediaType.APPLICATION_JSON));

        AnalysisOutcome outcome = client.analyze(sampleInput());
        assertThat(outcome.isFallback()).isFalse();
    }

    @Test
    void successReturnsParsedResultIgnoringThinkingBlocks() {
        server.expect(once(), requestTo(BASE_URL + "/v1/messages"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(fixture("messages-success.json"), MediaType.APPLICATION_JSON));

        AnalysisOutcome outcome = client.analyze(sampleInput());

        assertThat(outcome.isFallback()).isFalse();
        assertThat(outcome.model()).isEqualTo("claude-haiku-5-5-20251001");
        assertThat(outcome.attempts()).isEqualTo(1);
        assertThat(outcome.inputTokens()).isEqualTo(450);
        assertThat(outcome.outputTokens()).isEqualTo(120);

        AnalysisResult result = outcome.result();
        assertThat(result).isNotNull();
        assertThat(result.strength()).isEqualTo("Excellent souls per minute through efficient lane farming.");
        assertThat(result.biggestLeak()).isEqualTo("Deaths were significantly higher than benchmark at 9 vs 5.");
        assertThat(result.nextGameAction()).isEqualTo("Focus on positioning behind cover during mid-game teamfights.");
        assertThat(result.keyNumbers()).containsExactly(
                "souls_per_min: 750 / 680",
                "deaths: 9 / 5",
                "damage_per_min: 1200 / 1150"
        );
    }

    @Test
    void invalidJsonThenSuccessRetriesOnce() {
        server.expect(once(), requestTo(BASE_URL + "/v1/messages"))
                .andRespond(withSuccess(fixture("messages-invalid-json.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE_URL + "/v1/messages"))
                .andRespond(withSuccess(fixture("messages-success.json"), MediaType.APPLICATION_JSON));

        AnalysisOutcome outcome = client.analyze(sampleInput());

        assertThat(outcome.isFallback()).isFalse();
        assertThat(outcome.attempts()).isEqualTo(2);
        assertThat(outcome.inputTokens()).isEqualTo(400 + 450);
        assertThat(outcome.outputTokens()).isEqualTo(50 + 120);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void missingFieldTwiceReturnsFallback() {
        server.expect(twice(), requestTo(BASE_URL + "/v1/messages"))
                .andRespond(withSuccess(fixture("messages-missing-field.json"), MediaType.APPLICATION_JSON));

        AnalysisOutcome outcome = client.analyze(sampleInput());

        assertThat(outcome.isFallback()).isTrue();
        assertThat(outcome.result()).isNull();
        assertThat(outcome.attempts()).isEqualTo(2);
        assertThat(outcome.inputTokens()).isEqualTo(420 * 2);
        assertThat(outcome.outputTokens()).isEqualTo(80 * 2);
    }

    @Test
    void keyNumbersOutOfRangeIsInvalid() {
        String zeroKeyNumbers = """
                {
                  "id": "msg_01",
                  "type": "message",
                  "role": "assistant",
                  "model": "claude-haiku-5-5",
                  "content": [{"type": "text", "text": "{\\"strength\\": \\"ok\\", \\"biggest_leak\\": \\"leak\\", \\"next_game_action\\": \\"action\\", \\"key_numbers\\": []}"}],
                  "stop_reason": "end_turn",
                  "usage": {"input_tokens": 100, "output_tokens": 50}
                }
                """;
        String fiveKeyNumbers = """
                {
                  "id": "msg_02",
                  "type": "message",
                  "role": "assistant",
                  "model": "claude-haiku-5-5",
                  "content": [{"type": "text", "text": "{\\"strength\\": \\"ok\\", \\"biggest_leak\\": \\"leak\\", \\"next_game_action\\": \\"action\\", \\"key_numbers\\": [\\"1\\", \\"2\\", \\"3\\", \\"4\\", \\"5\\"]}"}],
                  "stop_reason": "end_turn",
                  "usage": {"input_tokens": 100, "output_tokens": 50}
                }
                """;

        server.expect(once(), requestTo(BASE_URL + "/v1/messages"))
                .andRespond(withSuccess(zeroKeyNumbers, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE_URL + "/v1/messages"))
                .andRespond(withSuccess(fixture("messages-success.json"), MediaType.APPLICATION_JSON));

        AnalysisOutcome outcomeZero = client.analyze(sampleInput());
        assertThat(outcomeZero.isFallback()).isFalse();
        assertThat(outcomeZero.attempts()).isEqualTo(2);

        server.reset();
        server.expect(once(), requestTo(BASE_URL + "/v1/messages"))
                .andRespond(withSuccess(fiveKeyNumbers, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE_URL + "/v1/messages"))
                .andRespond(withSuccess(fixture("messages-success.json"), MediaType.APPLICATION_JSON));

        AnalysisOutcome outcomeFive = client.analyze(sampleInput());
        assertThat(outcomeFive.isFallback()).isFalse();
        assertThat(outcomeFive.attempts()).isEqualTo(2);
    }

    @Test
    void maxTokensStopReasonIsRetried() {
        server.expect(once(), requestTo(BASE_URL + "/v1/messages"))
                .andRespond(withSuccess(fixture("messages-max-tokens.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE_URL + "/v1/messages"))
                .andRespond(withSuccess(fixture("messages-success.json"), MediaType.APPLICATION_JSON));

        AnalysisOutcome outcome = client.analyze(sampleInput());

        assertThat(outcome.isFallback()).isFalse();
        assertThat(outcome.attempts()).isEqualTo(2);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void refusalReturnsFallbackWithoutRetry() {
        server.expect(once(), requestTo(BASE_URL + "/v1/messages"))
                .andRespond(withSuccess(fixture("messages-refusal.json"), MediaType.APPLICATION_JSON));

        AnalysisOutcome outcome = client.analyze(sampleInput());

        assertThat(outcome.isFallback()).isTrue();
        assertThat(outcome.attempts()).isEqualTo(1);
        assertThat(outcome.inputTokens()).isEqualTo(400);
        assertThat(outcome.outputTokens()).isEqualTo(20);
    }

    @Test
    void overloadedWithRetryAfterWaitsThenRetries() {
        server.expect(once(), requestTo(BASE_URL + "/v1/messages"))
                .andRespond(withStatus(HttpStatusCode.valueOf(529))
                        .header(HttpHeaders.RETRY_AFTER, "2")
                        .body(fixture("error-overloaded.json"))
                        .contentType(MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE_URL + "/v1/messages"))
                .andRespond(withSuccess(fixture("messages-success.json"), MediaType.APPLICATION_JSON));

        AnalysisOutcome outcome = client.analyze(sampleInput());

        assertThat(outcome.isFallback()).isFalse();
        assertThat(outcome.attempts()).isEqualTo(2);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(2));
    }

    @Test
    void rateLimitWithoutRetryAfterUsesDefaultWait() {
        server.expect(once(), requestTo(BASE_URL + "/v1/messages"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .body("{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"Rate limit exceeded\"}}")
                        .contentType(MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE_URL + "/v1/messages"))
                .andRespond(withSuccess(fixture("messages-success.json"), MediaType.APPLICATION_JSON));

        AnalysisOutcome outcome = client.analyze(sampleInput());

        assertThat(outcome.isFallback()).isFalse();
        assertThat(outcome.attempts()).isEqualTo(2);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
    }

    @Test
    void retryAfterAboveMaxDoesNotRetry() {
        server.expect(once(), requestTo(BASE_URL + "/v1/messages"))
                .andRespond(withStatus(HttpStatusCode.valueOf(529))
                        .header(HttpHeaders.RETRY_AFTER, "60")
                        .body(fixture("error-overloaded.json"))
                        .contentType(MediaType.APPLICATION_JSON));

        AnalysisOutcome outcome = client.analyze(sampleInput());

        assertThat(outcome.isFallback()).isTrue();
        assertThat(outcome.attempts()).isEqualTo(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void serverErrorTwiceReturnsFallback() {
        server.expect(twice(), requestTo(BASE_URL + "/v1/messages"))
                .andRespond(withServerError());

        AnalysisOutcome outcome = client.analyze(sampleInput());

        assertThat(outcome.isFallback()).isTrue();
        assertThat(outcome.attempts()).isEqualTo(2);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void badRequestReturnsFallbackWithoutRetry() {
        server.expect(once(), requestTo(BASE_URL + "/v1/messages"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .body(fixture("error-invalid-request.json"))
                        .contentType(MediaType.APPLICATION_JSON));

        AnalysisOutcome outcome = client.analyze(sampleInput());

        assertThat(outcome.isFallback()).isTrue();
        assertThat(outcome.attempts()).isEqualTo(1);

        server.reset();
        server.expect(once(), requestTo(BASE_URL + "/v1/messages"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .body("{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"Invalid key\"}}")
                        .contentType(MediaType.APPLICATION_JSON));

        AnalysisOutcome outcome401 = client.analyze(sampleInput());

        assertThat(outcome401.isFallback()).isTrue();
        assertThat(outcome401.attempts()).isEqualTo(1);
    }

    @Test
    void ioErrorIsRetried() {
        server.expect(once(), requestTo(BASE_URL + "/v1/messages"))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));
        server.expect(once(), requestTo(BASE_URL + "/v1/messages"))
                .andRespond(withSuccess(fixture("messages-success.json"), MediaType.APPLICATION_JSON));

        AnalysisOutcome outcome = client.analyze(sampleInput());

        assertThat(outcome.isFallback()).isFalse();
        assertThat(outcome.attempts()).isEqualTo(2);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void blankApiKeyReturnsFallbackWithoutCall() {
        AnthropicProperties blankProperties = new AnthropicProperties(
                BASE_URL,
                "",
                "claude-haiku-5-5",
                "low",
                1500,
                Duration.ofSeconds(3),
                Duration.ofSeconds(30),
                Duration.ofSeconds(1),
                Duration.ofSeconds(10)
        );
        AnalysisClient blankClient = new AnalysisClient(
                AnthropicConfig.restClientBuilder(blankProperties).build(),
                blankProperties,
                sleeps::add
        );

        AnalysisOutcome outcome = blankClient.analyze(sampleInput());

        assertThat(outcome.isFallback()).isTrue();
        assertThat(outcome.attempts()).isEqualTo(0);
        assertThat(outcome.inputTokens()).isEqualTo(0);
        assertThat(outcome.outputTokens()).isEqualTo(0);
        assertThat(outcome.model()).isEqualTo("claude-haiku-5-5");
    }

    @Test
    void inputSerializationMatchesMvpContract() throws Exception {
        AnalysisInput input = new AnalysisInput(
                "Infernus",
                "Archon 3",
                "win",
                32.5,
                Map.of(
                        "souls_per_min", new AnalysisInput.MetricComparison(750.0, 600.0, 680.0, 780.0),
                        "damage_per_min", new AnalysisInput.MetricComparison(1200.0, null, 1150.0, null)
                ),
                List.of(
                        new AnalysisInput.ItemTiming("Extra Health", 2.5, 3.0),
                        new AnalysisInput.ItemTiming("Toxic Bullets", 14.2, null)
                ),
                "en"
        );

        String json = objectMapper.writeValueAsString(input);
        JsonNode root = objectMapper.readTree(json);

        assertThat(root.path("hero").asText()).isEqualTo("Infernus");
        assertThat(root.path("rank").asText()).isEqualTo("Archon 3");
        assertThat(root.path("result").asText()).isEqualTo("win");
        assertThat(root.path("duration_min").asDouble()).isEqualTo(32.5);
        assertThat(root.path("language").asText()).isEqualTo("en");

        JsonNode soulsMetric = root.path("metrics").path("souls_per_min");
        assertThat(soulsMetric.path("value").asDouble()).isEqualTo(750.0);
        assertThat(soulsMetric.path("p25").asDouble()).isEqualTo(600.0);
        assertThat(soulsMetric.path("p50").asDouble()).isEqualTo(680.0);
        assertThat(soulsMetric.path("p75").asDouble()).isEqualTo(780.0);

        JsonNode damageMetric = root.path("metrics").path("damage_per_min");
        assertThat(damageMetric.path("value").asDouble()).isEqualTo(1200.0);
        assertThat(damageMetric.path("p50").asDouble()).isEqualTo(1150.0);
        assertThat(damageMetric.has("p25")).isFalse();
        assertThat(damageMetric.has("p75")).isFalse();

        JsonNode items = root.path("items");
        assertThat(items.isArray()).isTrue();
        assertThat(items.size()).isEqualTo(2);

        JsonNode item0 = items.get(0);
        assertThat(item0.path("name").asText()).isEqualTo("Extra Health");
        assertThat(item0.path("bought_at_min").asDouble()).isEqualTo(2.5);
        assertThat(item0.path("avg_bought_at_min").asDouble()).isEqualTo(3.0);

        JsonNode item1 = items.get(1);
        assertThat(item1.path("name").asText()).isEqualTo("Toxic Bullets");
        assertThat(item1.path("bought_at_min").asDouble()).isEqualTo(14.2);
        assertThat(item1.has("avg_bought_at_min")).isFalse();
    }
}
