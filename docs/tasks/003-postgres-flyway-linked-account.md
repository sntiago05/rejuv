# Task 003: PostgreSQL, Flyway and `linked_account`

- **Branch**: `feature/003-postgres-flyway`
- **Implementer**: Antigravity
- **Related ADRs**: ADR-0002, ADR-0005

## Goal
Give the bot a real persistence layer: PostgreSQL running through Docker Compose, Flyway as the only way to change the schema, and the first table `linked_account` with its JPA entity and repository. Remove the TEMP database auto-configuration exclusions left by Task 001, so the application now requires a reachable PostgreSQL at startup.

## Context (verified against the repository when this spec was written)
Do not repeat this discovery. If any fact below is false in your checkout, stop and ask.

- Plan entry: `docs/implementation-plan.md`, task 003, "PostgreSQL via Docker Compose, Flyway, `linked_account`; remove the TEMP auto-configuration exclusions from task 001", depends on 001, implementer Antigravity.
- Base: Tasks 001 and 002 are in `main` (HEAD `d6eece0`). This task edits a Task 002 test file (`MessagesIntegrationTest`), so your branch must contain Task 002.
- `pom.xml` (Spring Boot parent `4.1.1`, Java 21) already contains: `spring-boot-starter-data-jpa`, `org.postgresql:postgresql` (runtime), `spring-boot-starter-flyway`, `org.flywaydb:flyway-database-postgresql` (runtime), `spring-boot-starter-validation`, `spring-boot-starter-cache`, `caffeine`, `spring-boot-docker-compose` (optional), `spring-boot-starter-test` (test). No Testcontainers dependency exists.
- `src/main/resources/application.yaml` currently contains `spring.application.name`, `spring.main.web-application-type: none`, `spring.docker.compose.enabled: false`, the `spring.autoconfigure.exclude` block (marked `# TEMP: removed in task 003`) listing `org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration`, `org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration` and `org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration`, and the `bot.token` / `bot.dev-guild-id` properties.
- Existing source packages: `commands`, `config`, `services`. There is no `domain/`, `repositories/`, `src/main/resources/db/`, `src/test/resources/`, `compose.yaml` or `docker-compose.yml` yet. `.env.example` contains only `DISCORD_TOKEN=` and `DEV_GUILD_ID=`. `.gitignore` already ignores `.env`.
- Spring-context tests that exist today: `RejuvApplicationTests` (`@SpringBootTest`, `contextLoads`) and `MessagesIntegrationTest` (`@SpringBootTest(properties = {"bot.token=", "bot.dev-guild-id="})`). Both start a full context, so once the exclusions are removed they need a database. Baseline: 22 tests, 0 failures (Task 002 report).
- Data model source of truth, `docs/mvp-spec.md` "Data stored": `linked_account`: `discord_user_id` (PK), `account_id`, `linked_at`. `match_analysis` is created by Task 009, not here. `/unlink` (Task 005) deletes by `discord_user_id`.
- `account_id` is the Deadlock account ID (SteamID3, `steamId64 - 76561197960265728`), a 32-bit unsigned number that can exceed `Integer.MAX_VALUE`; a Discord user ID is a 64-bit snowflake. Both therefore map to `BIGINT` / `long`.
- ADR-0005: same setup locally and in production (Docker Compose); images must support ARM64.
- Environment note (`docs/PROJECT_CONTEXT.md`): Windows + PowerShell; the Maven wrapper may need `MAVEN_USER_HOME` pointed at a temporary junction (workaround from the Task 001 report, not part of this task). Docker Desktop is a documented prerequisite (`docs/setup-checklist.md`).
- The working tree of the maintainer's `main` currently shows line-ending-only modifications (CRLF) in several files. Work from a clean branch and do not commit line-ending-only changes.

## Files to create or modify
| File | Change |
|---|---|
| `pom.xml` | **Modify.** Add exactly two `test`-scope dependencies, versions managed by the Spring Boot BOM (no explicit `<version>`): `org.springframework.boot:spring-boot-testcontainers` and `org.testcontainers:testcontainers-postgresql`. Change nothing else. |
| `compose.yaml` | **Create.** PostgreSQL service, defined in "Docker Compose". |
| `.env.example` | **Modify.** Add the `POSTGRES_*` variables defined in "Configuration". |
| `src/main/resources/application.yaml` | **Modify.** Remove the TEMP `spring.autoconfigure.exclude` block; add datasource and JPA settings defined in "Configuration". |
| `src/main/resources/db/migration/V1__create_linked_account.sql` | **Create.** First Flyway migration. |
| `src/main/java/sntiago05/rejuv/domain/LinkedAccount.java` | **Create.** JPA entity. |
| `src/main/java/sntiago05/rejuv/repositories/LinkedAccountRepository.java` | **Create.** Spring Data repository. |
| `src/test/java/sntiago05/rejuv/TestcontainersConfiguration.java` | **Create.** Test configuration providing a PostgreSQL container as a service connection. |
| `src/test/java/sntiago05/rejuv/RejuvApplicationTests.java` | **Modify.** Only add `@Import(TestcontainersConfiguration.class)` (and its import). |
| `src/test/java/sntiago05/rejuv/services/MessagesIntegrationTest.java` | **Modify.** Only add `@Import(TestcontainersConfiguration.class)` (and its import). No assertion changes. |
| `src/test/java/sntiago05/rejuv/FlywayMigrationTest.java` | **Create.** Schema and migration tests. |
| `src/test/java/sntiago05/rejuv/repositories/LinkedAccountRepositoryTest.java` | **Create.** Repository tests against real PostgreSQL. |
| `README.md` | **Modify.** Replace the sentence saying the app disables database auto-configuration; document the database prerequisite and startup steps (see "Documentation"). |
| `docs/task-reports/003-postgres-flyway-linked-account.md` | **Create** at closeout (AGENTS.md). |
| `docs/PROJECT_CONTEXT.md` | **Update** at closeout (AGENTS.md), or mark pending integration if other task branches are active. |

Do not touch any other file (other source files, other tests, `.gitignore`, ADRs, other specs, `AGENTS.md`, existing reports, `docs/mvp-spec.md`, `docs/architecture.md`).

## Interfaces / signatures

### Migration `V1__create_linked_account.sql`
```sql
CREATE TABLE linked_account (
    discord_user_id BIGINT      PRIMARY KEY,
    account_id      BIGINT      NOT NULL,
    linked_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
```
Exactly this table: no extra columns, no unique constraint or index on `account_id`, no `match_analysis`. Flyway version `1`, description derived from the file name (`create linked account`).

### `LinkedAccount` (new)
```java
package sntiago05.rejuv.domain;

@Entity
@Table(name = "linked_account")
public class LinkedAccount {

    @Id
    @Column(name = "discord_user_id")
    private long discordUserId;

    @Column(name = "account_id", nullable = false)
    private long accountId;

    @Column(name = "linked_at", nullable = false)
    private Instant linkedAt;

    protected LinkedAccount() { }   // required by JPA

    public LinkedAccount(long discordUserId, long accountId, Instant linkedAt) { /* assign fields */ }

    public long getDiscordUserId();
    public long getAccountId();
    public Instant getLinkedAt();
}
```
The identifier is assigned by the application, so there is no `@GeneratedValue`. No setters, no Lombok, no JDA types. Updating a link is done by saving a new `LinkedAccount` with the same `discordUserId` (Spring Data `save` merges); do not add a mutator.

### `LinkedAccountRepository` (new)
```java
package sntiago05.rejuv.repositories;

public interface LinkedAccountRepository extends JpaRepository<LinkedAccount, Long> { }
```
No custom query methods: `findById`, `save`, `deleteById` cover Tasks 005 and 006. Later tasks add what they need.

### `TestcontainersConfiguration` (test, new)
```java
package sntiago05.rejuv;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:17"));
    }
}
```
Use the `PostgreSQLContainer` class that belongs to the Testcontainers version managed by Spring Boot 4.1.1 (with `org.testcontainers:testcontainers-postgresql` this is `org.testcontainers.postgresql.PostgreSQLContainer`, non-generic). `@ServiceConnection` is `org.springframework.boot.testcontainers.service.connection.ServiceConnection`. The image tag must match the one in `compose.yaml` (add a short comment saying so). If either artifact does not resolve from the Boot BOM, or the class names differ, stop and ask; do not add an explicit version or another test library.

## Configuration

### `application.yaml` (resulting content, keep existing keys and the `bot:` block unchanged)
```yaml
spring:
  application:
    name: rejuv
  main:
    web-application-type: none
  docker:
    compose:
      enabled: false
  datasource:
    url: jdbc:postgresql://${POSTGRES_HOST:localhost}:${POSTGRES_PORT:5432}/${POSTGRES_DB:rejuv}
    username: ${POSTGRES_USER:rejuv}
    password: ${POSTGRES_PASSWORD:}
  jpa:
    hibernate:
      ddl-auto: validate

bot:
  token: ${DISCORD_TOKEN:}
  dev-guild-id: ${DEV_GUILD_ID:}
```
- The whole `spring.autoconfigure.exclude` block (and its TEMP comment) is removed. Do not add other exclusions.
- `spring.docker.compose.enabled: false` stays: the database is started explicitly with `docker compose`, not by Spring Boot.
- `ddl-auto: validate`: Hibernate never changes the schema; Flyway is the only schema authority (AGENTS.md). Do not set `spring.flyway.*` (defaults: enabled, `classpath:db/migration`, uses the datasource).
- No default password: an empty `POSTGRES_PASSWORD` makes authentication fail at startup with the driver's error. Never log the datasource URL with credentials or the password.

### `.env.example` (append, keep existing lines)
```
POSTGRES_DB=rejuv
POSTGRES_USER=rejuv
POSTGRES_PASSWORD=
```
`POSTGRES_HOST` and `POSTGRES_PORT` are optional overrides with the defaults above and are not added to the file.

## Docker Compose
Create `compose.yaml` at the repository root with one service, `postgres`, and one named volume:

- `image: postgres:17` (multi-arch, includes ARM64 per ADR-0005).
- `restart: unless-stopped`.
- Environment: `POSTGRES_DB: ${POSTGRES_DB:-rejuv}`, `POSTGRES_USER: ${POSTGRES_USER:-rejuv}`, `POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?Set POSTGRES_PASSWORD in .env}` (compose must refuse to start without a password).
- Ports: `"127.0.0.1:${POSTGRES_PORT:-5432}:5432"` (bound to localhost only).
- Volume `postgres-data` mounted at `/var/lib/postgresql/data`.
- Healthcheck using `pg_isready` with the configured user and database (remember `$$` escaping in compose), interval 5s, timeout 3s, retries 10.

No application service, Dockerfile or Caddy in this task (deployment is not part of Phase 1 tasks 001-021 yet). Compose reads `.env` from the repository root automatically.

## Behavior
1. **Local startup order.** The developer copies `.env.example` to `.env`, sets `POSTGRES_PASSWORD`, runs `docker compose up -d postgres`, waits until the container is healthy, exports the same `POSTGRES_*` variables (plus Discord ones) in the shell and runs `.\mvnw.cmd spring-boot:run`. The app does not read `.env` itself (unchanged from Task 001).
2. **Application startup.** Spring Boot builds the datasource from `application.yaml`, Flyway applies pending migrations (`V1` on an empty database), then Hibernate validates the entity mapping against the resulting schema. The application now **requires a reachable PostgreSQL at startup**; if the database is down or the password is wrong, startup fails (fail fast). This is intended.
3. **Idempotent migrations.** Restarting against an already migrated database applies nothing and succeeds. An applied migration is never edited; later schema changes are new `V<n>__description.sql` files.
4. **Schema.** After `V1`, table `linked_account` exists with the columns, types, nullability, primary key and default defined above.
5. **Persistence.** `LinkedAccountRepository.save` inserts a new row, or updates `account_id` and `linked_at` when a row with the same `discord_user_id` exists (relink replaces the account, no duplicate row). `findById` returns the row or empty. `deleteById` removes only that row.
6. **No user-facing text** is added or changed, so `messages.properties` is untouched. No command is added or changed; business logic (parsing, API verification, upsert policy) belongs to Task 005.
7. **Tests** start PostgreSQL through Testcontainers (Docker must be running) and do not depend on the Compose database or on the developer's `.env`.

## Tests
JUnit 5 and AssertJ. Every Spring test uses `@SpringBootTest(properties = {"bot.token=", "bot.dev-guild-id="})` and `@Import(TestcontainersConfiguration.class)` (the empty bot properties keep the `JDA` bean out even if the shell has Discord variables). Use `JdbcTemplate` from the context for schema assertions.

### `FlywayMigrationTest` (5 tests)
1. `appliesVersion1Successfully`: `flyway_schema_history` contains a row with `version = '1'` and `success = true`.
2. `linkedAccountHasExpectedColumns`: through `information_schema.columns`, `discord_user_id` and `account_id` are `bigint` and `NOT NULL`, `linked_at` is `timestamp with time zone` and `NOT NULL`; there are exactly these three columns.
3. `discordUserIdIsThePrimaryKey`: the primary key of `linked_account` consists of exactly the column `discord_user_id`.
4. `linkedAtDefaultsToNow`: inserting a row with only `discord_user_id` and `account_id` yields a non-null `linked_at` close to the current time (assert within a generous window, for example 1 minute).
5. `enforcesConstraints`: inserting a duplicate `discord_user_id` throws `DataIntegrityViolationException`; inserting a null `account_id` throws `DataIntegrityViolationException`.
Each test cleans the rows it created (delete in the test or `@BeforeEach`).

### `LinkedAccountRepositoryTest` (4 tests)
`@BeforeEach` runs `repository.deleteAll()`.
1. `savesAndReadsBackAllFields`: save `new LinkedAccount(123456789012345678L, 4294967295L, Instant.now().truncatedTo(ChronoUnit.MICROS))`; `findById` returns equal values for all three fields (proves `long` mapping beyond `int` range and `Instant` round trip; PostgreSQL stores microseconds).
2. `savingSameDiscordUserIdReplacesTheLinkedAccount`: save id `1` with account `100`, then id `1` with account `200` and a later `linkedAt`; `count()` is `1` and `findById(1)` has account `200`.
3. `deleteByIdRemovesOnlyThatUser`: save two users, delete one; the other remains.
4. `findByIdReturnsEmptyWhenNotLinked`: `findById` of an unknown id is empty.

### Existing tests
`RejuvApplicationTests` and `MessagesIntegrationTest` keep every test and assertion; only the `@Import` is added. They must pass (they now also prove that the full context starts with the real datasource, Flyway and `ddl-auto: validate`). All other existing tests are unchanged.

Expected totals after this task: 22 existing + 9 new = 31 tests, 0 failures.

## Verification
Docker Desktop must be running (Testcontainers). On Windows use the wrapper form `.\mvnw.cmd` (with the `MAVEN_USER_HOME` workaround from the Task 001 report if needed).
1. `.\mvnw.cmd clean verify` passes.
2. Manual check (needs `.env` with a password and the `POSTGRES_*` variables exported): `docker compose up -d postgres`, wait for healthy, then run the app with `.\mvnw.cmd spring-boot:run` (no Discord credentials needed; the JDA bean is skipped when they are empty). The log must show Flyway applying `V1` and the context starting. Then `docker compose exec postgres psql -U <user> -d <db> -c "\d linked_account"` shows the three columns. Run the app a second time: Flyway reports the schema is up to date. If Docker cannot be used, say so in the report; do not claim the manual check.
3. `git grep -n "autoconfigure.exclude"` returns nothing in `src/main`.
4. `git diff --check` and a review of the final diff for unrelated changes (including line-ending-only changes).

## Documentation
- `README.md`: replace the sentence "The app is non-web and disables database-related auto-configuration..." with the new reality (needs PostgreSQL), add Docker to the prerequisites, and add the `.env` + `docker compose up -d postgres` + `POSTGRES_*` export steps (PowerShell syntax, consistent with the existing instructions), plus that `.\mvnw.cmd verify` needs Docker running. Keep it short.
- Task report `docs/task-reports/003-postgres-flyway-linked-account.md`, following the format of `docs/task-reports/002-i18n-and-help.md` (status, summary, important files, verification actually run with results, deviations, known issues).
- `docs/PROJECT_CONTEXT.md`: at closeout, record only verified facts (database layer done, TEMP exclusions removed, `linked_account` exists, Testcontainers needed for `verify`, next eligible task 004). If other task branches are active, mark the update pending integration instead (AGENTS.md).
- No ADR is added: PostgreSQL and Compose are already decided (ADR-0002, ADR-0005); Testcontainers for tests is recorded here and in the report.

## Out of scope
- `match_analysis` table and any other table (Task 009), indexes or unique constraints beyond the primary key, `/unlink` cascade logic.
- `/link`, `/unlink`, `/profile` or any command, `LinkService`/business logic, input parsing, Steam ID conversion (Task 005).
- `DeadlockApiClient`, caching, AI client (Tasks 004, 008).
- A Dockerfile, an application service in `compose.yaml`, Caddy, backups, production deployment, CI configuration.
- Loading `.env` from Spring (`spring.config.import`), Spring Boot Docker Compose auto-start (stays disabled), removing the optional `spring-boot-docker-compose` dependency, profiles, changing Hikari pool settings.
- Any `messages.properties` change, any change to `CommandRouter`, `PingCommand`, `HelpCommand`, `Messages`, `JdaConfig`.
- Test slices (`@DataJpaTest`) and their extra starters, H2 or any other embedded database.
- New dependencies other than the two test dependencies listed.

## Dependencies and constraints
- Conventions (AGENTS.md): constructor injection, `final` fields, no Lombok, `domain/` for entities, `repositories/` for Spring Data, database changes only through Flyway migrations, never edit an applied migration, secrets only in `.env`/environment (never read or print `.env`), JUnit 5 + AssertJ.
- Surefire only runs classes named `*Test`/`Tests`; name every test class that way (no `*IT`; failsafe is not configured).
- Parallel work: Task 004 and Task 008 are independent of this task. This task edits `pom.xml` and `application.yaml`; if another active branch touches them, flag the merge risk in the report.

## Acceptance criteria
- [ ] Compiles, all tests pass (`.\mvnw.cmd clean verify`, 31 tests expected)
- [ ] Unit tests for the service logic (no service logic in this task; schema and repository tests cover the new code)
- [ ] Follows AGENTS.md conventions
- [ ] The TEMP `spring.autoconfigure.exclude` block is gone from `application.yaml`; datasource and `ddl-auto: validate` are configured exactly as specified.
- [ ] `compose.yaml` defines only the `postgres` service as specified; it refuses to start without `POSTGRES_PASSWORD`.
- [ ] `V1__create_linked_account.sql` matches the specified DDL; Flyway applies it on an empty database.
- [ ] `LinkedAccount` and `LinkedAccountRepository` match the specified signatures; `ddl-auto: validate` passes against the migrated schema.
- [ ] `FlywayMigrationTest` (5) and `LinkedAccountRepositoryTest` (4) exist; `RejuvApplicationTests` and `MessagesIntegrationTest` pass with only the `@Import` added.
- [ ] `pom.xml` differs only by the two test dependencies, with no explicit versions.
- [ ] `.env.example` has the three new variables; no secret is committed; `.env` was never read or printed.
- [ ] README updated; task report created; `docs/PROJECT_CONTEXT.md` updated (or marked pending integration); final diff has no unrelated files.

## Decisions and open questions
Decisions taken in this spec (supported by the repository):

1. **Testcontainers for tests.** ADR-0002 chose PostgreSQL and rejected H2 for weaker fidelity; tests must exercise real PostgreSQL behavior (constraints, `timestamptz`, Flyway). Docker Desktop is already a documented prerequisite. This is the only new dependency, test-scoped, authorized here. Consequence: `verify` needs Docker running.
2. **Entity and repository live in this task**, not only the migration, so `ddl-auto: validate` actually checks the mapping and Task 005 can build on them. No service and no custom queries.
3. **`BIGINT` for both IDs**, `TIMESTAMPTZ` for `linked_at`, as justified in Context.
4. **No unique constraint on `account_id`.** `docs/mvp-spec.md` defines only `discord_user_id` as PK and requires no account verification; whether one Steam account may be linked by several Discord users is a product decision for later (a new migration can add the constraint).
5. **PostgreSQL 17** to avoid the data-directory layout change of the newer images; the tag is pinned identically in `compose.yaml` and the test configuration.
6. **One variable set `POSTGRES_*`** shared by Compose and the application, no default password.
7. **Compose publishes the port on `127.0.0.1` only.**

Unresolved / assumptions (owner to confirm):

1. **Docker required for `verify`.** If you prefer `verify` to run without Docker, the alternative is H2 or a skipped DB test, both weaker; this spec chooses Testcontainers.
2. **Testcontainers coordinates.** Assumed `org.springframework.boot:spring-boot-testcontainers` and `org.testcontainers:testcontainers-postgresql` are managed by Boot 4.1.1 (could not be checked against the BOM from the planning environment). The implementer must stop and ask if they do not resolve.
3. **Shared `main` working tree** has uncommitted line-ending-only changes; they are not part of this task.
4. **Deployment compose** (bot service, Oracle Cloud) is not covered by any task 001-021; to be planned separately before the day-4 deploy.
