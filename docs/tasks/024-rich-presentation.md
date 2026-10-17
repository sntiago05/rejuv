# Task 024: Rich presentation (hero/item icons, rank badge, friendlier embeds)

- **Branch**: `feature/024-rich-presentation`
- **Implementer**: Antigravity
- **Related ADRs**: ADR-0008 (English, i18n-ready), ADR-0010 (deadlock-api), ADR-0001 (JDA; this task bumps its version). A new **ADR-0016** is authorized (see Documentation).

## Goal
Make every existing command reply look friendly: colored embeds with clear layout, a **hero icon or item icon next to each hero/item name**, the **hero card as thumbnail** in `/build` and `/counter`, and the **player's rank badge as thumbnail** in `/profile`. All images are hosted by deadlock-api; no image file is stored in the repository. Inline icons are Discord **application emojis** that the bot uploads once from the deadlock-api image URLs (an embed cannot show an image beside each text line any other way). If icons are unavailable, every command still works with text only.

## Context (verified when this spec was written, 2026-10-17)
Do not repeat this discovery. If a fact below is false in your checkout, stop and ask.

- **Dependency: Task 012 (`/counter`) must be merged into `main` first.** This task changes `CounterCommand` and its test, which Task 012 creates. If `commands/CounterCommand.java` does not exist, stop and ask; do not create it. Task 011 (`/build`) is already merged. Start the branch from the latest `origin/main`. Task 012's `docs/PROJECT_CONTEXT.md` update may still be pending; do not edit that file here either (see Documentation).
- Commands with replies today: `HelpCommand` (ephemeral text), `PingCommand`, `LinkCommand`, `UnlinkCommand` (one-line text, **unchanged by this task**), and four embed commands: `ProfileCommand`, `MetaCommand`, `BuildCommand`, `CounterCommand`. They use `EmbedBuilder` with `addField`/`setFooter` and no color, no image.
- Assets are read through `DeadlockApiClient.getRanks()`, `getHeroes()`, `getItems()` (paths `/v1/assets/ranks`, `/heroes`, `/items`), all cached in `deadlockAssets` (24 h). Current DTOs: `HeroAsset(int id, String className, String name)`, `ItemAsset(long id, String className, String name, String type, Integer itemTier, String itemSlotType, Integer cost)`, `RankAsset(int tier, String name)`. They ignore image fields; the test fixtures `assets-heroes.json`, `assets-items.json`, `assets-ranks.json` have no image fields either.
- **Image fields in the real API** (read live 2026-10-17, one request per assets endpoint; GET works, HEAD returns 403):
  - Heroes (65): `images.icon_image_small` (e.g. `https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/inferno_sm.png`, 20 KB PNG) and `images.icon_hero_card` (`.../heroes/inferno_card.png`), plus many other keys and `_webp` variants we do not use.
  - Items: top-level `image` (e.g. `.../images/upgrades/mods_weapon/clip_size.png`, 765 bytes PNG) and `shop_image`. 250 items have `type == "upgrade"`; 15 shopable upgrades have no `image`, so the field can be null. Abilities (431) are not used here.
  - Ranks (tiers 0-11): `images.large`, `images.large_subrank1` ... `images.large_subrank6` (e.g. `https://api.deadlock-api.com/v1/assets/ranks/7/1/image`), `images.small_subrank1..6`, `chalk`, and `_webp` variants. Tier 0 (Obscurus) only has `large`, `large_webp`, `small`, `small_webp`.
- `ProfileSummary` has `Integer rankTier` (null = unranked), `String rankName`, `int subrank` (0 = unknown). `ProfileService` is **not** changed.
- **JDA 5.0.0 is pinned in `pom.xml` and has no application-emoji API.** Application emojis (`JDA#retrieveApplicationEmojis`, `JDA#createApplicationEmoji(String, Icon)`, `ApplicationEmoji#getAsMention`) exist from JDA 5.2.0 on. This task bumps the JDA version (the only dependency change authorized).
- Discord limits that matter: embed field value <= 1024 chars, embed total <= 6000, application emojis: 2,000 per app, name 2-32 chars `[A-Za-z0-9_]`, image <= 256 KB. Custom emojis render in field values and descriptions; **do not put them in titles, field names or footers** (use Unicode emoji there).
- Rules from `AGENTS.md` still apply: no JDA types in `services/`, no business logic in `commands/`, all user-facing text in `messages.properties`, messages with arguments contain no single apostrophe, DTO records with `@JsonIgnoreProperties(ignoreUnknown = true)` and only the fields used, no secrets in logs.

## Step 0: verify before writing code
1. Check on Maven Central which JDA 5.x release is current and pick the newest **stable 5.x release that is at least 5.2.0** (do not use a milestone, beta or 6.x). Put it in `pom.xml` and run `./mvnw clean verify` **before any other change** to confirm the bump alone keeps the 214+ existing tests green. If it does not, stop and report the failure; do not patch around it.
2. Confirm in that JDA version the exact signatures of `retrieveApplicationEmojis()`, `createApplicationEmoji(String, Icon)` and `ApplicationEmoji#getName()/getAsMention()`. Record them in the report. If the application-emoji API is missing or differs materially, **stop and ask**.
3. Confirm with one GET each that a hero `icon_image_small`, an item `image` and a rank `large_subrank1` URL return 200 `image/png`.

## Files to create or modify
| File | Change |
|---|---|
| `pom.xml` | **Modify.** Only the JDA version (Step 0). |
| `src/main/java/sntiago05/rejuv/clients/dto/HeroImages.java` | **Create.** Record. |
| `src/main/java/sntiago05/rejuv/clients/dto/HeroAsset.java` | **Modify.** Add `images`; keep the old 3-argument constructor. |
| `src/main/java/sntiago05/rejuv/clients/dto/ItemAsset.java` | **Modify.** Add `image`; keep the old 7-argument constructor. |
| `src/main/java/sntiago05/rejuv/clients/dto/RankAsset.java` | **Modify.** Add `images`; keep the old 2-argument constructor. |
| `src/main/java/sntiago05/rejuv/clients/AssetImageClient.java` | **Create.** Downloads one image. |
| `src/main/java/sntiago05/rejuv/services/AssetImages.java` | **Create.** Hero card and rank badge URLs. |
| `src/main/java/sntiago05/rejuv/services/IconCatalog.java` | **Create.** In-memory emoji markup by hero/item id. |
| `src/main/java/sntiago05/rejuv/services/EmojiGateway.java` | **Create.** Interface (no JDA types). |
| `src/main/java/sntiago05/rejuv/services/EmojiSyncService.java` | **Create.** Uploads missing emojis, fills the catalog. |
| `src/main/java/sntiago05/rejuv/config/JdaEmojiGateway.java` | **Create.** JDA implementation of `EmojiGateway`. |
| `src/main/java/sntiago05/rejuv/config/EmojiSyncStarter.java` | **Create.** Runs the sync once after JDA is ready, in the background. |
| `src/main/java/sntiago05/rejuv/commands/EmbedStyle.java` | **Create.** Package-private helper (color, icon line, field fitting). |
| `src/main/java/sntiago05/rejuv/commands/ProfileCommand.java` | **Modify.** Rank badge thumbnail, author avatar, hero icons, color. |
| `src/main/java/sntiago05/rejuv/commands/MetaCommand.java` | **Modify.** Hero icons, color. |
| `src/main/java/sntiago05/rejuv/commands/BuildCommand.java` | **Modify.** Hero thumbnail, item icons, color. |
| `src/main/java/sntiago05/rejuv/commands/CounterCommand.java` | **Modify** (exists only after Task 012). Hero thumbnail, hero and item icons, color. |
| `src/main/java/sntiago05/rejuv/commands/HelpCommand.java` | **Modify.** Reply as an ephemeral embed. |
| `src/main/resources/messages.properties` | **Modify.** Re-style the listed existing values and add the listed new keys. Change nothing else. |
| `src/test/resources/fixtures/deadlock-api/assets-heroes.json`, `assets-items.json`, `assets-ranks.json` | **Modify.** Add image fields in the real shape (URLs as above, invented ids kept). |
| `src/test/java/sntiago05/rejuv/clients/DeadlockApiClientTest.java` | **Modify.** Add asset image parsing tests only. |
| `src/test/java/sntiago05/rejuv/clients/AssetImageClientTest.java` | **Create.** |
| `src/test/java/sntiago05/rejuv/services/AssetImagesTest.java`, `IconCatalogTest.java`, `EmojiSyncServiceTest.java` | **Create.** |
| `src/test/java/sntiago05/rejuv/commands/ProfileCommandTest.java`, `MetaCommandTest.java`, `BuildCommandTest.java`, `CounterCommandTest.java`, `HelpCommandTest.java` | **Modify.** Constructor changes and new/updated assertions only. |
| `src/test/java/sntiago05/rejuv/commands/EmbedStyleTest.java` | **Create.** |
| `src/test/java/sntiago05/rejuv/services/MessagesIntegrationTest.java` | **Modify.** Add the new keys to `requiredKeys` only. |
| `docs/adr/0016-application-emoji-icons.md` | **Create.** New ADR (format of `docs/adr/0000-template.md`). |
| `docs/task-reports/024-rich-presentation.md` | **Create.** Task report. |

No other file changes. No migration, no `application.yaml` change, no new dependency besides the JDA version. If you think one is needed, stop and ask.

## Interfaces / signatures

### DTOs (`sntiago05.rejuv.clients.dto`)
```java
@JsonIgnoreProperties(ignoreUnknown = true)
public record HeroImages(@JsonProperty("icon_image_small") String iconImageSmall,
                         @JsonProperty("icon_hero_card") String iconHeroCard) { }

// HeroAsset: new last component
@JsonProperty("images") HeroImages images
// ItemAsset: new last component
@JsonProperty("image") String image
// RankAsset: new last component (keys such as "large", "large_subrank1" .. "large_subrank6")
@JsonProperty("images") Map<String, String> images
```
Each of the three records keeps its **previous canonical signature as an additional constructor** (delegating with `null` for the new component) so existing tests and call sites compile unchanged. A test must prove Jackson still deserializes the fixtures through the canonical constructor (images present, and absent).

### `clients/AssetImageClient` (`@Component`, new)
```java
public byte[] download(String url);   // GET, bytes of the image
```
Over `RestClient` (reuse the builder pattern of `DeadlockApiConfig`; no new dependency). Rules: only `https` URLs whose host is exactly `assets-bucket.deadlock-api.com` or `api.deadlock-api.com`, otherwise `IllegalArgumentException` before any HTTP call (it downloads URLs that come from an external API); response must be non-empty and <= 262144 bytes (256 KB), else `DeadlockApiException`; I/O or timeout -> `DeadlockApiUnavailableException`; other status -> `DeadlockApiException`. **Not cached**: this is the one documented exception to "every read is cached", because it only runs for emojis that do not yet exist in Discord and the result is stored there. Never log the response bytes.

### `services/AssetImages` (`@Component`, new)
```java
public AssetImages(DeadlockApiClient deadlockApiClient)
/** icon_hero_card of the hero, or empty (unknown hero, null images, blank url, or asset call failed). */
public Optional<String> heroCardUrl(int heroId)
/** tier 0..11. subrank 1..6 -> images "large_subrank<N>"; otherwise (0 or missing key) -> "large". Empty if the tier or url is missing or the asset call failed. */
public Optional<String> rankBadgeUrl(int tier, int subrank)
```
Uses only the cached `getHeroes()`/`getRanks()`. Catches `DeadlockApiException` and `DeadlockApiUnavailableException` (log `WARN`, no stack trace spam) and returns empty: a missing picture must never fail a command.

### `services/IconCatalog` (`@Component`, new)
```java
public static String heroEmojiName(int heroId)    // "h_" + heroId
public static String itemEmojiName(long itemId)   // "i_" + itemId
public Optional<String> hero(int heroId)          // emoji markup, e.g. "<:h_1:123456789>", or empty
public Optional<String> item(long itemId)
public void replace(Map<String, String> markupByEmojiName)   // atomically swaps an immutable copy; thread-safe
```
Starts empty. Reads never block.

### `services/EmojiGateway` (interface, new, no JDA types)
```java
Map<String, String> listExisting();                  // emoji name -> markup (<:name:id>)
String create(String name, byte[] image);            // returns the markup of the created emoji
```

### `services/EmojiSyncService` (`@Service`, new)
```java
public EmojiSyncService(DeadlockApiClient deadlockApiClient, AssetImageClient assetImageClient,
                        EmojiGateway emojiGateway, IconCatalog iconCatalog)
public void sync()
```
Behavior: (1) `existing = emojiGateway.listExisting()`; publish it to the catalog immediately (so already-uploaded icons work while the rest uploads). (2) Desired emojis, from the cached assets: every hero with a non-blank `images.iconImageSmall` -> name `heroEmojiName(id)`; every item with `type` equal (ignoring case) to `upgrade` and a non-blank `image` -> name `itemEmojiName(id)`. (3) For each desired name **not** in `existing`, sequentially: `assetImageClient.download(url)` then `emojiGateway.create(name, bytes)`; add the markup to the map. A failure on one emoji is logged at `WARN` (name only) and skipped; the others continue. (4) Hard cap: never create more than `MAX_NEW_EMOJIS = 400` in one run (stop and log `WARN`). (5) Publish the final map to the catalog after the loop and, at most every 25 creations, an interim map. (6) Any failure of `listExisting()` or of the asset reads is logged at `WARN` and ends the run **without throwing**; the catalog stays as it was. One `INFO` summary line: existing, created, skipped.

### `config/JdaEmojiGateway` (`@Component`, new) and `config/EmojiSyncStarter` (new)
- `JdaEmojiGateway implements EmojiGateway` over the `JDA` bean: `listExisting` uses `retrieveApplicationEmojis().complete()`; `create` uses `createApplicationEmoji(name, Icon.from(bytes)).complete()` and returns `getAsMention()`.
- `EmojiSyncStarter`: when JDA is ready (look at `JdaConfig` for how the bean is built; use the mechanism that fits, e.g. an `ApplicationRunner` that starts one daemon thread calling `jda.awaitReady()` then `EmojiSyncService.sync()`), run the sync **once, in the background**. It must not delay or fail application startup, and must be skipped quietly if the JDA bean is absent in a test context. Add a Boolean property **only if** it is needed to keep tests from calling Discord; if so, name it `rejuv.emoji-sync.enabled` (default true), defined in `config/` as a `@ConfigurationProperties` or `@ConditionalOnProperty`, and set it false in the test context. Do not touch `application.yaml` unless required; if required, only add that one key.

### `commands/EmbedStyle` (package-private final class, new)
```java
static final int COLOR = 0x...;                       // one brand color (pick a readable accent), used by all embed commands
static String withIcon(Optional<String> icon, String text)    // icon present: icon + " " + text; else text
static String fitField(List<String> lines)            // joins with "\n"; drops WHOLE trailing lines until the result is <= 1024 chars
```
`fitField` of an empty list returns an empty string (callers decide whether to show the field).

## Behavior

### Common
- Every embed gets `setColor(EmbedStyle.COLOR)`.
- Where a hero/item name is shown in a field value, the line is `EmbedStyle.withIcon(iconCatalog.hero(id) | item(id), text)`. If the icon is absent the line is exactly the text-only line (the command still works fully).
- Thumbnail URLs come from `AssetImages`; if empty, no thumbnail is set. No command makes any new API call except through the cached asset reads inside `AssetImages`.
- Titles, field names and footers get only **Unicode** emoji (through `messages.properties`); application-emoji markup appears only in field values and descriptions.
- Every field value goes through `fitField` so it can never exceed 1024 characters (this matters for the `/meta` tiers once icons are added). No command may produce an embed over 6000 characters.
- Error, "not linked", "no data" and "hero not found" replies stay plain text (as they are now).

### `/profile`
- Thumbnail = rank badge: `assetImages.rankBadgeUrl(summary.rankTier(), summary.subrank())` when `rankTier` is non-null; no thumbnail for unranked.
- Author line = `target.getEffectiveName()` with `target.getEffectiveAvatarUrl()` as icon (replaces the name in the title; the title becomes the static `profile.title` text).
- Top heroes lines use the hero icon. Recent-form text and layout otherwise stay.

### `/meta`, `/build`, `/counter`
- `/meta`: each hero line gets its hero icon; tier field names gain a Unicode emoji (catalog). Heroes inside a tier stay sorted as today.
- `/build`: thumbnail = `heroCardUrl(build.heroId())`; each item line gets its item icon; the ability order text is unchanged (no ability icons).
- `/counter`: thumbnail = `heroCardUrl(guide.heroId())`; matchup lines get hero icons, item lines get item icons.

### `/help`
- Same ephemeral reply, now an embed: `help.title` as title, `help.body` as description (body restyled with bullet lines and Unicode emoji), `EmbedStyle.COLOR`. The set of listed commands is unchanged by this task (still including `/counter` after Task 012).

### Emoji sync
- Runs once per start, in the background; the bot is usable immediately. First run uploads about 300 small emojis (can take minutes); later runs only add missing ones. Until the catalog is filled, commands show text only.
- Emoji names are `h_<heroId>` and `i_<itemId>`; the sync never deletes or renames existing application emojis.

## User-facing text (`messages.properties`, English)
No hardcoded text in code. Unicode emoji in the file is allowed (file is UTF-8). Argument messages: no single apostrophe. Text length limits as before.

- **Restyle values of existing keys** (keep the keys and every placeholder `{n}` and its meaning; add a leading Unicode emoji and/or Markdown bold; keep lengths within Discord limits): `profile.title` (now has **no** `{0}`: it becomes a static title; update the one test that used it), `profile.field.rank`, `profile.field.recent`, `profile.field.top_heroes`, `meta.title`, `meta.tier.s`..`meta.tier.d` (one distinct Unicode emoji each), `meta.hero_entry` (hero name bold), `build.title`, `build.phase`, `build.item_entry` (item name bold), `build.abilities`, `counter.title`, `counter.field.counters`, `counter.field.items`, `counter.field.favorable`, `counter.matchup_entry`, `counter.item_entry`, `help.body` (bullets, one command per line, emoji allowed; same commands and meaning).
- **New keys:** `help.title` (embed title, e.g. a short product line with an emoji).
- `profile.hero_line` and `*_unknown` keys keep their placeholders.

## Tests
JUnit 5, AssertJ, Mockito; `MockRestServiceServer` for HTTP; `MessagesFixture.english()` for commands.

1. **DTO/client** (`DeadlockApiClientTest`): heroes, items and ranks fixtures parse image fields (`icon_hero_card`, `icon_image_small`, `image`, `large_subrank1`); an asset without images parses with nulls; the old constructors still work.
2. **`AssetImageClientTest`:** returns bytes for an allowed host; rejects `http://`, another host, and a look-alike host (`assets-bucket.deadlock-api.com.evil.example`) with `IllegalArgumentException` and **no** request; body over 256 KB -> `DeadlockApiException`; empty body -> `DeadlockApiException`; timeout/I/O -> `DeadlockApiUnavailableException`; non-2xx -> `DeadlockApiException`.
3. **`AssetImagesTest`:** hero card present/absent/unknown id; rank badge uses `large_subrank<N>` for subrank 1..6, falls back to `large` for subrank 0 and for a missing key, empty for unknown tier; asset exceptions -> empty (not thrown).
4. **`IconCatalogTest`:** names; empty at start; `replace` swaps atomically and later reads see it; the stored map is a copy (mutating the argument has no effect).
5. **`EmojiSyncServiceTest`** (fake `EmojiGateway`, mock clients): existing emojis are published and **not** re-created; only missing ones are created; heroes without `iconImageSmall` and items that are not `upgrade` or have no `image` are not created; one failed download/create does not stop the others and is skipped; `MAX_NEW_EMOJIS` stops the run; `listExisting` failure, or `DeadlockApiUnavailableException` on assets, ends the run without throwing and leaves the catalog unchanged; final catalog contains existing + created.
6. **`EmbedStyleTest`:** `withIcon` with and without icon; `fitField` keeps all lines when short, drops whole trailing lines (never cuts a line) at the 1024 limit, empty list -> empty string.
7. **Command tests** (update existing, add new): for each of `/profile`, `/meta`, `/build`, `/counter`: (a) with icons and thumbnail present, the embed has the color, the thumbnail URL (`/profile`: the badge; `/build` and `/counter`: the hero card), and field lines start with the icon markup; (b) with an empty `IconCatalog` and empty `AssetImages`, the embed has no thumbnail and the lines are text-only and equal to the previous format; (c) a very long `/meta` tier never exceeds 1024 characters per field. `/profile`: author name/avatar set, unranked player -> no thumbnail. `HelpCommandTest`: ephemeral embed with `help.title`, description from `help.body`, still lists `/counter`.
8. **`MessagesIntegrationTest`:** every new key resolves (needs Docker). Run and report the full suite.

## Verification
1. Step 0 done and recorded in the report (JDA version chosen, API signatures confirmed, three image URLs return 200 PNG).
2. `./mvnw clean verify` passes with Docker running (Windows: `.\mvnw.cmd clean verify`; see the `MAVEN_USER_HOME` note in `docs/PROJECT_CONTEXT.md` if the wrapper fails). Report the real test count against the baseline you measure on `origin/main` before your first change.
3. `git diff --check`; review the final diff: only the files in the table changed, no secret, token or real user data; `.env` never read or printed; no image binary added to the repo.
4. Manual check in the dev guild (needs `DISCORD_TOKEN`, `DEV_GUILD_ID`, PostgreSQL): start the bot, wait for the `INFO` sync summary, confirm in the Discord developer portal (Emojis tab) that `h_*`/`i_*` emojis exist, then run `/meta`, `/build hero:Haze`, `/counter hero:Haze`, `/profile` (linked and unranked cases if possible) and `/help`. Also run once with the emoji sync failing or not finished (e.g. immediately after start) and confirm text-only replies. If you do not run this check, say so in the report.

## Documentation
- Task report `docs/task-reports/024-rich-presentation.md`: status, Step 0 findings, summary, files, commands run with real results, deviations, known issues.
- New `docs/adr/0016-application-emoji-icons.md`: decision to show per-line icons as application emojis uploaded from deadlock-api image URLs (not stored in the repo), the JDA bump it required, the 2,000-emoji limit and `MAX_NEW_EMOJIS`, and the text-only fallback. Do not edit any existing ADR.
- `docs/PROJECT_CONTEXT.md`: **do not edit in this branch** while other task branches are active. Mark the update "pending integration" in the report and list the facts to add (JDA version, emoji sync, `AssetImages`, `IconCatalog`, `AssetImageClient` as the one uncached read, that Task 024 is done).
- Do not edit `docs/mvp-spec.md`, `docs/architecture.md`, `docs/features.md`, `docs/implementation-plan.md` (task 024 is not in it yet; the owner adds the row), `docs/roadmap.md` or other specs.

## Out of scope
- `/link`, `/unlink`, `/ping` replies; error, not-linked, no-data and hero-not-found messages; `/analyze` and any future command (they reuse `EmbedStyle`, `IconCatalog` and `AssetImages` later).
- Ability icons, `shop_image`, `_webp` images, hero background or card art as a large embed image, generated or composite images, attaching files, buttons, pagination, select menus.
- Deleting or renaming existing application emojis, per-guild emojis, hosting images ourselves, a database table for emoji ids.
- Changing `ProfileService`, other services' logic, `CommandRouter`, `Messages`, `HeroResolver`, existing client methods or cache names.
- Any other dependency upgrade besides JDA.

## Acceptance criteria
- [ ] Task 012 is merged; `CounterCommand` is modified, not recreated
- [ ] JDA bump alone passes the existing suite (Step 0); chosen version and API signatures are in the report
- [ ] Asset DTOs parse image fields; old constructors still compile and work
- [ ] `/profile` shows the rank badge thumbnail (ranked players) and the user's avatar in the author line
- [ ] `/build` and `/counter` show the hero card thumbnail; hero and item names show their icon inline when the catalog has it
- [ ] `/meta` and `/profile` hero lines show hero icons; no field value exceeds 1024 chars and no embed exceeds 6000
- [ ] With no icons or thumbnails available, all four embed commands still reply with text-only content in the previous format (plus color); no command fails because of an image
- [ ] `/help` replies as an ephemeral embed and still lists every command
- [ ] Emoji sync runs once in the background, never blocks or fails startup, creates only missing emojis (cap 400 per run), and a failed emoji or API outage is skipped without crashing
- [ ] `AssetImageClient` only accepts the two allowed https hosts and images <= 256 KB
- [ ] All user-facing text is in `messages.properties`; no custom-emoji markup in titles, field names or footers
- [ ] `./mvnw clean verify` passes; test count and baseline difference are in the report
- [ ] ADR-0016 and the task report are created; `docs/PROJECT_CONTEXT.md` update marked pending integration; final diff has no unrelated files and no image binaries

## Decisions and open questions
Decisions taken in this spec:
1. **Application emojis for inline icons** (owner choice, 2026-10-17), because embeds only allow one thumbnail and one large image. Rejected: composite generated images (fragile, needs attachments) and thumbnail-only (no per-item pictures).
2. **JDA bump** from 5.0.0 because application emojis need >= 5.2.0; it is verified first and alone (Step 0) so a regression is attributable.
3. **Hero card as thumbnail, rank badge as thumbnail in `/profile`**; hero `icon_image_small` and item `image` as emoji art.
4. **Failure is always soft**: no icon means text-only, never an error.
5. **`AssetImageClient` is uncached** by design and host-restricted; its inputs come from an external API.
6. **Only upgrade items and all heroes** get emojis (about 315 of 2,000 slots); abilities (431, mostly per-hero) are left out.

Open for the owner (not blocking):
1. Brand accent color for `EmbedStyle.COLOR` is chosen by the implementer; change it in review if you prefer another.
2. Whether `/link`, `/unlink`, `/ping` should also get Unicode emoji prefixes later.
3. A follow-up could prune orphaned emojis if the emoji count approaches the 2,000 limit.
