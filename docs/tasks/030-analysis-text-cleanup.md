# Task 030: Remove stray `}` and `,` from AI analysis text

- **Branch**: `feature/030-analysis-text-cleanup`
- **Implementer**: Antigravity
- **Related ADRs**: none
- **Depends on**: 008, 009 (merged). Evidence: `docs/evidence/029-coaching-analytics/real-analyze-results.json`.

## Goal
Real analyses stored in `match_analysis` contain stray JSON characters at the end of `next_game_action` (`...creep score.}` in matches 118563188 and 119330211; `...above 1043.3.,` in match 118693768). Clean them in one place so both new AI results and the already stored rows read correctly. Smallest possible change, no prompt or schema change.

## Files to create or modify
| File | Change |
|---|---|
| `domain/AnalysisResult.java` | Modify: add a compact constructor that cleans the four text fields. |
| `src/test/java/sntiago05/rejuv/domain/AnalysisResultTest.java` | **New.** |
| `src/test/java/sntiago05/rejuv/clients/AnalysisClientTest.java` | Modify: add one test. |
| `src/test/resources/fixtures/anthropic/messages-stray-brace.json` | **New.** Same shape as `messages-success.json`, with `next_game_action` ending in `.}`. |
| `docs/task-reports/030-analysis-text-cleanup.md` | **New.** Task report. |

Nothing else. Do not touch `AnalyzeService` (task 029 edits it in parallel): cleaning in the record constructor already covers the stored path, because Jackson builds `AnalysisResult` through its canonical constructor. `docs/PROJECT_CONTEXT.md`: mark the update as pending integration.

## Interfaces / signatures
`AnalysisResult` keeps its exact components and JSON names. Add:

```java
public AnalysisResult {
    strength = clean(strength);
    biggestLeak = clean(biggestLeak);
    nextGameAction = clean(nextGameAction);
    keyNumbers = keyNumbers == null ? null : keyNumbers.stream().map(AnalysisResult::clean).toList();
}

/** Package-private or private static; null stays null. */
static String clean(String text)
```

## Behavior
`clean(text)`:
1. `null` returns `null`.
2. Repeatedly remove trailing characters from the set `}`, `{`, `]`, `[`, `,` and whitespace until the last character is outside the set. A trailing `.`, `!`, `?`, `)`, `"` or `'` is never removed.
3. Do not touch characters anywhere except the end. A `}` or `,` in the middle stays (for example `"Deaths: 7, assists: 9"`).
4. If the result is empty, return the empty string (the existing validation in `AnalysisClient` still rejects blank required fields; do not change it).
5. Leading whitespace is not trimmed here (the client's existing `trim()` stays as is). `keyNumbers` elements are cleaned with the same rule; a `null` element stays `null`.

Expected examples:
- `"...to close the gap on creep score.}"` becomes `"...to close the gap on creep score."`
- `"...stays above 1043.3.,"` becomes `"...stays above 1043.3."`
- `"Deaths: 7 / 5"` is unchanged; `"Good job.} ,"` becomes `"Good job."`; `"}"` becomes `""`.

`AnalysisClient` needs no code change (it already builds a new `AnalysisResult` through the constructor); the new test proves the end-to-end behavior. `AnalyzeCommand` and the stored-row path are unchanged and benefit automatically.

## Out of scope
Changing the prompt, `max_tokens`, the JSON schema, validation rules, `AnalyzeService`, the database rows (no migration, no rewriting of stored JSON; cleaning happens when they are read), or Markdown/emoji handling.

## Tests
- `AnalysisResultTest`: the five examples above; middle `}`/`,` preserved; null fields and null `keyNumbers` stay null; null element stays null; a Jackson round trip of the stored JSON from `real-analyze-results.json` row 3 (`...creep score.}`) returns the cleaned text (embed the string literal in the test, do not read the evidence file); serialization names unchanged (`biggest_leak`, `next_game_action`, `key_numbers`).
- `AnalysisClientTest`: with `messages-stray-brace.json` the returned result has no stray character and counts as a normal (non-fallback) success after one attempt.

## Verification
`./mvnw clean verify` (on this machine `.\mvnw.cmd clean verify`, Docker running). Record baseline and final test counts.

## Acceptance criteria
- [ ] Compiles, all tests pass, counts recorded in the report
- [ ] Stored and fresh results never show a trailing `}`, `]` or `,`
- [ ] Only the files listed above changed; no new dependency; no user-facing text added
- [ ] Task report created; `docs/PROJECT_CONTEXT.md` update marked pending integration
- [ ] Follows AGENTS.md conventions
