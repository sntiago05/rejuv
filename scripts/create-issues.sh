#!/usr/bin/env bash
# Creates milestones, labels and one issue per task using the GitHub CLI.
# Usage: gh auth login  (once), then: bash scripts/create-issues.sh
set -euo pipefail

gh api repos/:owner/:repo/milestones -f title="Week 1 - MVP" >/dev/null || true
gh api repos/:owner/:repo/milestones -f title="Week 2 - Growth & premium" >/dev/null || true

for l in "antigravity:1f6feb" "claude-code:d97706" "claude-project:8250df" "free:2da44e" "premium:bf8700" "infra:6e7781"; do
  gh label create "${l%%:*}" --color "${l##*:}" --force >/dev/null
done

issue () { # number, title, labels, milestone
  gh issue create --title "$1: $2" --label "$3" --milestone "$4" \
    --body "Spec: \`docs/tasks/$1-*.md\` (write it in the Claude Project before starting). Plan: \`docs/implementation-plan.md\`." >/dev/null
  echo "created $1"
}

W1="Week 1 - MVP"; W2="Week 2 - Growth & premium"
issue 001 "Project skeleton, config, /ping"                 "antigravity,infra" "$W1"
issue 002 "i18n setup + /help"                               "antigravity,free"  "$W1"
issue 003 "PostgreSQL, Docker Compose, Flyway, linked_account" "antigravity,infra" "$W1"
issue 004 "DeadlockApiClient with caching and 503 handling"  "antigravity,infra" "$W1"
issue 005 "/link and /unlink"                                "antigravity,free"  "$W1"
issue 006 "/profile"                                         "antigravity,free"  "$W1"
issue 007 "MetricsService + benchmarks"                      "claude-code"       "$W1"
issue 008 "AnalysisClient (Claude Haiku 5.5)"                "claude-code"       "$W1"
issue 009 "/analyze + match_analysis persistence"            "antigravity,free"  "$W1"
issue 010 "Prompt tuning with testers"                       "claude-project"    "$W1"
issue 011 "/build hero [rank]"                               "antigravity,free"  "$W2"
issue 012 "/counter hero"                                    "antigravity,free"  "$W2"
issue 013 "/meta [rank]"                                     "antigravity,free"  "$W2"
issue 014 "/heroes"                                          "antigravity,free"  "$W2"
issue 015 "/mates + party stats in /profile"                 "antigravity,free"  "$W2"
issue 016 "/leaderboard server"                              "antigravity,free"  "$W2"
issue 017 "Web report page /r/{publicId} + embed button"     "claude-code"       "$W2"
issue 018 "Soul curve chart, domain + Caddy HTTPS"           "antigravity,infra" "$W2"
issue 019 "Feature flag + usage limits"                      "antigravity,premium" "$W2"
issue 020 "Patreon Premium role check"                       "antigravity,premium" "$W2"
issue 021 "/compare and /items"                              "claude-code,premium" "$W2"
