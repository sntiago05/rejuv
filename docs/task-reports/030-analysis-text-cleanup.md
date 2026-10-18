# Task 030: Remove stray `}` and `,` from AI analysis text

## Status
Implemented on `feature/030-analysis-text-cleanup`. `.\mvnw.cmd clean verify` passes. Not pushed, not merged.

## Summary
`AnalysisResult` now includes a compact constructor and static helper `clean(String)` that removes trailing characters in the set `}`, `{`, `]`, `[`, `,` and whitespace from all text components (`strength`, `biggestLeak`, `nextGameAction`) and `keyNumbers` elements.

Because Jackson deserialization of records invokes the canonical constructor, both newly generated responses from `AnalysisClient` and historical rows deserialized from `match_analysis` are cleaned uniformly in one place without altering the prompt, database records, or `AnalyzeService`.

## Important Files
- Created:
  - `src/test/java/sntiago05/rejuv/domain/AnalysisResultTest.java`: 12 unit tests validating spec examples, middle character preservation, null handling, and Jackson round-trip deserialization/serialization with property names preserved.
  - `src/test/resources/fixtures/anthropic/messages-stray-brace.json`: Claude response fixture with `next_game_action` ending with `creep score.}`.
  - `docs/task-reports/030-analysis-text-cleanup.md`: this task report.
- Modified:
  - `src/main/java/sntiago05/rejuv/domain/AnalysisResult.java`: added compact constructor and `clean(String)` method.
  - `src/test/java/sntiago05/rejuv/clients/AnalysisClientTest.java`: added test verifying clean behavior and single-attempt normal success with `messages-stray-brace.json`.

## Decisions
- Placed sanitization logic exclusively within the compact constructor of `AnalysisResult` as specified. This avoids touching `AnalyzeService` (which is concurrently edited in task 029) while covering both runtime AI client parsing and database JSON loading.
- Character trimming scans backwards for `Character.isWhitespace(c)` or set members `}`, `{`, `]`, `[`, `,` until reaching standard trailing sentence punctuation (`.`, `!`, `?`, `)`, `"`, `'`) or other text.

## Verification
- Baseline `.\mvnw.cmd clean verify`: BUILD SUCCESS, 675 tests, 0 failures, 2 skipped (35.289 s).
- Final `.\mvnw.cmd clean verify`: BUILD SUCCESS, 688 tests (+13), 0 failures, 2 skipped (34.960 s).
- Targeted test executions:
  - `.\mvnw.cmd -q test -Dtest=AnalysisResultTest`: 12 tests passed (exit code 0).
  - `.\mvnw.cmd -q test -Dtest=AnalysisClientTest`: 17 tests passed including `strayBraceInResponseTextIsCleanedAndCountsAsNormalSuccess` (exit code 0).
- `git diff --check`: clean (only standard Windows autocrlf working-copy warnings).
- Manual check: runtime behavior is covered by mock server and deserialization unit tests; manual Discord execution was not run due to lack of live Discord bot credentials.

## Deviations
None.

## Known Issues
None.

## Merge Risks
None. No public method signatures or JSON field names changed. Zero overlap with `AnalyzeService` changes in task 029.

PROJECT_CONTEXT update pending integration (parallel task branches `017`, `020`, `021`, `029`, `036` are active). Facts to add upon integration:
- `AnalysisResult` compact constructor sanitizes trailing `}`, `{`, `]`, `[`, `,` and trailing whitespace across all fields and `key_numbers` elements.
- Stored and freshly analyzed records are cleaned on read; no database migration or schema modification was made.
