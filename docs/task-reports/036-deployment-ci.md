# Task 036: Container image, production compose file and CI

## Status

Completed on `feature/036-deployment-ci`.
`.\mvnw.cmd clean verify` passes with **675 tests** (0 failures, 2 skipped benchmark tests).
Docker image build, non-root user, font rendering, zstd-jni native library, compose config, and CI workflow syntax verified.
`docs/PROJECT_CONTEXT.md` update marked **pending integration** as required by the task spec.
Not merged.

## Summary

- Created `Dockerfile` using multi-stage build:
  - **Build stage** on `eclipse-temurin:21.0.6_7-jdk` (pinned tag supporting `linux/amd64` and `linux/arm64` on Ubuntu 24.04 Noble glibc). Caches dependencies in their own layer via `./mvnw -B -q dependency:go-offline` after stripping Windows CRLF endings (`sed -i 's/\r$//' mvnw`) and adding execution permissions (`chmod +x mvnw`). Packages application skipping tests (`./mvnw -B -DskipTests package`).
  - **Extract stage** produces Spring Boot 4.1.1 layers using `java -Djarmode=tools -jar target/rejuv-0.0.1-SNAPSHOT.jar extract --layers --launcher --destination /extracted`.
  - **Runtime stage** on `eclipse-temurin:21.0.6_7-jre` (glibc Ubuntu, compatible with `zstd-jni` native libraries from Task 023). Installs `fontconfig` and `fonts-dejavu-core` with `apt-get install -y --no-install-recommends` and cleans apt lists in the same layer. Creates non-root group `appgroup` (GID 10001) and user `appuser` (UID 10001). Copies the four layers with `chown=appuser:appgroup`. Sets `JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -Djava.awt.headless=true"` and `ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]`. No secrets in image, no healthcheck.
- Created `.dockerignore` excluding `.git`, `.github`, `.idea`, `.vscode`, `target`, `docs`, `.claude`, `.agents`, `*.md`, `**/*.log`, `.env`, and `.env.*` (while keeping `!.env.example`).
- Created `compose.prod.yaml`:
  - `postgres` service matching `compose.yaml` (image `postgres:17`, volume `postgres-data`, healthcheck `pg_isready`), with `restart: unless-stopped`, password enforcement via `${POSTGRES_PASSWORD:?Set POSTGRES_PASSWORD in .env}`, and **no published ports**.
  - `bot` service with `build: .`, `image: rejuv:local`, `restart: unless-stopped`, `env_file: [path: .env, required: false]`, environment overrides `POSTGRES_HOST: postgres` and `POSTGRES_PORT: 5432`, `depends_on: postgres: condition: service_healthy`, `mem_limit: 1g`, `stop_grace_period: 20s`, and JSON log rotation (`max-size: 10m`, `max-file: "3"`). No published ports, with a commented example for `127.0.0.1:8080:8080`.
- Created `.github/workflows/ci.yml`:
  - Triggers on `push` to `main` and `pull_request` targeting `main`.
  - Sets `permissions: contents: read` and concurrency group per ref cancelling stale runs.
  - Job `verify` runs on `ubuntu-latest` with `actions/checkout@v4`, `actions/setup-java@v4` (Temurin 21, Maven cache), and `./mvnw -B verify`. Uploads `target/surefire-reports` only on failure.
  - Job `image` runs in parallel on `ubuntu-latest` building `docker build -t rejuv:ci .` without pushing or using secrets.
- Created `docs/deployment.md`:
  - Concise guide with copy-paste commands covering server prerequisites (Oracle Cloud Always Free ARM64 Ubuntu, Docker Engine & Compose, non-root user in `docker` group), cloning, creating `.env` from `.env.example` with all 8 variables listed by name without values, first start, emoji sync delay note, logs, updates, `pg_dump`/`psql` backup & restore without exposing ports, Server Members Intent requirement, and out-of-scope boundaries.
- No changes to Java sources, tests, application resources, `pom.xml`, `application.yaml`, `.env.example`, `compose.yaml`, or `.gitignore` (which did not ignore any of the new files).

## Important Files

- `Dockerfile`
- `.dockerignore`
- `compose.prod.yaml`
- `.github/workflows/ci.yml`
- `docs/deployment.md`
- `docs/task-reports/036-deployment-ci.md`

## Decisions

- **Base image selection (`eclipse-temurin:21.0.6_7-jdk` and `eclipse-temurin:21.0.6_7-jre`)**: Pinned to a specific minor release tag with multi-arch manifest support verified for both `linux/amd64` and `linux/arm64`. Built on Ubuntu 24.04 (Noble Numbat) with glibc, satisfying ADR-0005 (Oracle Cloud ARM64) and ADR-0017 (native `zstd-jni` library).
- **Layer extraction with `--launcher`**: Spring Boot 4.1.1 uses `jarmode=tools`. Running `extract --layers --launcher --destination /extracted` extracts `dependencies`, `spring-boot-loader`, `snapshot-dependencies`, and `application`, enabling execution via `org.springframework.boot.loader.launch.JarLauncher`. This avoids repacking or executing nested jars, optimizing Docker cache reuse.
- **Font support for `ChartRenderer`**: Java2D chart rendering requires font metrics. Explicitly installing `fontconfig` and `fonts-dejavu-core` ensures logical fonts like `SansSerif` map to DejaVu Sans.
- **Compose `.env` handling**: Used `env_file: [ path: .env, required: false ]` so that `docker compose -f compose.prod.yaml config` passes cleanly when `.env` has not yet been copied on disk (e.g., during linting or initial validation with environment variables), while seamlessly loading `.env` when present.

## Verification

1. **Maven verify**:
   - Command: `.\mvnw.cmd clean verify`
   - Result: `Tests run: 675, Failures: 0, Errors: 0, Skipped: 2` (2 benchmark tests skipped as intended). Build success.
2. **Docker build**:
   - Command: `docker build -t rejuv:local .`
   - Result: Success. Multi-stage build executed offline dependency resolution, packaged the application, extracted Boot layers, and generated the runtime image.
3. **Container runtime, user, fonts, and Java version**:
   - Command: `docker run --rm --entrypoint sh rejuv:local -c "id -u && fc-list | head -3 && java -version"`
   - Result:
     - User ID: `10001` (non-root `appuser`)
     - Fonts:
       ```
       /usr/share/fonts/truetype/dejavu/DejaVuSerif-Bold.ttf: DejaVu Serif:style=Bold
       /usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf: DejaVu Sans Mono:style=Book
       /usr/share/fonts/truetype/dejavu/DejaVuSans.ttf: DejaVu Sans:style=Book
       ```
     - Java version:
       ```
       Picked up JAVA_TOOL_OPTIONS: -XX:MaxRAMPercentage=70 -Djava.awt.headless=true
       openjdk version "21.0.6" 2025-01-21 LTS
       OpenJDK Runtime Environment Temurin-21.0.6+7 (build 21.0.6+7-LTS)
       OpenJDK 64-Bit Server VM Temurin-21.0.6+7 (build 21.0.6+7-LTS, mixed mode, sharing)
       ```
4. **Font resolution check in container**:
   - Single-file test program `Check.java` kept in scratchpad (`C:\Users\santiago\.gemini\antigravity-cli\brain\da0e6c3d-9897-4d25-bf8f-257cf407a6e2\scratch\Check.java`) mounted read-only:
     ```
     Picked up JAVA_TOOL_OPTIONS: -XX:MaxRAMPercentage=70 -Djava.awt.headless=true
     Requested font: SansSerif
     Resolved family: SansSerif
     Resolved font name: SansSerif.plain
     Available font families: [DejaVu Sans, DejaVu Sans Mono, DejaVu Serif, Dialog, DialogInput, Monospaced, SansSerif, Serif]
     ```
5. **Native library (`zstd-jni`) check in container**:
   - Test program `ZstdCheck.java` in scratchpad executed with container JRE against `/app/BOOT-INF/lib/*`:
     ```
     Picked up JAVA_TOOL_OPTIONS: -XX:MaxRAMPercentage=70 -Djava.awt.headless=true
     Zstd decompressed: Hello zstd on temurin-21
     ```
6. **Docker Compose production config**:
   - Command: `$env:POSTGRES_PASSWORD="dummy"; docker compose -f compose.prod.yaml config; Remove-Item Env:\POSTGRES_PASSWORD`
   - Result: Success (exit code 0). Validated configuration with no published ports for `postgres` or `bot`.
7. **YAML syntax validation**:
   - Validated `.github/workflows/ci.yml` and `compose.prod.yaml` using PyYAML in a `python:3.12-alpine` container. Both parsed cleanly with zero syntax errors.
8. **Diff inspection**:
   - `git status` confirms only expected files created (`Dockerfile`, `.dockerignore`, `compose.prod.yaml`, `.github/workflows/ci.yml`, `docs/deployment.md`, `docs/task-reports/036-deployment-ci.md`). No existing files modified or deleted.

## Deviations

None.

## What to Check on Pull Request

1. Confirm that GitHub Actions automatically triggers both `verify` and `image` jobs on PR creation.
2. Confirm that `verify` succeeds and executes Testcontainers against the runner's Docker daemon.
3. Confirm that `image` succeeds in building `rejuv:ci`.
4. Confirm no secret values or credentials exist in PR diff.

## Project Context Update (Pending Integration)

Shared context update in `docs/PROJECT_CONTEXT.md` is deferred until branch integration per `AGENTS.md` and the task specification:
- Task 036 implemented: `Dockerfile` (multi-stage, pinned Temurin 21.0.6_7, non-root `appuser`, font packages `fontconfig` and `fonts-dejavu-core`, Boot tools layer extraction, glibc runtime for `zstd-jni`).
- Created `compose.prod.yaml` for production bot + PostgreSQL stack with zero exposed ports and healthcheck synchronization.
- Added `.github/workflows/ci.yml` with parallel `verify` and `image` jobs.
- Added `docs/deployment.md` production operational guide for Oracle Cloud ARM64 Ubuntu.
