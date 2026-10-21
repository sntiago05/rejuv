# Task 058: `/heroinfo` (hero details from `heroes/by-name`)

## Status
Implemented on branch `feature/058-hero-info`. `env -u ANTHROPIC_BASE_URL -u ANTHROPIC_CUSTOM_HEADERS -u ANTHROPIC_API_KEY ./mvnw verify` passes: 1224 tests, 0 failures, 14 skipped. Not pushed, not merged. `docs/PROJECT_CONTEXT.md` is **pending integration** (task 057 is still active).

## Summary
- New cached client call `DeadlockApiClient.getHeroDetail(String canonicalHeroName)` for `GET /v1/assets/heroes/by-name/{name}`. HTTP 404 gives `Optional.empty()`. The name is a URI variable, so a space becomes `%20` and `&` becomes `%26`. New cache `deadlockHeroDetail` (2 h, max 100); the key is the lower-cased name.
- `HeroInfoService` resolves the typed text with `HeroResolver` first and sends only the canonical name to the API (the raw text never reaches the URL). Unknown text and a 404 both give `HeroNotFoundException`.
- `/heroinfo hero:<name>` shows one embed: role (description), type line (`Marksman, Complexity 1, Gun: Rapid Fire`), playstyle, tags, eight base stats, lore (cut at a word, at most 600 characters). The embed color is the hero's own `#RRGGBB` (brand color if the value is not valid). A hero that cannot be played gets an extra line.
- `/help` lists the new command.

## Real-call facts (2026-10-21, checked before writing the code)
- `GET .../by-name/Infernus` and `.../by-name/infernus` and `.../by-name/hero_inferno` all return 200; `Lady%20Geist` and `Mo%20%26%20Krill` return 200; `Mo and Krill` returns 404. An unknown name returns 404 with `{"status":404,"error":"Unknown hero name: <name>"}`.
- In the list of 65 heroes, 20 have a null `hero_type`, 12 are not selectable, `complexity` is 1 to 4, one name has a symbol (`Mo & Krill`) and one internal entry is `hero_testhero`.

## Important files
- New: `clients/dto/HeroDetail.java`, `domain/HeroInfo.java`, `services/HeroInfoService.java`, `commands/HeroInfoCommand.java`, fixture `fixtures/deadlock-api/hero-detail-infernus.json` (a trimmed real response plus one unknown field), `HeroInfoServiceTest`, `HeroInfoCommandTest`.
- Changed: `DeadlockApiClient`, `CacheConfig`, `DeadlockApiClientCachingTest`, `DeadlockApiClientTest`, `HelpCommandTest`, `messages.properties` (`heroinfo.*` keys at the end, one line in `help.body`).

## Deviations
- The `/help` line is `/heroinfo hero - See hero details.` The first wording made help plus the donation text 2,004 characters (limit 2,000), as the spec foresaw; I shortened only the new line twice (to 1,998 characters). The other lines are unchanged.
- Fields are added by `addIfFits`: each is cut to 1,024 characters and is dropped (lore first, since it is added last) if the embed would pass 6,000 characters. The description is cut to 1,024 characters, not 4,096, to keep room for the fields.
- `hero_testhero` (an internal test entry) can appear in the hero list; it has no valid name for users and is never matched by `HeroResolver` unless typed exactly.

## Verification
- `./mvnw verify`: BUILD SUCCESS, 1224 tests, 0 failures, 14 skipped.
- Client tests with `MockRestServiceServer`: fields and unknown-field handling, URL encoding for `Lady Geist` and `Mo & Krill`, 404 gives empty, 503 with `Retry-After` retried once then mapped, cache hit for the same name in any letter case.
- Service tests: canonical name used, both not-found paths, API failure propagates, `playable` rules, stat order and filtering, null parts, lore cut.
- Command tests: embed shape and limits (with huge text), color fallback, number format, not-found, busy and unexpected-error replies.
- Not done: the command was **not run in Discord** and the real endpoint was **not called by the bot itself** (only by `curl` while writing the spec).

## Known issues and follow-up
- Hero names with a symbol depend on the resolver matching the typed text (`Mo & Krill` works if typed as such).
- Only English text is returned (the `language` parameter is not used).
- Manual check: `/heroinfo hero:Infernus`, `/heroinfo hero:haze`, `/heroinfo hero:Mo & Krill`, `/heroinfo hero:zzz`, and a hero that is not playable (for example `Kali`, if the resolver finds it).
