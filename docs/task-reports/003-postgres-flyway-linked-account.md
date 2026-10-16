# Task 003: PostgreSQL, Flyway and `linked_account`

## Status

Implemented; verification incomplete. The required `.\mvnw.cmd clean verify` with Testcontainers and the manual Docker Compose startup check have **not** been run yet (see Verification). Do not merge until they pass on the Windows machine. Not committed.

## Summary

- Added PostgreSQL 17 through `compose.yaml` (`postgres` service only, named volume `postgres-data`, port published on `127.0.0.1` only, `pg_isready` healthcheck, refuses to start without `POSTGRES_PASSWORD`).
- Removed the TEMP `spring.autoconfigure.exclude` block from `application.yaml` and configured the datasource from `POSTGRES_*` variables with `spring.jpa.hibernate.ddl-auto: validate`. The app now needs a reachable PostgreSQL at startup (fail fast).
- Added Flyway migration `V1__create_linked_account.sql` with exactly the specified table.
- Added the `LinkedAccount` entity (`domain/`) and `LinkedAccountRepository` (`repositories/`) with the specified signatures. No business logic.
- Added `TestcontainersConfiguration` (`postgres:17`, `@ServiceConnection`), `FlywayMigrationTest` (5 tests) and `LinkedAccountRepositoryTest` (4 tests); added only `@Import(TestcontainersConfiguration.class)` to `RejuvApplicationTests` and `MessagesIntegrationTest`.
- Added the two BOM-managed test dependencies to `pom.xml`; documented the database setup in `README.md`; added `POSTGRES_*` to `.env.example`.

## Important Files

- `pom.xml`
- `compose.yaml`
- `.env.example`
- `src/main/resources/application.yaml`
- `src/main/resources/db/migration/V1__create_linked_account.sql`
- `src/main/java/sntiago05/rejuv/domain/LinkedAccount.java`
- `src/main/java/sntiago05/rejuv/repositories/LinkedAccountRepository.java`
- `src/test/java/sntiago05/rejuv/TestcontainersConfiguration.java`
- `src/test/java/sntiago05/rejuv/FlywayMigrationTest.java`
- `src/test/java/sntiago05/rejuv/repositories/LinkedAccountRepositoryTest.java`
- `src/test/java/sntiago05/rejuv/RejuvApplicationTests.java`
- `src/test/java/sntiago05/rejuv/services/MessagesIntegrationTest.java`
- `README.md`

## Decisions

- Followed the spec as written: `BIGINT` ids, `TIMESTAMPTZ` `linked_at`, no extra columns/indexes/unique constraints, Flyway as the only schema authority, `ddl-auto: validate`, no `spring.flyway.*` settings.
- Dependency coordinates confirmed from the local Maven cache: `spring-boot-dependencies-4.1.1` manages `spring-boot-testcontainers` (4.1.1) and imports `testcontainers-bom` 2.0.5, which manages `org.testcontainers:testcontainers-postgresql`. The 2.0.5 jars are not cached yet, so the class `org.testcontainers.postgresql.PostgreSQLContainer` has not been compiled against here; the first Windows build will download it.
- The `postgres:17` tag is pinned in `compose.yaml` and `TestcontainersConfiguration`, each with a comment pointing at the other.
- New files use CRLF in the working tree like the existing files (`core.autocrlf=true`; index is LF).

## Verification

Implemented from a session that could not run commands in a Windows terminal, and whose sandboxes cannot reach Maven Central or Docker Hub. Results below are only what actually ran.

Not run (required by the spec, pending):

- `.\mvnw.cmd clean verify` with Testcontainers (Docker Desktop). Expected 31 tests.
- Manual check: `docker compose up -d postgres`, `.\mvnw.cmd spring-boot:run` twice, `docker compose exec postgres psql ... -c "\d linked_account"`.

Run (auxiliary, not a substitute for the above):

- Offline Maven build of the real project (Linux sandbox, Maven 3.10.0, Java 21, local repository copied from `~/.m2`): fails only at dependency resolution because `org.testcontainers:testcontainers-postgresql:2.0.5` and `org.testcontainers:testcontainers:2.0.5` are not cached. This also confirms the BOM supplies the versions.
- Harness build: a scratch copy of the project with the two Testcontainers dependencies removed and `TestcontainersConfiguration` replaced by an empty test configuration, with the datasource pointed at a local **PostgreSQL 16.15** server through `POSTGRES_*` variables. `mvn -o clean verify`: BUILD SUCCESS, 31 tests, 0 failures, 0 errors, 0 skipped (`FlywayMigrationTest` 5, `LinkedAccountRepositoryTest` 4, `MessagesIntegrationTest` 5, `RejuvApplicationTests` 1, others unchanged). Log shows Flyway `Migrating schema "public" to version "1 - create linked account"` on the empty database, Hibernate validation passing, and the second test context reporting `Schema "public" is up to date`.
- Harness startup check (same PostgreSQL 16 server, fresh database, packaged jar, no Discord credentials): first run applied V1 and logged `Started RejuvApplication`; second run logged `Schema "public" is up to date. No migration necessary.`; `\d linked_account` shows the three columns (`bigint not null`, `bigint not null`, `timestamp with time zone not null default now()`) and `linked_account_pkey` on `discord_user_id`. With the database unreachable, startup failed with a connection-refused error (exit code 1).
- `docker compose config` (Linux sandbox): without `POSTGRES_PASSWORD` it fails with `required variable POSTGRES_PASSWORD is missing a value: Set POSTGRES_PASSWORD in .env`; with a dummy password it renders `postgres:17`, `127.0.0.1:5432->5432`, volume `postgres-data` and the healthcheck. The container itself was not started.
- `git grep -n "autoconfigure.exclude" -- src/main`: no matches.
- `git diff --check`: no issues; new untracked files checked separately for trailing whitespace and final newline.
- Final diff reviewed against the spec's file list: no other files changed; no line-ending-only changes.
- `.env` was never read or printed.

## Deviations

- Branch is `feature/003-postgres-flyway-linked-account` (pre-existing, clean, at `main` 9aa25b7), not `feature/003-postgres-flyway` as in the spec. The owner chose to proceed on it; rename with `git branch -m feature/003-postgres-flyway` if needed. Its upstream is configured as `origin/main`; set the upstream explicitly before pushing.
- Minor additions inside the specified tests: `appliesVersion1Successfully` also checks the description `create linked account`; `savingSameDiscordUserIdReplacesTheLinkedAccount` also checks the later `linkedAt`; `FlywayMigrationTest` cleans the table in `@BeforeEach` and `@AfterEach`. Short sync comment added in `compose.yaml`.

## Known Issues

- `verify` now requires Docker running (Testcontainers), as decided in the spec.
- `RejuvApplicationTests` keeps its plain `@SpringBootTest` (the spec allows only the `@Import`), so if `DISCORD_TOKEN` and `DEV_GUILD_ID` are exported in the shell running `verify`, that test creates the JDA bean and tries to log in to Discord (behavior unchanged from Task 001).
- The harness ran PostgreSQL 16, not 17; the real tests will run on `postgres:17`.
- Housekeeping left by this session: transfer archives in `target/m2xfer/` (ignored, removed by `.\mvnw.cmd clean`), and in the main repository `.git/stale-index-lock-003` (0 bytes) and `.git/objects/e6/tmp_obj_EudRno` from a git check; both can be deleted.

## Merge Risks

- This task edits `pom.xml` and `application.yaml`. No other unmerged task branch existed when this was written (Tasks 001 and 002 are merged); Tasks 004 and 008 are likely to touch the same files if started in parallel.
