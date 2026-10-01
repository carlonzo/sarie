#!/usr/bin/env bash
set -euo pipefail
# Regenerates src/test/resources/ktor/<version>/OkUtilsKt$WhenMappings.class from Maven Central.
# The only way to update those goldens; never hand-edit them.

VERSIONS=("2.0.0" "2.2.4" "2.3.13" "3.0.0" "3.2.4" "3.3.0")
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET_ROOT="$(cd "$SCRIPT_DIR/../src/test/resources" && pwd)/ktor"

for v in "${VERSIONS[@]}"; do
  out_dir="$TARGET_ROOT/$v"
  mkdir -p "$out_dir"
  tmp_jar="$(mktemp --suffix=.jar)"
  url="https://repo1.maven.org/maven2/io/ktor/ktor-client-okhttp-jvm/$v/ktor-client-okhttp-jvm-$v.jar"
  echo "Downloading $url..."
  curl -sSL "$url" -o "$tmp_jar"
  unzip -p "$tmp_jar" "io/ktor/client/engine/okhttp/OkUtilsKt\$WhenMappings.class" > "$out_dir/OkUtilsKt\$WhenMappings.class"
  rm -f "$tmp_jar"
  echo "Extracted $v golden to $out_dir/OkUtilsKt\$WhenMappings.class"
done
