# Task 008: `AnalysisClient` for Claude Haiku 5.5

- **Branch**: `feature/008-analysis-client`
- **Implementer**: Claude Code
- **Related ADRs**: ADR-0003 (Claude Haiku 5.5), ADR-0011 (single-match analysis, structured output), ADR-0008 (English)

## Goal
Create `AnalysisClient`, the only component that talks to the Claude Messages API. It takes a compact `AnalysisInput` (the JSON contract of `docs/mvp-spec.md`), asks `claude-haiku-5-5` for a strict JSON coaching result, validates it, retries once, and otherwise returns a fallback outcome without AI text. Nothing calls it yet; Task 009 wires it into `/analyze`.

## Context (verified in the repository when this spec was written)
Do not repeat this discovery. If a fact below is false in your checkout, stop and ask.

- Plan entry: `docs/implementation-plan.md`, task 008, depends on 001, implementer Claude Code. Task 009 (`/analyze`) depends on 005, 007 and 008.
- **Task 006 is planned to run in parallel** in another worktree. It changes `CacheConfig`, `messages.properties`, `MessagesIntegrationTest` and adds profile classes. Do not touch any of those files.
- Stack rules (`AGENTS.md`): Spring `RestClient` for external HTTP, **no new dependency** (so no Anthropic Java SDK; adding it would need an ADR), DTO records with `@JsonIgnoreProperties(ignoreUnknown = true)` containing only used fields, constructor injection, `final` fields, no Lombok, never log API keys, secrets only from environment variables.
- Existing code to follow (do not change it):
  - `config/DeadlockApiConfig` + `config/DeadlockApiProperties`: pattern for a `@ConfigurationProperties` record, a public static `restClientBuilder(properties)` (so tests bind `MockRestServiceServer` to the same configuration) and a `RestClient` bean. JDK `HttpClient` with connect timeout and `JdkClientHttpRequestFactory` read timeout.
  - `clients/Sleeper` (functional interface, bean `sleeper` in `DeadlockApiConfig`, `Thread::sleep`): reuse it for the retry wait so tests do not sleep.
  - DTOs use `com.fasterxml.jackson.annotation.*` annotations (Jackson 3 databind is on the classpath through the RestClient starter, package `tools.jackson.databind`).
  - Tests: `DeadlockApiClientTest` (MockRestServiceServer + fixtures) and `DeadlockApiPropertiesTest` (`ApplicationContextRunner` + `ConfigDataApplicationContextInitializer`).
- Claude Messages API facts this spec relies on (Anthropic documentation, 2026-10):
  - `POST https://api.anthropic.com/v1/messages`, headers `x-api-key`, `anthropic-version: 2023-06-01`, `content-type: application/json`.
  - `claude-haiku-5-5` runs adaptive thinking by default; effort goes in `output_config.effort` (`low` here). Thinking tokens count toward `max_tokens`, and a response may begin with `thinking` blocks, so text must be read by block `type`, never by position.
  - Structured output: `output_config.format = {"type": "json_schema", "schema": {...}}`. No assistant prefill (400). No `temperature`/`top_p` (non-default values 400). No `fallbacks` parameter (not supported on Haiku 5.5).
  - `stop_reason` may be `end_turn`, `max_tokens` or `refusal`. Retryable statuses: 429, 500, 529 (`overloaded_error`), with an optional `retry-after` header in seconds. Non-retryable: 400, 401, 402, 403, 404, 413.

## Files to create or modify
| File | Change |
|---|---|
| `src/main/java/sntiago05/rejuv/domain/AnalysisInput.java` | **Create.** Record + nested records (see Interfaces). |
| `src/main/java/sntiago05/rejuv/domain/AnalysisResult.java` | **Create.** |
| `src/main/java/sntiago05/rejuv/domain/AnalysisOutcome.java` | **Create.** |
| `src/main/java/sntiago05/rejuv/config/AnthropicProperties.java` | **Create.** |
| `src/main/java/sntiago05/rejuv/config/AnthropicConfig.java` | **Create.** |
| `src/main/java/sntiago05/rejuv/clients/AnalysisClient.java` | **Create.** |
| `src/main/java/sntiago05/rejuv/clients/dto/MessagesRequest.java` | **Create.** Request DTO (nested records allowed). |
| `src/main/java/sntiago05/rejuv/clients/dto/MessagesResponse.java` | **Create.** Response DTO. |
| `src/main/resources/prompts/analysis-system.txt` | **Create.** System prompt (rules below). |
| `src/main/resources/application.yaml` | **Modify.** Append the `anthropic:` block. Change nothing else. |
| `.env.example` | **Modify.** Append `ANTHROPIC_API_KEY=` (empty). Keep existing lines. |
| `src/test/java/sntiago05/rejuv/clients/AnalysisClientTest.java` | **Create.** |
| `src/test/java/sntiago05/rejuv/config/AnthropicPropertiesTest.java` | **Create.** |
| `src/test/resources/fixtures/anthropic/*.json` | **Create.** Fixtures listed in Tests. |
| `docs/task-reports/008-analysis-client.md` | **Create.** Task report (format of the Task 005 report). |

No `pom.xml`, migration, `messages.properties`, `CacheConfig`, command or service change. If you think one is needed, stop and ask.

## Interfaces / signatures

### Domain records (`sntiago05.rejuv.domain`, new)
`AnalysisInput` serializes to the `docs/mvp-spec.md` input JSON (snake_case through `@JsonProperty`). Task 007/009 will build it; this task only defines and sends it.
```java
public record AnalysisInput(String hero, String rank, String result,           // result: "win" | "loss"
                            @JsonProperty("duration_min") double durationMin,
                            Map<String, MetricComparison> metrics,              // key = metric name, e.g. "souls_per_min"
                            List<ItemTiming> items,
                            String language) {                                  // always "en" in the MVP

    /** p25/p50/p75 are null when no benchmark exists for the metric. */
    public record MetricComparison(double value, Double p25, Double p50, Double p75) { }

    /** avgBoughtAtMin is null when no benchmark exists for the item. */
    public record ItemTiming(String name,
                             @JsonProperty("bought_at_min") double boughtAtMin,
                             @JsonProperty("avg_bought_at_min") Double avgBoughtAtMin) { }
}

public record AnalysisResult(String strength, String biggestLeak, String nextGameAction, List<String> keyNumbers) { }

/**
 * result is null when the AI failed and the caller must show the non-AI fallback.
 * inputTokens/outputTokens are summed over all attempts that returned a response (0 if none).
 */
public record AnalysisOutcome(AnalysisResult result, String model, int attempts, int inputTokens, int outputTokens) {
    public boolean isFallback() { return result == null; }
}
```
Null `p25/p50/p75/avgBoughtAtMin` must be omitted from the JSON (`@JsonInclude(NON_NULL)` on those records), not sent as `null`.

### `AnthropicProperties` (`sntiago05.rejuv.config`, new)
```java
@ConfigurationProperties("anthropic")
public record AnthropicProperties(String baseUrl, String apiKey, String model, String effort, int maxTokens,
                                  Duration connectTimeout, Duration readTimeout,
                                  Duration defaultRetryAfter, Duration maxRetryAfter) { }
```
`toString()` must not contain the API key (override it to mask the key).

### `application.yaml` (append)
```yaml
anthropic:
  base-url: https://api.anthropic.com
  api-key: ${ANTHROPIC_API_KEY:}
  model: claude-haiku-5-5
  effort: low
  max-tokens: 1500
  connect-timeout: 3s
  read-timeout: 30s
  default-retry-after: 1s
  max-retry-after: 10s
```

### `AnthropicConfig` (`sntiago05.rejuv.config`, new)
- `@EnableConfigurationProperties(AnthropicProperties.class)`.
- `public static RestClient.Builder restClientBuilder(AnthropicProperties properties)`: base URL, timeouts (same technique as `DeadlockApiConfig`), default headers `x-api-key` (from properties), `anthropic-version: 2023-06-01`, `content-type` and `accept` `application/json`.
- `@Bean RestClient anthropicRestClient(AnthropicProperties properties)`.
- The application must start with an empty API key (tests and local runs have none).

### `AnalysisClient` (`sntiago05.rejuv.clients`, `@Component`, new)
```java
public static final int MAX_ATTEMPTS = 2;

public AnalysisClient(RestClient anthropicRestClient, AnthropicProperties properties, Sleeper sleeper)

/** Never throws for API, network or model-output failures: returns a fallback outcome instead. */
public AnalysisOutcome analyze(AnalysisInput input);
```
Use `@Qualifier` (or parameter name matching) so the right `RestClient` bean is injected; there are now two. The system prompt is loaded once from `classpath:prompts/analysis-system.txt` (UTF-8) at construction; a missing file fails startup.

### Request body (one Messages call)
```json
{
  "model": "<properties.model>",
  "max_tokens": <properties.maxTokens>,
  "system": "<contents of analysis-system.txt>",
  "messages": [{"role": "user", "content": "<AnalysisInput serialized as JSON>"}],
  "output_config": {
    "effort": "<properties.effort>",
    "format": {"type": "json_schema", "schema": <RESULT_SCHEMA>}
  }
}
```
No `thinking`, `temperature`, `top_p`, `fallbacks` or beta header. `RESULT_SCHEMA`: object, `additionalProperties: false`, required `strength`, `biggest_leak`, `next_game_action` (strings) and `key_numbers` (array of strings).

### Response DTO (only used fields)
`MessagesResponse(String model, List<ContentBlock> content, @JsonProperty("stop_reason") String stopReason, Usage usage)`; `ContentBlock(String type, String text)`; `Usage(@JsonProperty("input_tokens") int inputTokens, @JsonProperty("output_tokens") int outputTokens)`.

## Behavior

### `analyze(input)`
1. If `apiKey` is blank: log `WARN` once per call ("Anthropic API key not configured"), return `AnalysisOutcome(null, model, 0, 0, 0)` without any HTTP call.
2. Up to `MAX_ATTEMPTS` (2) attempts. An attempt **succeeds** only when all hold:
   - HTTP 2xx and the body parses;
   - `stop_reason == "end_turn"`;
   - the concatenation of all `text` blocks (ignore `thinking` and other block types) parses as JSON into the four fields;
   - `strength`, `biggest_leak`, `next_game_action` are non-blank; `key_numbers` has 1 to 4 non-blank entries.
   On success return `AnalysisOutcome(result, response.model, attempts, summedInput, summedOutput)` with trimmed strings.
3. An attempt **fails retryably** on: 429, 500, 529, any other 5xx, timeout or I/O error, `stop_reason` `max_tokens`, text that is not valid JSON or fails the validation in step 2.
   - Before the second attempt after 429/529: if a `retry-after` header is present and greater than `maxRetryAfter`, do not retry (fallback now); otherwise wait `retry-after` (or `defaultRetryAfter` when absent) through `Sleeper`.
   - After other retryable failures: retry immediately (no wait).
4. An attempt **fails without retry** on: 400, 401, 402, 403, 404, 413, any other 4xx, and `stop_reason == "refusal"` (the same input would be refused again). Return the fallback immediately.
5. After the last failed attempt return the fallback outcome (`result == null`), with `attempts` = attempts made and token counts summed from every response that had a parsable `usage`.
6. Logging: one `INFO` line per `analyze` call with model, attempts, outcome (`ok` / `fallback`), the reason of the last failure if any (status, stop reason or `invalid_output`), input/output tokens and duration in ms. `WARN` per failed attempt with the same reason. Never log the API key, request headers, the system prompt or the full model output (a 200-character excerpt at `DEBUG` is allowed).
7. No caching: each call is a paid generation; Task 009 avoids repeats by storing results per `(account_id, match_id)`.

### System prompt (`prompts/analysis-system.txt`)
Plain English text, under 250 words, tuned later in Task 010. It must say:
- The model is a Deadlock coach writing for the player; the user message is a JSON summary of one match (`metrics` with p25/p50/p75 of the same hero and rank, `items` with average buy times).
- Use only numbers present in the input; never invent metrics, items or events.
- `biggest_leak`: a metric below p25, or clearly worse than p50 with a meaningful gap (for lower-is-better metrics such as deaths or timings, "worse" means higher); include the player's number and the benchmark.
- `strength` and `biggest_leak`: 1-2 sentences each; `next_game_action`: one concrete action for the next game; `key_numbers`: 3-4 short strings in the form `<metric>: <yours> / <median at your rank>`.
- Answer in the language given by `language`.

## Tests
JUnit 5, AssertJ, `MockRestServiceServer` bound to `AnthropicConfig.restClientBuilder(TEST_PROPERTIES)`, a recording fake `Sleeper`. No Spring context in `AnalysisClientTest`; no real network; fixtures are fake (no real key, no real player data).

### Fixtures (`src/test/resources/fixtures/anthropic/`)
`messages-success.json` (a `thinking` block with empty text, then a `text` block with valid result JSON, `stop_reason` `end_turn`, usage), `messages-invalid-json.json` (text block not JSON), `messages-missing-field.json` (JSON without `next_game_action`), `messages-max-tokens.json`, `messages-refusal.json` (`stop_reason` `refusal`, `stop_details` present), `error-overloaded.json` (529 body), `error-invalid-request.json` (400 body).

### `AnalysisClientTest`
1. `sendsExpectedRequest`: method POST, path `/v1/messages`, headers `x-api-key`, `anthropic-version: 2023-06-01`, JSON body with `model`, `max_tokens` 1500, `system` equal to the prompt file, one user message whose content parses back to the input (snake_case keys, null benchmarks omitted), `output_config.effort` `low`, `output_config.format.type` `json_schema`; no `thinking`, `temperature` or `fallbacks` keys.
2. `successReturnsParsedResultIgnoringThinkingBlocks`: all four fields, model from the response, `attempts` 1, token counts.
3. `invalidJsonThenSuccessRetriesOnce`: `attempts` 2, tokens summed over both responses, no sleep.
4. `missingFieldTwiceReturnsFallback`: `isFallback()`, `attempts` 2.
5. `keyNumbersOutOfRangeIsInvalid`: 0 entries and 5 entries each count as invalid output.
6. `maxTokensStopReasonIsRetried`.
7. `refusalReturnsFallbackWithoutRetry`: exactly one request.
8. `overloadedWithRetryAfterWaitsThenRetries`: 529 with `retry-after: 2`, then success → sleeper recorded 2 s.
9. `rateLimitWithoutRetryAfterUsesDefaultWait`: 429 without header → sleeper recorded 1 s.
10. `retryAfterAboveMaxDoesNotRetry`: 529 with `retry-after: 60` → fallback, one request, no sleep.
11. `serverErrorTwiceReturnsFallback`: two 500 responses.
12. `badRequestReturnsFallbackWithoutRetry`: 400 → one request; same for 401.
13. `ioErrorIsRetried` (e.g. `MockRestResponseCreators.withException(new SocketTimeoutException())`).
14. `blankApiKeyReturnsFallbackWithoutCall`: no request expected.
15. `inputSerializationMatchesMvpContract`: a fixed input serializes to the expected JSON (keys `hero`, `rank`, `result`, `duration_min`, `metrics.<name>.value/p25/p50/p75`, `items[].name/bought_at_min/avg_bought_at_min`, `language`).

### `AnthropicPropertiesTest`
Style of `DeadlockApiPropertiesTest`: binds the `application.yaml` defaults (with `ANTHROPIC_API_KEY` unset the key is empty), and `toString()` of properties built with key `sk-test` does not contain `sk-test`.

## Verification
1. `./mvnw clean verify` passes with Docker running (on Windows `.\mvnw.cmd clean verify`; see the `MAVEN_USER_HOME` note in `docs/PROJECT_CONTEXT.md` if needed). `RejuvApplicationTests` must still start with no API key. Report the real test count against the baseline of 135 (Task 022 report).
2. `git diff --check` and a review of the final diff: only the files in the table changed; no API key, token or real data; `.env` never read or printed.
3. Optional live check (costs a fraction of a cent, needs `ANTHROPIC_API_KEY` in the environment, owner approval first): call `analyze` once with a hand-built input and record model, attempts, tokens and whether the result validated. If not run, say so in the report.

## Documentation
- Task report `docs/task-reports/008-analysis-client.md`: status, summary, files, commands run with real results, deviations, known issues; include the open questions below if still open.
- `docs/PROJECT_CONTEXT.md`: **do not edit in this branch.** Task 006 runs in parallel; mark the context update as "pending integration" in the report and list the facts to add after both branches merge.
- No ADR. Do not edit `docs/mvp-spec.md` (already updated by the owner for this task), `docs/architecture.md`, ADRs, other specs or `docs/roadmap.md`.

## Out of scope
- `/analyze`, `MetricsService`, building `AnalysisInput` from match data (Tasks 007, 009).
- The non-AI fallback text and the embed (Task 009, via `messages.properties`).
- Persisting results or token usage (`match_analysis`, Task 009).
- Prompt tuning with real matches (Task 010), prompt caching, streaming, batches, usage limits.
- The Anthropic Java SDK or any new dependency; changes to `DeadlockApiConfig`, `Sleeper`, `CacheConfig`, `messages.properties`.

## Acceptance criteria
- [ ] `./mvnw clean verify` passes; test count and baseline difference are in the report
- [ ] One Messages API request per attempt with the exact body and headers above; model, effort and `max_tokens` come from `application.yaml`
- [ ] Text is read only from `text` blocks; the result is validated (four fields, 1-4 key numbers)
- [ ] At most 2 attempts; retry rules for 429/529 (`retry-after`), 5xx, I/O, `max_tokens` and invalid output; no retry for 4xx or refusal
- [ ] `analyze` never throws for API or model failures and returns a fallback outcome with attempts and summed tokens
- [ ] Blank API key → fallback without HTTP call; the application starts without a key
- [ ] API key never logged and masked in `toString()`; no secret committed
- [ ] `AnalysisInput` serializes to the `docs/mvp-spec.md` contract, with `avg_bought_at_min` (see Decisions)
- [ ] Only the files in the table changed; no new dependency
- [ ] Task report created with `docs/PROJECT_CONTEXT.md` update marked pending integration; final diff has no unrelated files

## Decisions and open questions
Decisions taken in this spec:
1. **Raw HTTP with `RestClient`, not the Anthropic Java SDK**, because `AGENTS.md` mandates `RestClient` and forbids new dependencies. Switching to the SDK later needs an ADR.
2. **Structured output (`output_config.format` with a JSON schema)** plus local validation and one retry, which implements ADR-0011's "fallback after two failures".
3. **Adaptive thinking left at the model default with effort `low`** (ADR-0003). Thinking is not disabled.
4. **Item benchmark field is `avg_bought_at_min`**: the only verified item benchmark is the `item-stats` average buy time (`avg_buy_time_s`, no percentiles; Task 004). `docs/mvp-spec.md` was updated to match.
5. **`AnalysisInput` lives in `domain/`** so Task 007 or 009 can build it without depending on `clients/`.

Open for the owner (not blocking this task):
1. `max_tokens` is 1500 (owner decision, raised from the original 600) because thinking tokens count toward it on Haiku 5.5. Worst case about 0.00075 USD of output per attempt. If live checks or Task 010 still show `max_tokens` stops, raise it in `application.yaml` (no code change).
2. Task 009 must map `AnalysisOutcome.isFallback()` to the "key numbers without AI text" reply and store `model`, `inputTokens`, `outputTokens` in `match_analysis`.
