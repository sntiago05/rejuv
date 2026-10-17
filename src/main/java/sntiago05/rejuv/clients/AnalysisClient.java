package sntiago05.rejuv.clients;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse;
import org.springframework.web.client.RestClientException;
import sntiago05.rejuv.clients.dto.MessagesRequest;
import sntiago05.rejuv.clients.dto.MessagesResponse;
import sntiago05.rejuv.config.AnthropicProperties;
import sntiago05.rejuv.domain.AnalysisInput;
import sntiago05.rejuv.domain.AnalysisOutcome;
import sntiago05.rejuv.domain.AnalysisResult;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gateway to the Anthropic Messages API for match analysis.
 * Uses structured JSON output with claude-haiku-5-5, validates the response, retries once,
 * and falls back gracefully on failure without throwing.
 */
@Component
public class AnalysisClient {

    public static final int MAX_ATTEMPTS = 2;

    private static final Logger log = LoggerFactory.getLogger(AnalysisClient.class);
    private static final String MESSAGES_PATH = "/v1/messages";

    private final RestClient restClient;
    private final AnthropicProperties properties;
    private final Sleeper sleeper;
    private final String systemPrompt;
    private final ObjectMapper objectMapper;

    public AnalysisClient(@Qualifier("anthropicRestClient") RestClient anthropicRestClient,
                          AnthropicProperties properties,
                          Sleeper sleeper) {
        this.restClient = anthropicRestClient;
        this.properties = properties;
        this.sleeper = sleeper;
        this.systemPrompt = loadSystemPrompt();
        this.objectMapper = new JsonMapper();
    }

    /**
     * Sends the match analysis input to Claude Messages API.
     * Never throws for API, network or model-output failures: returns a fallback outcome instead.
     */
    public AnalysisOutcome analyze(AnalysisInput input) {
        long startTime = System.currentTimeMillis();
        String model = properties.model();

        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            log.warn("Anthropic API key not configured");
            return new AnalysisOutcome(null, model, 0, 0, 0);
        }

        String inputJson;
        try {
            inputJson = objectMapper.writeValueAsString(input);
        } catch (Exception e) {
            log.warn("Failed to serialize AnalysisInput to JSON", e);
            return new AnalysisOutcome(null, model, 0, 0, 0);
        }

        MessagesRequest request = new MessagesRequest(
                model,
                properties.maxTokens(),
                systemPrompt,
                List.of(new MessagesRequest.Message("user", inputJson)),
                new MessagesRequest.OutputConfig(
                        properties.effort(),
                        new MessagesRequest.Format("json_schema", MessagesRequest.RESULT_SCHEMA)
                )
        );

        int attempts = 0;
        int totalInputTokens = 0;
        int totalOutputTokens = 0;
        String lastFailureReason = null;
        String responseModel = model;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            attempts = attempt;
            AttemptResult attemptResult = executeAttempt(request);

            if (attemptResult.responseModel() != null) {
                responseModel = attemptResult.responseModel();
            }
            totalInputTokens += attemptResult.inputTokens();
            totalOutputTokens += attemptResult.outputTokens();

            if (attemptResult.isSuccess()) {
                long durationMs = System.currentTimeMillis() - startTime;
                log.info("Analysis completed: model={}, attempts={}, outcome=ok, reason=none, input_tokens={}, output_tokens={}, duration_ms={}",
                        responseModel, attempts, totalInputTokens, totalOutputTokens, durationMs);
                return new AnalysisOutcome(attemptResult.result(), responseModel, attempts, totalInputTokens, totalOutputTokens);
            }

            lastFailureReason = attemptResult.failureReason();
            log.warn("Anthropic Messages API attempt {}/{} failed: reason={}", attempt, MAX_ATTEMPTS, lastFailureReason);

            if (!attemptResult.isRetryable() || attempt == MAX_ATTEMPTS) {
                break;
            }

            if (attemptResult.isRateLimitedOrOverloaded()) {
                Duration wait = attemptResult.retryAfter() != null ? attemptResult.retryAfter() : properties.defaultRetryAfter();
                if (wait.compareTo(properties.maxRetryAfter()) > 0) {
                    log.warn("Anthropic retry-after {} s exceeds max-retry-after {} s, falling back immediately",
                            wait.toSeconds(), properties.maxRetryAfter().toSeconds());
                    break;
                }
                try {
                    sleeper.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Interrupted while waiting to retry Anthropic call");
                    break;
                }
            }
        }

        long durationMs = System.currentTimeMillis() - startTime;
        log.info("Analysis completed: model={}, attempts={}, outcome=fallback, reason={}, input_tokens={}, output_tokens={}, duration_ms={}",
                responseModel, attempts, lastFailureReason, totalInputTokens, totalOutputTokens, durationMs);
        return new AnalysisOutcome(null, responseModel, attempts, totalInputTokens, totalOutputTokens);
    }

    private AttemptResult executeAttempt(MessagesRequest request) {
        try {
            return restClient.post()
                    .uri(MESSAGES_PATH)
                    .body(request)
                    .exchange((clientRequest, clientResponse) -> {
                        int status = clientResponse.getStatusCode().value();
                        String retryAfterHeader = clientResponse.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
                        Duration retryAfter = parseRetryAfter(retryAfterHeader);

                        if (clientResponse.getStatusCode().is2xxSuccessful()) {
                            return handleSuccessResponse(clientResponse);
                        }
                        return handleErrorResponse(clientResponse, status, retryAfter);
                    });
        } catch (ResourceAccessException e) {
            log.warn("Anthropic Messages API request failed with I/O error or timeout: {}", e.getMessage());
            return AttemptResult.failure("io_error", null, 0, 0, null, true, false);
        } catch (RestClientException e) {
            if (e.getCause() instanceof IOException) {
                log.warn("Anthropic Messages API request failed with I/O error: {}", e.getMessage());
                return AttemptResult.failure("io_error", null, 0, 0, null, true, false);
            }
            log.warn("Anthropic Messages API request failed with client exception: {}", e.getMessage());
            return AttemptResult.failure("client_error", null, 0, 0, null, true, false);
        }
    }

    private AttemptResult handleSuccessResponse(ConvertibleClientHttpResponse clientResponse) {
        MessagesResponse response;
        try {
            response = clientResponse.bodyTo(MessagesResponse.class);
        } catch (Exception e) {
            log.warn("Failed to parse Anthropic MessagesResponse body: {}", e.getMessage());
            return AttemptResult.failure("invalid_output", null, 0, 0, null, true, false);
        }
        if (response == null) {
            return AttemptResult.failure("invalid_output", null, 0, 0, null, true, false);
        }

        int inputTokens = response.usage() != null ? response.usage().inputTokens() : 0;
        int outputTokens = response.usage() != null ? response.usage().outputTokens() : 0;
        String responseModel = response.model() != null ? response.model() : properties.model();

        String stopReason = response.stopReason();
        if ("refusal".equalsIgnoreCase(stopReason)) {
            return AttemptResult.failure("refusal", responseModel, inputTokens, outputTokens, null, false, false);
        }
        if ("max_tokens".equalsIgnoreCase(stopReason)) {
            return AttemptResult.failure("max_tokens", responseModel, inputTokens, outputTokens, null, true, false);
        }
        if (!"end_turn".equalsIgnoreCase(stopReason)) {
            return AttemptResult.failure(stopReason != null ? stopReason : "invalid_stop_reason",
                    responseModel, inputTokens, outputTokens, null, true, false);
        }

        String combinedText = response.content() == null ? "" : response.content().stream()
                .filter(b -> "text".equals(b.type()) && b.text() != null)
                .map(MessagesResponse.ContentBlock::text)
                .collect(Collectors.joining());

        if (log.isDebugEnabled()) {
            String excerpt = combinedText.substring(0, Math.min(combinedText.length(), 200));
            log.debug("Anthropic response text excerpt: {}", excerpt);
        }

        AnalysisResult result;
        try {
            result = objectMapper.readValue(combinedText, AnalysisResult.class);
        } catch (Exception e) {
            log.warn("Failed to parse analysis result JSON: {}", e.getMessage());
            return AttemptResult.failure("invalid_output", responseModel, inputTokens, outputTokens, null, true, false);
        }

        if (result == null
                || result.strength() == null || result.strength().isBlank()
                || result.biggestLeak() == null || result.biggestLeak().isBlank()
                || result.nextGameAction() == null || result.nextGameAction().isBlank()
                || result.keyNumbers() == null
                || result.keyNumbers().size() < 1 || result.keyNumbers().size() > 4
                || result.keyNumbers().stream().anyMatch(k -> k == null || k.isBlank())) {
            return AttemptResult.failure("invalid_output", responseModel, inputTokens, outputTokens, null, true, false);
        }

        AnalysisResult trimmedResult = new AnalysisResult(
                result.strength().trim(),
                result.biggestLeak().trim(),
                result.nextGameAction().trim(),
                result.keyNumbers().stream().map(String::trim).toList()
        );

        return AttemptResult.success(trimmedResult, responseModel, inputTokens, outputTokens);
    }

    private AttemptResult handleErrorResponse(ConvertibleClientHttpResponse clientResponse,
                                              int status,
                                              Duration retryAfter) {
        int inputTokens = 0;
        int outputTokens = 0;
        String responseModel = null;
        try {
            MessagesResponse errResponse = clientResponse.bodyTo(MessagesResponse.class);
            if (errResponse != null) {
                if (errResponse.usage() != null) {
                    inputTokens = errResponse.usage().inputTokens();
                    outputTokens = errResponse.usage().outputTokens();
                }
                responseModel = errResponse.model();
            }
        } catch (Exception ignored) {
            // Error response body is not a MessagesResponse
        }

        if (status == 429 || status == 529) {
            return AttemptResult.failure("HTTP " + status, responseModel, inputTokens, outputTokens, retryAfter, true, true);
        }
        if (status >= 500 && status < 600) {
            return AttemptResult.failure("HTTP " + status, responseModel, inputTokens, outputTokens, null, true, false);
        }
        return AttemptResult.failure("HTTP " + status, responseModel, inputTokens, outputTokens, null, false, false);
    }

    private static Duration parseRetryAfter(String header) {
        if (header == null) {
            return null;
        }
        try {
            long seconds = Long.parseLong(header.trim());
            return seconds >= 0 ? Duration.ofSeconds(seconds) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String loadSystemPrompt() {
        try (var in = AnalysisClient.class.getResourceAsStream("/prompts/analysis-system.txt")) {
            if (in == null) {
                throw new IllegalStateException("Missing required system prompt at classpath:/prompts/analysis-system.txt");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load system prompt from classpath:/prompts/analysis-system.txt", e);
        }
    }

    private record AttemptResult(
            boolean isSuccess,
            boolean isRetryable,
            boolean isRateLimitedOrOverloaded,
            AnalysisResult result,
            String failureReason,
            String responseModel,
            int inputTokens,
            int outputTokens,
            Duration retryAfter
    ) {
        static AttemptResult success(AnalysisResult result, String responseModel, int inputTokens, int outputTokens) {
            return new AttemptResult(true, false, false, result, null, responseModel, inputTokens, outputTokens, null);
        }

        static AttemptResult failure(String failureReason, String responseModel, int inputTokens, int outputTokens,
                                     Duration retryAfter, boolean isRetryable, boolean isRateLimitedOrOverloaded) {
            return new AttemptResult(false, isRetryable, isRateLimitedOrOverloaded, null, failureReason, responseModel,
                    inputTokens, outputTokens, retryAfter);
        }
    }
}
