#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLS_DIR="$ROOT_DIR/.tools"
CLI_BIN="$TOOLS_DIR/tailwindcss"
VERSION="v4.3.3"

SRC_CSS="$ROOT_DIR/src/main/tailwind/report.css"
TARGET_CSS="$ROOT_DIR/src/main/resources/static/report.css"

ARCH="$(uname -m)"
case "$ARCH" in
  x86_64|amd64)
    CLI_ASSET="tailwindcss-linux-x64"
    EXPECTED_SHA256="dc61b3ac6b8c9ca874c0cc4c57b2409791a64c5540404ca5f5367360babc313a"
    ;;
  aarch64|arm64)
    CLI_ASSET="tailwindcss-linux-arm64"
    EXPECTED_SHA256="55fd0b241214eff3de1e8ee4f22796662f2d2e7a49bcfca7477cfd0bac398195"
    ;;
  *)
    echo "Error: Unsupported architecture: $ARCH" >&2
    exit 1
    ;;
esac

verify_sha256() {
  local file="$1"
  local expected="$2"
  local actual=""

  if command -v sha256sum >/dev/null 2>&1; then
    actual="$(sha256sum "$file" | awk '{print $1}')"
  elif command -v shasum >/dev/null 2>&1; then
    actual="$(shasum -a 256 "$file" | awk '{print $1}')"
  else
    echo "Error: sha256sum or shasum is required to verify Tailwind CLI" >&2
    exit 1
  fi

  if [ "$actual" != "$expected" ]; then
    echo "Error: SHA-256 verification failed for $file" >&2
    echo "  Expected: $expected" >&2
    echo "  Actual:   $actual" >&2
    return 1
  fi
  return 0
}

ensure_cli() {
  if [ -f "$CLI_BIN" ]; then
    if verify_sha256 "$CLI_BIN" "$EXPECTED_SHA256" >/dev/null 2>&1; then
      return 0
    fi
    echo "Existing CLI binary failed checksum verification, redownloading..." >&2
    rm -f "$CLI_BIN"
  fi

  mkdir -p "$TOOLS_DIR"
  local download_url="https://github.com/tailwindlabs/tailwindcss/releases/download/${VERSION}/${CLI_ASSET}"
  echo "Downloading Tailwind CSS standalone CLI ${VERSION} for ${ARCH}..."
  curl -sSL "$download_url" -o "${CLI_BIN}.tmp"
  verify_sha256 "${CLI_BIN}.tmp" "$EXPECTED_SHA256"
  mv "${CLI_BIN}.tmp" "$CLI_BIN"
  chmod +x "$CLI_BIN"
}

ensure_cli

if [ "${1:-}" = "--check" ]; then
  TMP_CSS="$(mktemp "${TMPDIR:-/tmp}/report-css-check.XXXXXX.css")"
  trap 'rm -f "$TMP_CSS"' EXIT
  "$CLI_BIN" -i "$SRC_CSS" -o "$TMP_CSS" --minify >/dev/null
  if cmp -s "$TARGET_CSS" "$TMP_CSS"; then
    echo "OK: $TARGET_CSS is up to date."
    exit 0
  else
    echo "Error: $TARGET_CSS is out of date. Run scripts/build-css.sh to regenerate." >&2
    diff -u "$TARGET_CSS" "$TMP_CSS" | head -n 30 >&2 || true
    exit 1
  fi
else
  mkdir -p "$(dirname "$TARGET_CSS")"
  "$CLI_BIN" -i "$SRC_CSS" -o "$TARGET_CSS" --minify
  echo "Generated $TARGET_CSS ($(wc -c < "$TARGET_CSS") bytes)"
fi
