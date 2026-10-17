#!/usr/bin/env bash
# Rejuv run driver. Usage: .claude/skills/run-rejuv/driver.sh <command> [args]
# See SKILL.md next to this file. Works from any directory inside the repo or a worktree.
set -euo pipefail

ROOT=$(git rev-parse --show-toplevel)
SKILL_DIR="$ROOT/.claude/skills/run-rejuv"
cd "$ROOT"

# Throwaway database: its own Compose project and port, so it never touches the
# developer's `rejuv` project (port 5432, password in .env). Shared by all worktrees.
PG_PROJECT=rejuv-run
export POSTGRES_HOST=localhost
export POSTGRES_PORT="${REJUV_RUN_PG_PORT:-55432}"
export POSTGRES_DB=rejuv
export POSTGRES_USER=rejuv
export POSTGRES_PASSWORD=rejuv-run-local

# A full JDK 21 (javac included). /usr/lib/jvm/java-21-openjdk-amd64 on this machine is a JRE only.
pick_java() {
  local candidate
  for candidate in "${JAVA_HOME:-}" "$HOME"/.jdks/*21* /usr/lib/jvm/*21*; do
    if [[ -n "$candidate" && -x "$candidate/bin/javac" ]] \
        && "$candidate/bin/javac" -version 2>&1 | grep -q 'javac 21'; then
      export JAVA_HOME="$candidate"
      return
    fi
  done
  echo "driver: no JDK 21 with javac found (checked \$JAVA_HOME, ~/.jdks/*21*, /usr/lib/jvm/*21*)" >&2
  exit 1
}
pick_java
JAVA="$JAVA_HOME/bin/java"

# Docker: the socket is root:docker; a login session may predate the docker group membership.
dk() {
  if docker info >/dev/null 2>&1; then
    docker "$@"
  else
    sg docker -c "$(printf '%q ' docker "$@")"
  fi
}
compose() { dk compose -p "$PG_PROJECT" -f "$ROOT/compose.yaml" "$@"; }

JAR="$ROOT/target/rejuv-0.0.1-SNAPSHOT.jar"
CP_FILE="$ROOT/target/driver.classpath"

build() {
  ./mvnw -q -DskipTests package
  if [[ ! -s "$CP_FILE" || "$ROOT/pom.xml" -nt "$CP_FILE" ]]; then
    ./mvnw -q dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile="$CP_FILE"
  fi
  echo "driver: built $JAR"
}

ensure_built() {
  if [[ ! -s "$CP_FILE" || ! -d "$ROOT/target/classes" ]]; then
    build
  fi
}

ensure_db() {
  if ! compose ps --status running --services 2>/dev/null | grep -qx postgres; then
    db_up
  fi
}

db_up() {
  compose up -d --wait postgres
  echo "driver: postgres ready on localhost:$POSTGRES_PORT (db=$POSTGRES_DB user=$POSTGRES_USER)"
}

run_driver() {
  ensure_built
  ensure_db
  "$JAVA" -XX:+EnableDynamicAgentLoading -Xshare:off \
    -cp "$ROOT/target/classes:$(cat "$CP_FILE")" "$SKILL_DIR/Driver.java" "$@"
}

boot() {
  ensure_built
  ensure_db
  local log="$ROOT/target/driver-boot.log" rc=0
  env -u DISCORD_TOKEN -u DEV_GUILD_ID timeout 120 "$JAVA" -jar "$JAR" >"$log" 2>&1 || rc=$?
  grep -E 'Successfully applied|Schema .* is up to date|Started RejuvApplication|ERROR|APPLICATION FAILED' "$log" || true
  if [[ $rc -ne 0 ]] || ! grep -q 'Started RejuvApplication' "$log"; then
    echo "driver: boot FAILED (exit $rc), full log: $log" >&2
    exit 1
  fi
  echo "driver: boot OK (exit 0), full log: $log"
}

case "${1:-}" in
  build) build ;;
  db-up) db_up ;;
  db-down) compose stop postgres ;;
  db-reset) compose down -v; db_up ;;
  psql) shift; ensure_db; compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "$*" ;;
  boot) boot ;;
  list) run_driver list ;;
  cmd) shift; run_driver cmd "$@" ;;
  eval) shift; run_driver eval "$*" ;;
  verify) shift; dk_cmd=$(printf '%q ' ./mvnw "$@" verify)
          if docker info >/dev/null 2>&1; then ./mvnw "$@" verify; else sg docker -c "$dk_cmd"; fi ;;
  *)
    sed -n '2,3p' "$0"
    echo "commands: build | db-up | db-down | db-reset | psql '<sql>' | boot | list | cmd <name> [opt=value ...] [--user=<id>] | eval '<java>' | verify [mvn args]"
    exit 2 ;;
esac
