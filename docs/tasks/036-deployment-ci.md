# Task 036: Container image, production compose file and CI

- **Branch**: `feature/036-deployment-ci`
- **Implementer**: Antigravity
- **Related ADRs**: ADR-0005 (Docker Compose on Oracle Cloud ARM64), ADR-0017 (zstd-jni native library)
- **Depends on**: none (infrastructure files only). Independent of 029, 030, 017, 020, 021.

## Goal
Make the bot deployable the way ADR-0005 decided: a reproducible multi-architecture-friendly Docker image, a production compose file (bot + PostgreSQL), a short deployment guide, and a CI workflow that runs `./mvnw verify` and builds the image on every pull request. It also closes the open risk from Task 026: the chart renderer needs fonts that a plain JRE image does not have.

## Files to create or modify
| File | Change |
|---|---|
| `Dockerfile` | **New.** Multi-stage build. |
| `.dockerignore` | **New.** |
| `compose.prod.yaml` | **New.** Production stack. `compose.yaml` (development, Postgres only) is **not** modified. |
| `.github/workflows/ci.yml` | **New.** |
| `docs/deployment.md` | **New.** Deployment guide (English). |
| `docs/task-reports/036-deployment-ci.md` | **New.** Task report. |

No Java, test, resource, `pom.xml`, `.env.example` or `application.yaml` changes. `.gitignore` is changed only if it would ignore one of the new files (state it in the report). `docs/PROJECT_CONTEXT.md`: mark the update as pending integration.

## Behavior

### Dockerfile
1. **Stage `build`**: base `eclipse-temurin:21-jdk` (a pinned tag with a specific minor version, not `latest`; both `linux/amd64` and `linux/arm64` must exist for it). Copy `pom.xml`, `mvnw` and `.mvn/` first and resolve dependencies in their own layer (`./mvnw -B -q dependency:go-offline`), then copy `src/` and run `./mvnw -B -DskipTests package`. Tests are skipped in the image build because CI runs them. Make `mvnw` executable inside the image (`chmod +x`) and tolerate Windows line endings (`sed -i 's/\r$//' mvnw`).
2. **Stage `extract`** (or inside `build`): produce Spring Boot layers from the built jar with `java -Djarmode=tools -jar <jar> extract --layers --destination <dir>` (Boot 4 tools mode). If the layout of the Boot 4 jar makes this unavailable, fall back to copying the single fat jar and say so in the report.
3. **Stage `runtime`**: base `eclipse-temurin:21-jre` (**glibc/Ubuntu, not Alpine**: `zstd-jni` native libraries from Task 023 do not work on musl). Install `fontconfig` and `fonts-dejavu-core` with `apt-get install --no-install-recommends` and clean the apt lists in the same layer (the Task 026 chart renderer needs a font; a missing font must not be silently accepted). Create a non-root user and run as it. Copy the layers. `ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -Djava.awt.headless=true"`. `ENTRYPOINT` runs the Boot launcher (`java org.springframework.boot.loader.launch.JarLauncher`) or `-jar`, matching what stage 2 produced. No secrets in any `ENV`, `ARG` or `COPY`; no `HEALTHCHECK` (the app has no web endpoint yet).
4. The image must build on both `amd64` and `arm64` hosts without changes (no architecture-specific URLs or binaries).

### `.dockerignore`
Exclude at least: `.git`, `.github`, `.idea`, `target`, `docs`, `.env`, `.env.*` **except** `.env.example`, `.claude`, `.agents`, `*.md` (except nothing is required from them), `**/*.log`. `.env` must never end up in the build context.

### `compose.prod.yaml`
- Service `postgres`: same image tag, volume and healthcheck as `compose.yaml`; **no published ports**; `restart: unless-stopped`; password required through `${POSTGRES_PASSWORD:?Set POSTGRES_PASSWORD in .env}`.
- Service `bot`: `build: .`, `image: rejuv:local`, `restart: unless-stopped`, `env_file: .env`, `environment` overrides `POSTGRES_HOST: postgres` and `POSTGRES_PORT: 5432`, `depends_on: postgres: condition: service_healthy`, `mem_limit: 1g` (note in the guide that this is a starting value), `stop_grace_period: 20s`, log rotation (`json-file`, `max-size: 10m`, `max-file: "3"`). It publishes **no** ports now (port mapping for the web report arrives with the web tasks; leave a commented example for `127.0.0.1:8080:8080`).
- The file must pass `docker compose -f compose.prod.yaml config` (with a dummy `POSTGRES_PASSWORD` in the environment, never a real `.env`).

### CI (`.github/workflows/ci.yml`)
- Triggers: `pull_request` and `push` to `main`. `permissions: contents: read`. `concurrency` group per ref cancelling older runs.
- Job `verify` on `ubuntu-latest`: `actions/checkout`, `actions/setup-java` (Temurin 21, Maven cache), `chmod +x mvnw`, `./mvnw -B verify` (Docker is available on the runner, so Testcontainers works). Upload `target/surefire-reports` as an artifact only on failure.
- Job `image` (needs nothing, runs in parallel): `docker build -t rejuv:ci .` to prove the Dockerfile builds; no registry login, no push.
- Pin third-party actions to a full major tag (`@v4`, `@v5`); no secrets used; do not echo the environment. No deploy step in this task.

### `docs/deployment.md`
English, concise, copy-paste commands for: server prerequisites (Oracle Cloud Always Free ARM64 Ubuntu, Docker Engine and Compose plugin, a non-root user in the `docker` group); getting the code; creating `.env` from `.env.example` (list every required variable by name: `DISCORD_TOKEN`, `DEV_GUILD_ID`, `POSTGRES_PASSWORD`, `ANTHROPIC_API_KEY`, usage and any variables present in `.env.example` at that time, **without any value**); first start (`docker compose -f compose.prod.yaml up -d --build`); logs; update procedure; database backup and restore with `pg_dump`/`psql` through `docker compose exec`; Server Members Intent reminder (Discord Developer Portal); the first emoji sync takes minutes; what is intentionally missing (HTTPS/Caddy, domain, monitoring). Do not describe features that do not exist.

## Out of scope
Caddy or any HTTPS setup; a domain; image publishing to a registry; automatic deploy to the server; Terraform or Oracle account provisioning; metrics or alerting; changing application code or configuration; the web server (task 017).

## Verification
- `./mvnw clean verify` still passes (nothing in `src/` changes; record the counts).
- If Docker is available: `docker build -t rejuv:local .` succeeds; run `docker run --rm --entrypoint sh rejuv:local -c "id -u && fc-list | head -3 && java -version"` (non-root user id, at least one font, Java 21). Then a font check inside the image with a throwaway single-file Java program kept **outside the repository** (for example in the scratchpad) mounted read-only and run with `java Check.java`: create a `BufferedImage`, draw a string with `new Font("SansSerif", Font.PLAIN, 14)` and print the family name that was resolved. Record the output.
- `docker compose -f compose.prod.yaml config` (with `POSTGRES_PASSWORD=dummy` in the shell) succeeds and shows no published Postgres port.
- Do **not** start the bot against Discord and do not read `.env`. If Docker is not available, say exactly which of these checks were not run.
- CI cannot be run locally; validate the YAML syntax (for example with `docker run --rm -v ...` of a YAML linter, or by careful review) and say how it was validated. It will run for real on the pull request: write in the report what to check there.

## Acceptance criteria
- [ ] Image builds (or the report states why it could not be verified), runs as non-root, contains a usable font, uses a glibc base
- [ ] `.env` and other secrets cannot enter the build context or the image
- [ ] `compose.prod.yaml` validates and exposes no database port
- [ ] CI workflow runs `verify` and the image build, with least-privilege permissions and no secrets
- [ ] `docs/deployment.md` contains no secret values and no claims about missing features
- [ ] No application code, test or dependency changed; `compose.yaml` untouched
- [ ] Task report created; `docs/PROJECT_CONTEXT.md` update marked pending integration
- [ ] Follows AGENTS.md conventions
