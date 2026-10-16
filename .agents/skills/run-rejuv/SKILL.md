---
name: run-rejuv
description: Build, run, boot-check and drive the Rejuv Discord bot locally without Discord. Use when asked to run or start the bot, try a slash command (/ping, /help, /link, /analyze ...), call a service, client or repository against the real Spring context, check Flyway migrations, query the local database, or run the test suite (./mvnw verify).
---

# Run Rejuv

Rejuv is a non-web Spring Boot bot. Discord is its only UI, and no agent has a bot token. So you drive it with
`.claude/skills/run-rejuv/driver.sh`. The driver boots the **real** Spring context (real beans, real PostgreSQL,
real Flyway) with the Discord token forced empty, then:

- sends a fake slash event through the real `CommandRouter` and prints every reply call, or
- runs a Java snippet against the live beans (services, clients, repositories).

All paths are relative to the repo root (or worktree root). The driver `cd`s there itself.

## Prerequisites

- JDK 21 **with `javac`**. The driver picks the first match from `$JAVA_HOME`, `~/.jdks/*21*`, `/usr/lib/jvm/*21*`.
  On this machine that is `~/.jdks/azul-21.0.12.1`. `/usr/lib/jvm/java-21-openjdk-amd64` is a JRE only (no `javac`).
- Docker. If your shell is not in the `docker` group yet, the driver falls back to `sg docker -c ...` on its own.
- No `.env` and no secrets needed. Never read `.env`.

## Build

```bash
.claude/skills/run-rejuv/driver.sh build
```

This runs `./mvnw -q -DskipTests package` and writes the test-scope classpath to `target/driver.classpath`.
The driver needs that classpath for Mockito. It rewrites the file when `pom.xml` changes. Run `build` again after
every code change: `cmd`, `list` and `eval` use `target/classes` as they are and do not recompile.

## Run (agent path)

```bash
D=.claude/skills/run-rejuv/driver.sh
$D db-up                      # throwaway Postgres: compose project "rejuv-run", localhost:55432
$D boot                       # java -jar, no Discord: Flyway runs, context starts, exits 0
$D list                       # slash commands as Discord would receive them
$D cmd ping                   # route a fake /ping through the real CommandRouter
$D cmd analyze                # unknown command -> error reply
$D eval 'bean(Messages.class).get("ping.reply", "7")'
$D psql 'select version, description, success from flyway_schema_history'
```

`list`, `cmd`, `eval` and `boot` start the database on their own if it is down. Each call is a fresh JVM (~5 s).

### `cmd <name> [option=value ...] [--user=<discord-id>]`

The real router handles the event, so you also see its INFO log line. Output for `cmd ping`:

```
... CommandRouter : Slash command userId=100000000000000001 command=ping durationMs=171 outcome=success
--- reply calls (1) ---
event.reply("Pong! 42 ms").setEphemeral(true).queue()
```

- Each fluent chain prints as one line, so you can check text, ephemeral, embeds (as JSON) and `queue()`.
  `deferReply()` and `getHook()` chains work too:
  `event.getHook().editOriginalEmbeds(embed{"description":"ok","title":"Report"}).queue()`.
- Options: `cmd link account=76561198000000000`. `getOption(name)`, `getOption(name, OptionMapping::getAsString)`
  and `getAsLong/Int/Boolean/Double` all work. Every option is typed `STRING`.
- Fake values: user id `100000000000000001` (change it with `--user=`), gateway ping `42`.
- Exit code 1 and a `WARNING` line if the command never calls `queue()`/`complete()`. In Discord the user would
  see "The application did not respond".

### `eval '<java>'`

Compiles the snippet against the live context and prints `=> result`. In scope: `ctx`, `bean(Class)`,
`java.util.*`, `java.time.*`, and every `sntiago05.rejuv.*` package that exists (`clients`, `services`, ...).
An expression is fine. For several statements, write a body with `return`:

```bash
D=.claude/skills/run-rejuv/driver.sh
$D eval 'var repo = bean(LinkedAccountRepository.class); repo.save(new LinkedAccount(100000000000000001L, 12345L, Instant.now())); return repo.findAll().stream().map(a -> a.getDiscordUserId() + "->" + a.getAccountId()).toList();'
$D psql 'delete from linked_account'
```

A snippet that throws prints `=> threw:` and the stack trace, and the exit code is 1. A compile error prints the
generated source. Use this for clients and services, for example `bean(DeadlockApiClient.class).getRanks()` once
Task 004 exists. The real network is reachable (`https://api.deadlock-api.com` answered 200).

### Database

```bash
D=.claude/skills/run-rejuv/driver.sh
$D psql 'select * from linked_account'
$D db-reset     # drop the volume and start empty (use after switching to a branch with different migrations)
$D db-down      # stop, keep data
```

Fixed credentials (not secret): db `rejuv`, user `rejuv`, password `rejuv-run-local`, port `55432`.
Change the port with `REJUV_RUN_PG_PORT`.

## Test

```bash
.claude/skills/run-rejuv/driver.sh verify
```

This runs `./mvnw verify` with Docker access (31 tests, ~13 s, `BUILD SUCCESS`). Extra Maven args go before
`verify`, for example `driver.sh verify -q`.

## Run (human path: real Discord)

The user exports `DISCORD_TOKEN`, `DEV_GUILD_ID` and the `POSTGRES_*` values from their own `.env`, then runs
`./mvnw spring-boot:run` (see `README.md`). The bot then registers its commands in the dev guild and keeps running.
This was **not** run here: no token is available to agents. A wrong token fails fast: `InvalidTokenException:
The provided token is invalid!` and exit 1.

## Gotchas

- **No token = no Discord, and the app exits.** `JdaConfig` creates the JDA bean only when `bot.token` and
  `bot.dev-guild-id` are both set. Without them, nothing keeps the JVM alive: `java -jar` migrates, logs
  `Started RejuvApplication`, then exits 0. That is the success signal for `boot`, not a crash.
- **`./mvnw verify` fails in this shell** with `Could not find a valid Docker environment`. The socket is
  `root:docker`, and the session lacks the group even though `/etc/group` lists the user. Use `driver.sh verify`
  (it wraps Maven in `sg docker`).
- **Port 5432 belongs to the developer's own `rejuv-postgres-1`.** Its password lives in `.env`, which agents
  must not read. So the driver uses a separate Compose project (`-p rejuv-run`, port 55432, fixed password).
  The env vars it exports override the `.env` that Compose would load on its own.
- **One `rejuv-run` database for all worktrees.** If two branches add different `V2__*.sql` files, Flyway fails
  on a checksum mismatch. Run `driver.sh db-reset`.
- **The default `java` is JDK 23** (`~/.jdks/azul-23.0.2`). It builds, but the project targets 21, so the driver
  pins 21. Setting `JAVA_HOME` to the system JRE gives `release version 21 not supported`.
- **The fake event is a Mockito mock.** `queue(success -> ...)` callbacks never run, and `complete()`/`submit()`
  return `null`. A command that does more work inside a `queue` callback shows only the first call.
- **`eval` can reach driver internals only through reflection** (`Driver` lives in another class loader; call
  `setAccessible(true)`). Example: building a fake event by hand to test a command's option parsing.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `permission denied while trying to connect to the docker API` | The driver retries with `sg docker`. For raw `docker` commands, use `sg docker -c "docker ps"`. |
| `Fatal error compiling: error: release version 21 not supported` | `JAVA_HOME` points to a JRE. Unset it or point it at `~/.jdks/azul-21.0.12.1`. |
| `Could not find a valid Docker environment` during tests | Use `driver.sh verify` instead of `./mvnw verify`. |
| `eval` prints `compilation failed` and the source | Fix the snippet: unknown class, a missing `return` in a statement body, or a class you have not built yet (`driver.sh build`). |
| `eval` prints `cannot access a member of class Driver with package access` | Call `setAccessible(true)` on the reflected member. |
| `cmd`/`eval` still shows old behavior | Run `driver.sh build`. The driver does not recompile `src/`. |
