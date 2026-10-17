# Task 008: `AnalysisClient` for Claude Haiku 5.5

## Status

Completed and verified. `.\mvnw.cmd clean verify` passes with 152 tests (135 baseline + 17 new). `PROJECT_CONTEXT.md` update marked pending integration because parallel tasks (Task 006) are active.

## Summary

- Created domain records:
  - `domain/AnalysisInput`: serializes to the `docs/mvp-spec.md` contract with snake_case properties (`duration_min`, `bought_at_min`, `avg_bought_at_min`) and omits null benchmarks (`@JsonInclude(NON_NULL)`).
  - `domain/AnalysisResult`: structured AI output (`strength`, `biggest_leak`, `next_game_action`, `key_numbers`).
  - `domain/AnalysisOutcome`: result or non-AI fallback (`isFallback()`), model, attempts, summed input and output tokens.
- Created configuration:
  - `config/AnthropicProperties`: `@ConfigurationProperties("anthropic")` record covering `base-url`, `api-key`, `model`, `effort`, `max-tokens`, timeouts and retry-after settings. Masks `apiKey` in `toString()`.
  - `config/AnthropicConfig`: exposes static `restClientBuilder` (with `x-api-key`, `anthropic-version: 2023-06-01`, and `application/json` headers) and bean `anthropicRestClient`.
- Created request/response DTOs:
  - `clients/dto/MessagesRequest`: request body with `model`, `max_tokens`, `system`, user message content JSON, and `output_config` with effort and strict JSON schema (`RESULT_SCHEMA`).
  - `clients/dto/MessagesResponse`: parses content blocks (`ContentBlock`), `stop_reason`, and token usage (`Usage`), ignoring unknown fields.
- Created system prompt:
  - `prompts/analysis-system.txt`: concise coaching system prompt (~155 words, under 250-word cap) enforcing grounding on provided metrics/benchmarks, single biggest leak diagnosis, concrete next action, 3-4 key numbers, and requested language. Loaded at `AnalysisClient` construction from classpath.
- Created client:
  - `clients/AnalysisClient`: executes up to 2 attempts against `/v1/messages`. Never throws: returns a fallback `AnalysisOutcome` on failure.
  - Validates that HTTP is 2xx, `stop_reason == "end_turn"`, all 4 fields are non-blank, and `key_numbers` has 1-4 non-blank entries.
  - Retry logic: retries once on 429/529 (waiting `retry-after` header if <= 10s, or default 1s; falls back immediately if > 10s), 5xx, I/O errors, `max_tokens`, and invalid JSON/output. Fails without retry on 4xx (400, 401, etc.) and `refusal`.
  - Blank API key returns fallback outcome immediately with 0 attempts and 0 tokens.
  - Logging: one INFO line per `analyze` call, WARN per failed attempt; API key, headers, and full model output are never logged.
- Modified config and environment:
  - `application.yaml`: appended `anthropic` configuration block with defaults.
  - `.env.example`: appended `ANTHROPIC_API_KEY=`.

## Important Files

- `src/main/java/sntiago05/rejuv/domain/AnalysisInput.java`
- `src/main/java/sntiago05/rejuv/domain/AnalysisResult.java`
- `src/main/java/sntiago05/rejuv/domain/AnalysisOutcome.java`
- `src/main/java/sntiago05/rejuv/config/AnthropicProperties.java`
- `src/main/java/sntiago05/rejuv/config/AnthropicConfig.java`
- `src/main/java/sntiago05/rejuv/clients/AnalysisClient.java`
- `src/main/java/sntiago05/rejuv/clients/dto/MessagesRequest.java`
- `src/main/java/sntiago05/rejuv/clients/dto/MessagesResponse.java`
- `src/main/resources/prompts/analysis-system.txt`
- `src/main/resources/application.yaml`
- `.env.example`
- `src/test/java/sntiago05/rejuv/clients/AnalysisClientTest.java` (15 tests)
- `src/test/java/sntiago05/rejuv/config/AnthropicPropertiesTest.java` (2 tests)
- `src/test/resources/fixtures/anthropic/` (7 JSON fixtures)

## Decisions

- **Raw HTTP via Spring `RestClient`**: no Anthropic Java SDK added, preserving the zero-new-dependency rule from `AGENTS.md`.
- **Structured output**: JSON schema defined in `MessagesRequest.RESULT_SCHEMA` enforces the required fields with `additionalProperties: false`.
- **Adaptive thinking with low effort**: `output_config.effort = "low"` without disabling thinking; client ignores non-text blocks (such as `thinking`) and reads text blocks by type.
- **Fail-safe outcome**: `analyze` catches all API, network, deserialization, and validation errors and returns `AnalysisOutcome(null, ...)` with `isFallback() == true`.
- **Masking API key**: `AnthropicProperties.toString()` masks the key with `****` or empty string to ensure keys never leak in logs or dumps.
- **Spring status code compatibility**: custom status 529 (`overloaded_error`) is handled using `HttpStatusCode.valueOf(529)` since `HttpStatus` is an enum for standard codes.

## Verification

- Baseline before changes: `.\mvnw.cmd verify` passed with 135 tests, 0 failures, 0 errors, 0 skipped.
- Narrow test runs:
  - `.\mvnw.cmd test -Dtest=AnthropicPropertiesTest`: 2 tests, 0 failures.
  - `.\mvnw.cmd test -Dtest=AnalysisClientTest`: 15 tests, 0 failures.
- Final full build: `.\mvnw.cmd clean verify` passed with **152 tests** (135 baseline + 17 new tests), 0 failures, 0 errors, 0 skipped.
  - `RejuvApplicationTests` verified context starts cleanly with no API key set.
- `git diff --check`: clean (no whitespace issues or trailing blank lines).
- Secret check: verified diff contains no API keys, tokens, or real user/match data.
- Live check: not run (requires live `ANTHROPIC_API_KEY` and owner approval; mock tests comprehensively cover all status codes and edge cases).

## Acceptance criteria

| Criterion | Evidence |
|---|---|
| `./mvnw clean verify` passes; test count and baseline difference are in the report | 152 tests (+17 new), see Verification |
| One Messages API request per attempt with exact body and headers; model, effort, `max_tokens` from `application.yaml` | `sendsExpectedRequest` |
| Text is read only from `text` blocks; result validated (4 fields, 1-4 key numbers) | `successReturnsParsedResultIgnoringThinkingBlocks`, `keyNumbersOutOfRangeIsInvalid` |
| At most 2 attempts; retry rules for 429/529 (`retry-after`), 5xx, I/O, `max_tokens`, invalid output; no retry for 4xx or refusal | `invalidJsonThenSuccessRetriesOnce`, `missingFieldTwiceReturnsFallback`, `maxTokensStopReasonIsRetried`, `refusalReturnsFallbackWithoutRetry`, `overloadedWithRetryAfterWaitsThenRetries`, `rateLimitWithoutRetryAfterUsesDefaultWait`, `retryAfterAboveMaxDoesNotRetry`, `serverErrorTwiceReturnsFallback`, `badRequestReturnsFallbackWithoutRetry`, `ioErrorIsRetried` |
| `analyze` never throws for API or model failures; returns fallback outcome with attempts and summed tokens | Verified across all failure tests in `AnalysisClientTest` |
| Blank API key → fallback without HTTP call; application starts without key | `blankApiKeyReturnsFallbackWithoutCall`, `RejuvApplicationTests` |
| API key never logged and masked in `toString()`; no secret committed | `AnthropicPropertiesTest.toStringMasksApiKey`, `git diff` review |
| `AnalysisInput` serializes to `mvp-spec.md` contract with `avg_bought_at_min` | `inputSerializationMatchesMvpContract` |
| Only the files in the table changed; no new dependency | `git diff --name-only origin/main...HEAD` |
| Task report created with `docs/PROJECT_CONTEXT.md` update marked pending integration; final diff has no unrelated files | Report created; context update deferred per spec |

## Deviations

- None.

## Known Issues and Open Questions

- Open question 1 from spec: `max_tokens` is configured at 1500 (raised from 600) because thinking tokens count toward `max_tokens` on Haiku 5.5. Can be adjusted in `application.yaml` without code changes if needed during Task 010 prompt tuning.
- Open question 2 from spec: Task 009 must map `AnalysisOutcome.isFallback()` to the non-AI fallback response and persist `model`, `inputTokens`, and `outputTokens` to `match_analysis`.

## Merge Risks and Context Update

- `docs/PROJECT_CONTEXT.md` was **not modified** in this branch because parallel task branches (such as Task 006) are active.
- Context update is marked **pending integration**. Facts to integrate into `docs/PROJECT_CONTEXT.md` after merge:
  - Task 008 Done: `AnalysisClient` talks to Claude Messages API (`claude-haiku-5-5`, low effort, max 1500 tokens) using structured JSON output, validates 4 coaching fields (with 1-4 key numbers), retries once with retry-after support, and returns a safe fallback outcome without throwing. Starts and runs with no API key configured.
