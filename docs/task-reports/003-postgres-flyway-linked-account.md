# Task 003: PostgreSQL, Flyway and `linked_account`

## Status

Completed and fully verified. Testcontainers tests (31 tests) and manual Docker Compose + Spring Boot + psql schema checks passed cleanly.

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
- Dependency coordinates confirmed from the local Maven cache: `spring-boot-dependencies-4.1.1` manages `spring-boot-testcontainers` (4.1.1) and imports `testcontainers-bom` 2.0.5, which manages `org.testcontainers:testcontainers-postgresql`.
- The `postgres:17` tag is pinned in `compose.yaml` and `TestcontainersConfiguration`, each with a comment pointing at the other.
- New files use CRLF in the working tree like the existing files (`core.autocrlf=true`; index is LF).

## Verification

All required verifications completed successfully:

- `./mvnw clean verify` with Testcontainers (`postgres:17`, `testcontainers/ryuk:0.14.0`): BUILD SUCCESS. Total tests: 31, Failures: 0, Errors: 0, Skipped: 0.
  - `LinkedAccountRepositoryTest`: 4 tests passed
  - `FlywayMigrationTest`: 5 tests passed
  - `MessagesTest`: 5 tests passed
  - `MessagesIntegrationTest`: 5 tests passed
  - `RejuvApplicationTests`: 1 test passed
  - `HelpCommandTest`: 5 tests passed
  - `PingCommandTest`: 3 tests passed
  - `CommandRouterTest`: 3 tests passed
- Manual Docker Compose check:
  - `docker compose up -d postgres`: container `rejuv-postgres-1` started and became healthy.
  - `./mvnw spring-boot:run` (run 1): Flyway migrated public schema to version `1 - create linked account`, Hibernate validated schema against entity mappings, and `RejuvApplication` started in 2.027s.
  - `docker compose exec postgres psql -U rejuv -d rejuv -c "\d linked_account"`: verified columns `discord_user_id bigint not null`, `account_id bigint not null`, `linked_at timestamp with time zone not null default now()`, and primary key `linked_account_pkey (discord_user_id)`.
  - `./mvnw spring-boot:run` (run 2): idempotent check logged `Schema "public" is up to date. No migration necessary.` and started cleanly.
- `git grep -n "autoconfigure.exclude" -- src/main`: 0 matches.
- `git diff --check`: no issues.
- `.env` was never read or committed to version control.

## Deviations

- Branch is `feature/003-postgres-flyway-linked-account` (pre-existing, clean, at `main` 9aa25b7), not `feature/003-postgres-flyway` as in the spec. The owner chose to proceed on it; rename with `git branch -m feature/003-postgres-flyway` if needed. Its upstream is configured as `origin/main`; set the upstream explicitly before pushing.
- Minor additions inside the specified tests: `appliesVersion1Successfully` also checks the description `create linked account`; `savingSameDiscordUserIdReplacesTheLinkedAccount` also checks the later `linkedAt`; `FlywayMigrationTest` cleans the table in `@BeforeEach` and `@AfterEach`. Short sync comment added in `compose.yaml`.

## Known Issues

- `verify` now requires Docker running (Testcontainers), as decided in the spec.
- `RejuvApplicationTests` keeps its plain `@SpringBootTest` (the spec allows only the `@Import`), so if `DISCORD_TOKEN` and `DEV_GUILD_ID` are exported in the shell running `verify`, that test creates the JDA bean and tries to log in to Discord (behavior unchanged from Task 001).

## Merge Risks

- This task edits `pom.xml` and `application.yaml`. No other unmerged task branch existed when this was written (Tasks 001 and 002 are merged); Tasks 004 and 008 are likely to touch the same files if started in parallel.
