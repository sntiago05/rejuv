# Task 058: `/heroinfo` (hero details from the assets endpoint `heroes/by-name`)

- **Branch**: `feature/058-hero-info`
- **Implementer**: Claude Code
- **Related ADRs**: ADR-0014 (pure data, zero AI cost), ADR-0019 (free model), ADR-0004 (deadlock-api)
- **Runs in parallel with**: 057 (`/trends`, `/nemesis`, rank percentile). Shared files: see "Parallel work".

## Goal
A free, no-AI command `/heroinfo hero:<name>` that calls `GET /v1/assets/heroes/by-name/{name}` and shows the hero's role, playstyle, type, complexity, tags and base stats in one embed.

## Verified facts (real calls, 2026-10-21, no key needed)
- `GET https://api.deadlock-api.com/v1/assets/heroes/by-name/{name}` returns 200 with one hero object (about 31 KB). Names are matched ignoring case; `class_name` (for example `hero_inferno`) also works. A name with a space or `&` works when URL-encoded (`Lady%20Geist`, `Mo%20%26%20Krill`); `Mo and Krill` is 404.
- Unknown name: HTTP 404 with body `{"status":404,"error":"Unknown hero name: <name>"}`.
- Optional query parameters: `language` (an enum such as `english`) and `client_version` (int). Not used here.
- Fields used (all present for Infernus and Haze):
  - `id` (int), `name`, `class_name`, `search_name` (nullable), `gender` (nullable).
  - `description`: `{lore, role, playstyle}` strings (role up to 43 chars, playstyle up to 237, lore up to 1,633 in the 65 heroes listed).
  - `tags` (string list), `gun_tag` (nullable), `hero_type` (`marksman`, `mystic`, `brawler`, `assassin` or null: 20 of 65 heroes have null), `complexity` (int; values 1 to 4 seen).
  - `player_selectable`, `disabled`, `in_development` (booleans). 12 heroes in the list are not selectable (for example Kali, Gunslinger, The Boss).
  - `colors.style_hex` (for example `#C93C26`), `images.icon_hero_card` (a PNG URL on `assets-bucket.deadlock-api.com`).
  - `starting_stats`: map of stat key to `{value, display_stat_name}`; `value` is an int or a float (`max_health` 830, `max_move_speed` 6.7, `sprint_speed` 1.6, `stamina` 3, `light_melee_damage` 50.0, `heavy_melee_damage` 116, `reload_speed` 1, `base_health_regen` 2.0).
- The list `GET /v1/assets/heroes` already exists in the repo (`DeadlockApiClient.getHeroes()`, `HeroAsset(id, className, name, images)`), and `HeroResolver.resolve(String)` turns user text into a `HeroAsset` (exact name ignoring case, else a single prefix match). `HeroNotFoundException(String input)` exists in `services/`. `CommandSupport.heroOption(String)` and `getHeroInput(event)`, `EmbedStyle.COLOR`, `AssetImages` exist. `DeadlockApiRequests.get(path, uri, reader, rateLimitedBodyIsSuccess, Supplier<RuntimeException> notFound)` supports a 404 mapping.
- AGENTS.md rules apply: cached client reads, 503 `Retry-After` once, text through `Messages`, `deferReply()`, one INFO log line per command, DTO records with `@JsonIgnoreProperties(ignoreUnknown = true)` and only the fields used.

## Files to create or modify
| File | Change |
|---|---|
| `src/main/java/sntiago05/rejuv/clients/dto/HeroDetail.java` | New record with only the fields above. Nested records `Description(lore, role, playstyle)`, `Colors(styleHex)` (`@JsonProperty("style_hex")`), `Images(iconHeroCard)` (`@JsonProperty("icon_hero_card")`), `StatValue(Double value)`. `starting_stats` is `Map<String, StatValue>`. |
| `src/main/java/sntiago05/rejuv/clients/DeadlockApiClient.java` | New `Optional<HeroDetail> getHeroDetail(String name)` (cached; see Behavior 2). Do not change other methods. |
| `src/main/java/sntiago05/rejuv/config/CacheConfig.java` and `src/test/.../DeadlockApiClientCachingTest.java` | New cache `DEADLOCK_HERO_DETAIL = "deadlockHeroDetail"`, TTL 2 hours, max 100 (the existing `deadlockAssets` cache holds only 10 entries, too small for 65 heroes). Add to the exact-set test; keep every existing entry. |
| `src/main/java/sntiago05/rejuv/domain/HeroInfo.java` | New record the service returns (see Interfaces). |
| `src/main/java/sntiago05/rejuv/services/HeroInfoService.java` | New. Resolve, fetch, map. No JDA types. |
| `src/main/java/sntiago05/rejuv/commands/HeroInfoCommand.java` | New slash command `heroinfo`. Validates, calls the service, builds the embed. |
| `src/main/resources/messages.properties` | Append `heroinfo.*` keys at the **end only**, and add the new command to `help.body` (one line, see Behavior 6). |
| tests | `HeroInfoServiceTest`, `HeroInfoCommandTest`, additions to `DeadlockApiClientTest` and `HelpCommandTest`, fixture `src/test/resources/fixtures/deadlock-api/hero-detail-infernus.json` (a trimmed real response: keep every field above, drop the large unused objects such as `models`, `items`, `level_info`, so the unknown-field handling is also tested with a few extra fields left in). |
| `docs/task-reports/058-hero-info.md` | New task report. Mark `docs/PROJECT_CONTEXT.md` as pending integration; do **not** edit it. |

No other file.

## Interfaces / signatures
- `Optional<HeroDetail> DeadlockApiClient.getHeroDetail(String canonicalHeroName)`; throws `DeadlockApiUnavailableException` / `DeadlockApiException` like the other reads; empty on HTTP 404.
- `record HeroInfo(int id, String name, String role, String playstyle, String lore, String heroType, Integer complexity, String gunTag, List<String> tags, String colorHex, String iconUrl, boolean playable, List<Stat> stats)` and `record Stat(String key, double value)`.
- `HeroInfo HeroInfoService.getInfo(String userInput)`; throws `HeroNotFoundException` when the name is not recognized or the endpoint says 404.
- Slash command: `/heroinfo hero:<text>` (required, same option helper as `/build`).

## Behavior
1. **Resolve first.** `HeroInfoService` calls `HeroResolver.resolve(userInput)`; no match means `HeroNotFoundException`. Then it calls the client with the **canonical** name from the resolver (`HeroAsset.name()`), never with the raw user text, so arbitrary text never reaches the URL.
2. **Client.** Path template `/v1/assets/heroes/by-name/{name}` built with a URI variable (`uri.path(PATH).build(name)`) so spaces and `&` are encoded by the builder (a test must prove `Lady Geist` and `Mo & Krill` give the requests `/Lady%20Geist` and `/Mo%20%26%20Krill`). Use `DeadlockApiRequests.get(..., false, notFoundSupplier)`; on the 404 marker return `Optional.empty()`. Cached with key = the name lower-cased. Honors the existing 503 `Retry-After` rule; never in parallel for different uncached keys is not required here (single small asset call).
3. **Mapping.** `role` and `playstyle` come from `description` (blank becomes null); `lore` is cut to at most 600 characters at a word boundary with `...` appended only when cut. `playable` is `player_selectable && !disabled && !in_development`. `tags` keep order, blank tags dropped. `stats` are only these keys, in this order, when present with a non-null value: `max_health`, `max_move_speed`, `sprint_speed`, `stamina`, `light_melee_damage`, `heavy_melee_damage`, `reload_speed`, `base_health_regen` (a `private static final List<String>` constant). `iconUrl` is `images.icon_hero_card`.
4. **Embed.**
   - Title: hero name. Color: `colors.style_hex` parsed as `#RRGGBB`; any other value falls back to `EmbedStyle.COLOR`. Thumbnail: `iconUrl` when not blank.
   - Description: the role (when present); when the hero is not playable, a second line with `heroinfo.not_playable`.
   - Fields, each skipped when it would be empty: **Type** (`hero_type` capitalized, a dash-free line such as `Marksman, complexity 1, gun: Rapid Fire`, built from the parts that exist), **Playstyle**, **Tags** (comma separated), **Base stats** (one short line per stat, `• Health: 830`, labels from `heroinfo.stat.<key>`, numbers with at most one decimal and no trailing `.0`), **Lore** (the cut text).
   - Every field value is cut to 1024 characters with whole lines dropped first (reuse `EmbedStyle.fitField`); the whole embed stays under 6000 characters (lore is the first thing to drop if it would not fit).
   - Footer: one short line from `heroinfo.footer`.
5. **Replies.** `deferReply()` first. Unknown hero: ephemeral-style plain message `heroinfo.hero_not_found` (edit of the original). 503 or network failure: the usual `error.data_source_busy`. Any other failure: `CommandSupport.handleUnexpectedError`. One INFO log line per command (user id, command, duration, outcome) in the same style as `BuildCommand`.
6. **Help.** Add one line to `help.body`: ``• 📖 `/heroinfo hero` - See a hero's role, stats and playstyle.`` placed after the `/build` line. The existing test checks that `help.body` plus the donation text is at most 2000 characters and today the body is about 1,880 characters: if the new line makes it exceed 2000, shorten **the new line** (not the other lines) and say so in the report. Add `/heroinfo` to the list in `HelpCommandTest.helpBodyCoversMvpTopics`.
7. **Efficiency.** One list lookup (cached) and one cached detail call; no loops over large collections.

## Out of scope
Other languages (`language` parameter), hero abilities, items, upgrades or `level_info`, images beyond the card icon, a web page for heroes, the AI coach, `HeroResolver` behavior, any change to `/build`, `/counter`, `/draft`, `/synergy`, the report, `docs/PROJECT_CONTEXT.md`, `docs/manual-testing.md`, ADRs, `pom.xml`, migrations.

## Parallel work
Task 057 edits `DeadlockApiClient`, `CacheConfig`, `DeadlockApiClientCachingTest`, `messages.properties` and `ProfileCommand`. On a merge conflict keep both sides (cache entries, appended keys). Do not reformat or reorder existing code.

## Acceptance criteria
- [ ] `./mvnw verify` passes (use `env -u ANTHROPIC_BASE_URL -u ANTHROPIC_CUSTOM_HEADERS -u ANTHROPIC_API_KEY`).
- [ ] Client tests (with `MockRestServiceServer`): correct path and encoding for `Lady Geist` and `Mo & Krill`, 404 gives empty, 503 with `Retry-After` honored once, unknown JSON fields ignored, second call served from cache.
- [ ] Service tests: resolved name used (not the raw input), unknown input and 404 give `HeroNotFoundException`, lore cut at a word boundary, stat order and filtering, `playable` for a disabled and an in-development hero, null `hero_type`.
- [ ] Command tests: embed shape with and without optional parts, color fallback for a bad hex, field limits with a very long lore and playstyle, not-found and busy messages, unexpected error path.
- [ ] No hardcoded user text, no duplicated helper, `HelpCommandTest` updated, task report written (with the real-call facts and the help-length result).
