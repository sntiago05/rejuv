# Rejuv

Rejuv is an English-language Discord bot for Deadlock players. This skeleton starts a Java 21 / Spring Boot 4.1 application, logs in with JDA 5, and registers dev-guild `/ping` and `/help` commands.

## Prerequisites

- Java 21
- Maven wrapper from this repository
- A Discord bot token
- A Discord development guild ID

## Run Locally

1. Copy `.env.example` to `.env` for local reference.
2. Export `DISCORD_TOKEN` and `DEV_GUILD_ID` in your shell environment.
3. Start the app:

   ```powershell
   $env:DISCORD_TOKEN="your-token"
   $env:DEV_GUILD_ID="your-dev-guild-id"
   .\mvnw.cmd spring-boot:run
   ```

The app is non-web and disables database-related auto-configuration until the database task is implemented. With valid Discord credentials, the bot comes online and registers `/ping` and `/help` in the configured dev guild.
