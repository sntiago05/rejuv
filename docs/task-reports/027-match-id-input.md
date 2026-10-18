# Task 027: `/analyze` accepts a typed or pasted match id and rejects pasted label text

## Status

Implemented and verified locally. `./mvnw clean verify` passes with 636 tests (623 baseline + 13 new, 2 skipped). Not pushed, not merged.

## Step 0 Findings

1. JDA 5.6.1 (`javap` on the jar in the local Maven repo): `OptionData` with `OptionType.STRING` supports `setAutoComplete(true)` (the `hero` option already does this); `Command.Choice(String name, String value)` exists.
2. `OptionData.MAX_CHOICE_VALUE_LENGTH = 100`; a match id (at most 18 digits here) fits.
3. Diagnosis (INTEGER option never delivers pasted text, so `getOption("match")` is null and the latest match is analyzed): not reproduced against Discord. The fix does not depend on it.

## Summary

- `match` is now an `OptionType.STRING` option with autocomplete; autocomplete choice values are `String.valueOf(matchId)`, labels unchanged.
- New private static `parseMatchId(String)` in `AnalyzeCommand` (single pass, no regex): trim, 1 to 18 ASCII digits, value > 0.
- Invalid `match` replies ephemerally with `analyze.match_invalid` and returns before the linked-account lookup, `deferReply()` and any service call.
- Valid `match` keeps the previous flow; no `match` still analyzes the latest (or filtered) match; `match` still wins over `hero`/`result`.
- `analyze.option.match` reworded to "Pick a match from the list or type a match id (overrides hero and result filters)" (authorized); `analyze.match_invalid` added at the end of `messages.properties`.

## Important Files

- `src/main/java/sntiago05/rejuv/commands/AnalyzeCommand.java`
- `src/main/resources/messages.properties`
- `src/test/java/sntiago05/rejuv/commands/AnalyzeCommandTest.java`
- `docs/task-reports/027-match-id-input.md`

## Decisions

- Pasted labels are rejected rather than parsed (spec default).
- Manual parsing loop instead of a regex or `Long.parseLong` alone, so signs, spaces and separators are rejected without exceptions.

## Verification

- Baseline `./mvnw -q verify` before changes: green, 623 tests.
- `./mvnw -q test -Dtest=AnalyzeCommandTest`: 36 tests, 0 failures.
- `./mvnw clean verify`: BUILD SUCCESS, 636 tests, 0 failures, 0 errors, 2 skipped.
- `git diff --check`: clean.
- Manual check in the dev guild (dropdown, typed id, pasted label): not run (no Discord credentials used).

Tests added or changed: option type STRING; choice values as text; typed id passes `MatchFilter(118802457L, null, null)` with caller id; pasted label gives ephemeral invalid reply with `verifyNoInteractions(profileService, analyzeService)` and no deferral; parameterized invalid values (8) and valid values (3); `analyze.match_invalid` added to the key-resolution test.

## Deviations

None.

## Known Issues

- A valid id outside the 200-entry cached history still ends in `analyze.match_not_found` (out of scope).
- Task 026 also edits `AnalyzeCommand`; merge 027 before 026.

## Merge Risks

- Possible textual conflict with 026 in `AnalyzeCommand`.
- PROJECT_CONTEXT update pending integration (other task branches active). Facts to record: `/analyze match` is a STRING option with autocomplete; values accepted are 1 to 18 ASCII digits after trim, > 0; invalid input gives ephemeral `analyze.match_invalid` before any lookup or service call.
