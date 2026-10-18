# Rejuv Deployment Guide

Production deployment guide for Rejuv on Oracle Cloud Always Free ARM64 using Docker Compose.

## 1. Server Prerequisites
- Oracle Cloud Always Free compute instance (Ubuntu 24.04 ARM64 Ampere A1, e.g., 2–4 OCPUs, 12–24 GB RAM).
- Docker Engine and Docker Compose plugin (`docker compose`) installed.
- Non-root user in the `docker` group:
  ```bash
  sudo usermod -aG docker $USER
  newgrp docker
  ```
- **Discord Developer Portal configuration**:
  The bot must have the **Server Members Intent** enabled (**Discord Developer Portal** > Application > **Bot** > **Privileged Gateway Intents** > **Server Members Intent**). Without this intent enabled, the gateway closes immediately on connect with a `Disallowed intents` error and the bot will fail to start.

## 2. Getting the Code
Clone the repository to the server:
```bash
git clone https://github.com/sntiago05/rejuv.git
cd rejuv
```

## 3. Environment Configuration
Create the production environment file from `.env.example`:
```bash
cp .env.example .env
chmod 600 .env
```
Edit `.env` with your production values:
```bash
nano .env
```

Required environment variables in `.env`:
- `DISCORD_TOKEN`: Discord bot token from the Discord Developer Portal.
- `DEV_GUILD_ID`: Discord guild/server ID where guild commands and testing occur.
- `POSTGRES_DB`: PostgreSQL database name (default `rejuv`).
- `POSTGRES_USER`: PostgreSQL user name (default `rejuv`).
- `POSTGRES_PASSWORD`: Strong password for PostgreSQL (required; the stack refuses to start without it).
- `ANTHROPIC_API_KEY`: API key from Anthropic Console for match coaching.
- `USAGE_LIMITS_ENABLED`: Feature flag to enable weekly analysis limits (`true` or `false`).
- `USAGE_WEEKLY_FREE_ANALYSES`: Number of free analyses per user per week (e.g., `3`).

> [!CAUTION]
> Never commit or share `.env`. Keep file permissions restricted (`chmod 600 .env`).

## 4. First Start
Build the image and start the PostgreSQL and bot services in detached mode:
```bash
docker compose -f compose.prod.yaml up -d --build
```

### Operational Notes on First Start:
- **First Emoji Sync**: On the very first launch, the bot connects to Discord and automatically syncs game asset emojis (heroes and items) as Discord application emojis. This process uploads around 300+ emojis and takes 1 to 3 minutes before completion.
- **Resource Limits**: The bot container has a memory limit of `1g` (`mem_limit: 1g`). This is a starting baseline suited for the current feature set.

## 5. Inspecting Logs
Follow the live logs of the bot:
```bash
docker compose -f compose.prod.yaml logs -f bot
```
Inspect PostgreSQL logs:
```bash
docker compose -f compose.prod.yaml logs -f postgres
```
Check service status:
```bash
docker compose -f compose.prod.yaml ps
```

## 6. Update Procedure
When deploying updates from the repository:
```bash
git pull origin main
docker compose -f compose.prod.yaml up -d --build
```
Docker Compose recreates the bot container with the freshly built image. Flyway automatically validates and applies any new database migrations on application startup.

## 7. Database Backup and Restore

### Backup (`pg_dump`)
Create a compressed database dump without exposing ports:
```bash
docker compose -f compose.prod.yaml exec -T postgres pg_dump -U rejuv -d rejuv | gzip > "backup_rejuv_$(date +%Y%m%d_%H%M%S).sql.gz"
```
*(If you changed `POSTGRES_USER` or `POSTGRES_DB`, replace `rejuv` with your configured values).*

### Restore (`psql`)
Restore an existing database dump:
```bash
gunzip -c backup_rejuv_YYYYMMDD_HHMMSS.sql.gz | docker compose -f compose.prod.yaml exec -T postgres psql -U rejuv -d rejuv
```

## 8. Intentionally Out of Scope
The following components are intentionally omitted from this task and deployment setup:
- **HTTPS / Reverse Proxy / Caddy**: The bot operates via the Discord Gateway over outbound WebSockets and currently exposes no inbound web HTTP endpoints. A reverse proxy setup will be added alongside web report features.
- **Custom Domain**: No public web domain is required for Discord bot gateway operations.
- **External Metrics and Alerting**: Operational observability relies on Docker daemon log rotation (`json-file`, max 10MB x 3) and container restart policies (`restart: unless-stopped`).
