#!/usr/bin/env bash
# Generates the local test CA + leaf cert for the Caddy HTTP/3 origin, the deterministic
# /big test payloads, and refreshes the NSC copy of the CA in the instrumentation-tests app.
# Outputs (re-runnable, deterministic names):
#   scripts/certs/{ca.pem,ca.key,cert.pem,key.pem}
#   scripts/big/{1mib.bin,5mib.bin}
#   instrumentation-tests/src/main/res/raw/caddy_root_ca  (public cert only - safe to keep in git)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CERTS="$ROOT/scripts/certs"
BIG="$ROOT/scripts/big"
mkdir -p "$CERTS" "$BIG"

# 1) Root CA (10y, critical CA constraints).
openssl req -x509 -newkey rsa:2048 -nodes \
  -keyout "$CERTS/ca.key" -out "$CERTS/ca.pem" -days 3650 \
  -subj "/CN=okhttp-cronet-test-ca" \
  -addext "basicConstraints=critical,CA:TRUE" \
  -addext "keyUsage=critical,keyCertSign,cRLSign"

# 2) Leaf for the origin host, SANs per plan todo 9.
openssl req -newkey rsa:2048 -nodes \
  -keyout "$CERTS/key.pem" -out "$CERTS/cert.csr" \
  -subj "/CN=10.0.2.2" \
  -addext "subjectAltName=DNS:localhost,DNS:10.0.2.2,IP:127.0.0.1,IP:10.0.2.2"
cat > "$CERTS/leaf.ext" <<'EOF'
basicConstraints=CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:localhost,DNS:10.0.2.2,IP:127.0.0.1,IP:10.0.2.2
EOF
openssl x509 -req -in "$CERTS/cert.csr" \
  -CA "$CERTS/ca.pem" -CAkey "$CERTS/ca.key" -CAcreateserial \
  -out "$CERTS/cert.pem" -days 3650 -sha256 -extfile "$CERTS/leaf.ext"
rm -f "$CERTS/cert.csr" "$CERTS/leaf.ext"

# Full chain (leaf + root): Cronet's QUIC handshake only receives what the origin serves,
# and a leaf-only chain fails the h3 TLS verify even though the NSC anchor trusts the root.
cat "$CERTS/cert.pem" "$CERTS/ca.pem" > "$CERTS/fullchain.pem"

# 3) Deterministic-name payloads for the /big route (content is random; only the
#    byte count matters to the tests).
head -c 1048576 /dev/urandom > "$BIG/1mib.bin"
head -c 5242880 /dev/urandom > "$BIG/5mib.bin"

# 4) Refresh the bundled NSC trust anchor (public cert; no secret in git).
cp "$CERTS/ca.pem" "$ROOT/instrumentation-tests/src/main/res/raw/caddy_root_ca"

openssl x509 -in "$CERTS/cert.pem" -noout -subject -ext subjectAltName
