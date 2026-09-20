#!/usr/bin/env bash
# Downloads a pinned static curl-with-HTTP/3 used to probe the local Caddy origin
# from the host. The emulator cannot complete local-origin h3 (Chromium known-root
# policy); this client writes the access-log "proto":"HTTP/3" evidence instead.
# Version + sha256 recorded in THIRD_PARTY.md.
# Usage: ./scripts/download-curl-http3.sh   (idempotent; extracts to scripts/bin/curl-http3)
set -euo pipefail

CURL_VERSION="8.22.0"
TARBALL="curl-linux-x86_64-musl-${CURL_VERSION}.tar.xz"
TARBALL_SHA256="dfb02460ba2abe513087538f12a3cf79b74b64a5ea3787ce8ac0cdb11251f884"
URL="https://github.com/stunnel/static-curl/releases/download/${CURL_VERSION}/${TARBALL}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BIN="$ROOT/scripts/bin"
mkdir -p "$BIN"

if [ ! -x "$BIN/curl-http3" ]; then
  echo "$TARBALL_SHA256  $BIN/$TARBALL" | sha256sum -c - >/dev/null 2>&1 || {
    curl -fsSL -o "$BIN/$TARBALL" "$URL"
    echo "$TARBALL_SHA256  $BIN/$TARBALL" | sha256sum -c -
  }
  tar -xJf "$BIN/$TARBALL" -C "$BIN" curl
  mv "$BIN/curl" "$BIN/curl-http3"
  chmod +x "$BIN/curl-http3"
  rm -f "$BIN/$TARBALL" "$BIN/trurl" "$BIN/SHA256SUMS"
fi
"$BIN/curl-http3" -V
