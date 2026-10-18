# Task 020: Patreon Premium role check

- **Branch**: `feature/020-patreon-role-check`
- **Implementer**: Antigravity
- **Related ADRs**: ADR-0006 (Patreon assigns a role in the support server, the bot checks it by user id)
- **Depends on**: 019 (merged): `services/PremiumChecker` and the default "nobody is premium" bean in `config/UsageConfig`.

## Goal
Replace the default `PremiumChecker` with one that returns true when the Discord user holds the configured Premium role in the configured support server. `UsageService` and `/usage` already call `PremiumChecker.isPremium`; this task changes no usage rule. The whole feature is off by default.

## Files to create or modify
| File | Change |
|---|---|
| `config/PremiumProperties.java` | **New.** `@ConfigurationProperties("rejuv.premium")` record. |
| `services/PremiumRoleGateway.java` | **New.** Port (no JDA types). |
| `services/PremiumLookupException.java` | **New.** Checked-free (`RuntimeException`) failure of a role lookup. |
| `services/PatreonRolePremiumChecker.java` | **New.** `PremiumChecker` implementation with a short cache. |
| `config/JdaPremiumRoleGateway.java` | **New.** JDA adapter of the port (same pattern as `JdaEmojiGateway`). |
| `src/main/resources/application.yaml` | Add the `rejuv.premium` block. |
| `.env.example` | Add `PREMIUM_ROLE_CHECK_ENABLED=false`, `PREMIUM_GUILD_ID=`, `PREMIUM_ROLE_ID=`. |
| Tests | **New**: `PatreonRolePremiumCheckerTest`, `JdaPremiumRoleGatewayTest`, `PremiumPropertiesTest`. |
| `docs/task-reports/020-patreon-role-check.md` | **New.** Task report. |

Do not edit `UsageConfig`, `UsageService`, `AnalyzeCommand`, `UsageCommand`, `messages.properties` or `JdaConfig`: the new checker replaces the default bean through `@Primary`, and no user-facing text is added. `docs/PROJECT_CONTEXT.md`: mark the update as pending integration.

## Interfaces / signatures

```java
// config/PremiumProperties.java
@ConfigurationProperties("rejuv.premium")
public record PremiumProperties(boolean enabled, Long guildId, Long roleId, Duration cacheTtl) {
    // compact constructor: cacheTtl null -> 2 minutes; when enabled, guildId and roleId must be non-null and > 0,
    // otherwise IllegalArgumentException (the app fails at startup with a clear message). Must not print any id of a secret.
}

// services/PremiumRoleGateway.java
public interface PremiumRoleGateway {
    /** @throws PremiumLookupException when the answer cannot be known (guild unavailable, Discord error, timeout). */
    boolean hasRole(long guildId, long roleId, long userId);
}

// services/PatreonRolePremiumChecker.java
@Component
@Primary
@ConditionalOnProperty(prefix = "rejuv.premium", name = "enabled", havingValue = "true")
public class PatreonRolePremiumChecker implements PremiumChecker { /* constructor: PremiumRoleGateway, PremiumProperties, Ticker */ }
```
`Ticker` is `com.github.benmanes.caffeine.cache.Ticker`; provide a constructor with `Ticker.systemTicker()` for production use and one taking a `Ticker` for tests.

## Behavior
1. **Flag off (default).** `rejuv.premium.enabled=false`: the new beans are not created, the default checker of Task 019 stays, and nothing about Discord roles is called. The app starts with the properties block empty.
2. **Flag on.** `isPremium(userId)` asks the gateway `hasRole(guildId, roleId, userId)` and caches the boolean per user id in a Caffeine cache (expire after write = `cacheTtl`, default 2 minutes, max 10,000 entries). Both true and false answers are cached, so a new subscriber is recognized within `cacheTtl`.
3. **Failures.** A `PremiumLookupException` (or any `RuntimeException` from the gateway) is logged once at WARN (user id, exception class; no stack trace of Discord internals, never a token), is **not cached**, and `isPremium` returns `false` (fail closed: a lookup problem never grants free unlimited analyses). `isPremium` never throws.
4. **Concurrency.** `UsageService` can call `isPremium` twice per command; concurrent lookups for the same user must produce one gateway call (use `Cache.get(key, loader)`; if the loader throws, do not cache).
5. **JDA adapter.** `JdaPremiumRoleGateway` is a bean only when the flag is on. It takes `ObjectProvider<JDA>`. `hasRole`: JDA absent (no bot token configured) or `jda.getGuildById(guildId) == null` -> `PremiumLookupException`. Otherwise `guild.retrieveMemberById(userId).complete()` (REST, blocking, with a bounded wait: use `submit().get(5, TimeUnit.SECONDS)`), then true when the member has a role with id `roleId`. A user who is not in the guild (Discord error `UNKNOWN_MEMBER` / `UNKNOWN_USER`) returns `false`, not an exception. Other `ErrorResponseException`s, timeouts and interruptions (restore the interrupt flag) become `PremiumLookupException`.
6. **Properties validation.** Enabled with a missing or non-positive `guildId` / `roleId` fails startup with `IllegalArgumentException`. Disabled never fails, even with a partial block.
7. **Logging.** No INFO line per lookup. WARN only on failures. Do not log role or guild ids at INFO.

## Out of scope
Patreon API calls or webhooks; a "Get Premium" button or message with the Patreon link on `analyze.limit_reached` (follow-up, it touches `AnalyzeCommand`); `/premium` command; granting roles; changing `UsageService`, limits or the `/usage` text; new Gateway intents; new dependencies; Flyway migrations.

## Tests
- `PatreonRolePremiumCheckerTest` (fake gateway counting calls, fake `Ticker`): true and false answers returned; second call within TTL does not hit the gateway; after the TTL it does; a failure returns false and a later successful lookup is not blocked by a cached failure; a `RuntimeException` that is not a `PremiumLookupException` also returns false; two threads asking for the same user trigger one gateway call; `isPremium` never throws.
- `JdaPremiumRoleGatewayTest` (Mockito on `JDA`, `Guild`, `Member`, `Role`): member with the role -> true; member without it -> false; `UNKNOWN_MEMBER` error -> false; other error and timeout -> `PremiumLookupException`; guild null -> exception; no JDA bean -> exception.
- `PremiumPropertiesTest`: disabled with empty ids is valid; enabled without ids or with 0 fails; default TTL is 2 minutes.
- A Spring-context assertion (extend an existing context test or add one using `ApplicationContextRunner`): with the flag off `PremiumChecker` is the default lambda bean; with the flag on and a stub gateway bean the `PatreonRolePremiumChecker` is the one injected into `UsageService`.

## Verification
`./mvnw clean verify` (on this machine `.\mvnw.cmd clean verify`, Docker running). Manual check only if the owner has a support server with the Premium role and a bot token (otherwise state "not run"): set `PREMIUM_ROLE_CHECK_ENABLED=true`, `PREMIUM_GUILD_ID`, `PREMIUM_ROLE_ID`, `USAGE_LIMITS_ENABLED=true USAGE_WEEKLY_FREE_ANALYSES=1`, restart, run `/usage` with a user that has the role (premium message) and one that does not. Never print `.env`.

## Acceptance criteria
- [ ] Compiles, all tests pass, counts recorded in the report
- [ ] Flag off changes nothing; flag on makes users with the role premium within `cacheTtl`
- [ ] Lookup failures fail closed, are not cached and never throw
- [ ] No new dependency, no user-facing text, no file outside the list changed
- [ ] Task report created; `docs/PROJECT_CONTEXT.md` update marked pending integration
- [ ] Follows AGENTS.md conventions
