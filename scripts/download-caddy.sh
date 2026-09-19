#!/usr/bin/env bash
# Downloads the pinned Caddy build used as the HTTP/3 test origin.
# Version + sha256 recorded in THIRD_PARTY.md ("Caddy HTTP/3 test origin").
# Usage: ./scripts/download-caddy.sh   (idempotent; extracts to scripts/bin/caddy)
set -euo pipefail

CADDY_VERSION="2.11.4"
CADDY_SHA256="527fbf917c39189a1e3b31d34fa955601680b2d5c8055d2a87b8b9588dec7bb9"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BIN="$ROOT/scripts/bin"
mkdir -p "$BIN"

if [ ! -x "$BIN/caddy" ]; then
  TARBALL="caddy_${CADDY_VERSION}_linux_amd64.tar.gz"
  URL="https://github.com/caddyserver/caddy/releases/download/v${CADDY_VERSION}/${TARBALL}"
  echo "$CADDY_SHA256  $BIN/$TARBALL" | sha256sum -c - >/dev/null 2>&1 || {
    curl -fsSL -o "$BIN/$TARBALL" "$URL"
    echo "$CADDY_SHA256  $BIN/$TARBALL" | sha256sum -c -
  }
  tar -xzf "$BIN/$TARBALL" -C "$BIN" caddy LICENSE
  chmod +x "$BIN/caddy"
  rm -f "$BIN/$TARBALL"
fi
"$BIN/caddy" version
