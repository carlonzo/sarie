#!/usr/bin/env bash
# Probe an okhttp version against the canonical ConnectInterceptor guard, and if it
# matches, pin it: RecipeRegistry, goldens, CI matrix, compatibility table.
# Does not raise okhttpMin (dropping older versions is a human decision).
#
# Usage: JAVA_HOME=<temurin-21> ./scripts/pin-okhttp-version.sh <okhttp-version>
set -euo pipefail

V="${1:?usage: pin-okhttp-version.sh <okhttp-version>}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REGISTRY="$ROOT/plugin/src/main/kotlin/dev/okhttpcronet/plugin/RecipeRegistry.kt"
GEN="$ROOT/plugin/scripts/generate-fingerprints.sh"
PR_YML="$ROOT/.github/workflows/pr.yml"
COMPAT="$ROOT/COMPATIBILITY.md"
TOML="$ROOT/gradle/libs.versions.toml"
PLUGIN_BUILD="$ROOT/plugin/build.gradle.kts"

if grep -qE "^[[:space:]]*\"$V\" to recipe" "$REGISTRY"; then
  echo "pin-okhttp-version: $V is already in RecipeRegistry"
  exit 0
fi

echo "pin-okhttp-version: probing $V against CANONICAL_GUARD"
PROBE_LOG="$ROOT/plugin/build/pin-$V.log"
mkdir -p "$(dirname "$PROBE_LOG")"
"$GEN" "$V" | tee "$PROBE_LOG"
if ! grep -qE "^$V: .* verified=yes$" "$PROBE_LOG"; then
  echo "pin-okhttp-version: $V does not match the canonical ConnectInterceptor shape." >&2
  echo "This is a new internals family — raise okhttpMin by hand and drop old pins." >&2
  grep -E "^$V: |^PROBLEM |^FATAL" "$PROBE_LOG" >&2 || true
  exit 1
fi

python3 - "$V" "$REGISTRY" "$PR_YML" "$COMPAT" "$TOML" "$PLUGIN_BUILD" "$GEN" <<'PY'
import re, sys
from pathlib import Path

v, registry, pr_yml, compat, toml, plugin_build, gen = sys.argv[1:8]

def version_key(s):
    return tuple(int(x) for x in s.split("."))

text = Path(registry).read_text()
if f'"{v}" to recipe' in text:
    sys.exit(0)
m = re.search(r"(val recipes: Map<String, Recipe> = mapOf\()(.*?)(\n    \))", text, re.S)
if not m:
    sys.exit("RecipeRegistry recipes map not found")
found = re.findall(r'"([0-9.]+)" to recipe', m.group(2))
versions = sorted(set(found + [v]), key=version_key)
block = "\n" + "".join(f'        "{x}" to recipe("{x}"),\n' for x in versions)
Path(registry).write_text(text[: m.start(2)] + block + text[m.end(2) :])

lit = "[" + ", ".join(f'"{x}"' for x in versions) + "]"
pr = Path(pr_yml).read_text()
pr2, n = re.subn(r"okhttp: \[[^\]]+\]", f"okhttp: {lit}", pr)
if n == 0:
    sys.exit("no okhttp matrix lists found in pr.yml")
Path(pr_yml).write_text(pr2)

oldest, newest = versions[0], versions[-1]
csv = ", ".join(versions)
compat_text = Path(compat).read_text()
compat_text, n = re.subn(
    r"\| unpublished \(this repo\) \| [^|]+ \| [^|]+\|",
    f"| unpublished (this repo) | {csv} | {oldest} is the compile floor; {newest} is the sample/default |",
    compat_text,
    count=1,
)
if n != 1:
    sys.exit("COMPATIBILITY.md unpublished-row not found")
Path(compat).write_text(compat_text)

toml_text = Path(toml).read_text()
toml_text, n = re.subn(r'^okhttpMin\s*=\s*"[^"]+"', f'okhttpMin = "{oldest}"', toml_text, count=1, flags=re.M)
if n != 1:
    sys.exit("okhttpMin not found in libs.versions.toml")
Path(toml).write_text(toml_text)

pb = Path(plugin_build).read_text()
pb2, n = re.subn(r'okhttp:[0-9]+\.[0-9]+\.[0-9]+', f"okhttp:{oldest}", pb)
if n == 0:
    sys.exit("no okhttp:x.y.z coordinates in plugin/build.gradle.kts")
Path(plugin_build).write_text(pb2)

gen_text = Path(gen).read_text()
m = re.search(r"STABLE_5X=\((.*)\)", gen_text)
if not m:
    sys.exit("STABLE_5X not found")
stable = m.group(1).split()
if v not in stable and v.startswith("5."):
    stable = sorted(set(stable + [v]), key=version_key)
    gen_text = gen_text[: m.start(1)] + " ".join(stable) + gen_text[m.end(1) :]
    Path(gen).write_text(gen_text)

print("pinned", versions)
PY

echo "pin-okhttp-version: regenerating goldens with $V in the pin set"
"$GEN"

echo "pin-okhttp-version: $V pinned"
