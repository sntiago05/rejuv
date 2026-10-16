# Rejuv

Rejuv is an English-language Discord bot for Deadlock players. This skeleton starts a Java 21 / Spring Boot 4.1 application, logs in with JDA 5, and registers dev-guild `/ping` and `/help` commands.

## Prerequisites

- Java 21
- Maven wrapper from this repository
- Docker Desktop (runs PostgreSQL locally and the test database)
- A Discord bot token
- A Discord development guild ID

## Run Locally

1. Copy `.env.example` to `.env` and set `POSTGRES_PASSWORD`. Never commit `.env`.
2. Start PostgreSQL and wait until `docker compose ps` reports it as `healthy`:

   ```powershell
   docker compose up -d postgres
   docker compose ps
   ```

3. Export the same `POSTGRES_*` values and the Discord credentials in your shell, then start the app:

   ```powershell
   $env:POSTGRES_DB="rejuv"
   $env:POSTGRES_USER="rejuv"
   $env:POSTGRES_PASSWORD="your-db-password"
   $env:DISCORD_TOKEN="your-token"
   $env:DEV_GUILD_ID="your-dev-guild-id"
   .\mvnw.cmd spring-boot:run
   ```

The app is non-web and requires a reachable PostgreSQL at startup: Flyway applies the migrations in `src/main/resources/db/migration`, then Hibernate validates the entity mapping (`ddl-auto: validate`). If the database is down or the password is wrong, startup fails. Docker Compose reads `.env`; the app does not, so export the variables. `POSTGRES_HOST` and `POSTGRES_PORT` optionally override `localhost` and `5432`. With valid Discord credentials, the bot comes online and registers `/ping` and `/help` in the configured dev guild.

## Tests

`.\mvnw.cmd verify` needs Docker running: the database tests start PostgreSQL 17 with Testcontainers and do not use the Compose database or `.env`.
