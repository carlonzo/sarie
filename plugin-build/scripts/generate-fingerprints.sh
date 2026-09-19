#!/usr/bin/env bash
# Generate golden stock/rewritten ConnectInterceptor artifacts for every released okhttp 5.x
# version, structurally verify each against the registered GuardSpec shape, and regenerate:
#   - plugin/src/test/resources/stock/<version>/{android,jvm}/ (goldens + Textifier dumps)
#   - plugin/src/main/kotlin/dev/okhttpcronet/plugin/GoldenFingerprints.kt
#   - bridge/src/main/kotlin/dev/okhttpcronet/bridge/VerifiedOkHttpVersions.kt
#   - plugin-build/recipe-verification-report.md (version -> guard -> pinned/excluded)
#   - the "Golden bytecode artifacts" section of THIRD_PARTY.md (between the markers)
#
# Deterministic + re-runnable: pinned Maven Central URLs, pinned ASM 9.7.1 for the dump +
# shape-check tool. Versions processed = released 5.x list ∪ registry-pinned versions ∪ args,
# so every run re-verifies every pinned version and refuses to keep a pinned version whose
# bytecode no longer matches the registered shape (never force-fit, never pin a lie).
#
# rewritten.txt mirrors dev.okhttpcronet.plugin.ConnectInterceptorRewriter's algorithm
# (discard intercept body, emit ALOAD 1 / INVOKESTATIC CronetBridge.intercept / ARETURN,
# COMPUTE_FRAMES); the unit tests assert the instruction list independently.
#
# Usage: JAVA_HOME=<temurin-21> ./generate-fingerprints.sh [okhttp-version ...]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLUGIN_DIR="$(cd "$SCRIPT_DIR/../plugin" && pwd)"   # plugin-build/plugin
ROOT_DIR="$(cd "$PLUGIN_DIR/../.." && pwd)"         # repo root
RES="$PLUGIN_DIR/src/test/resources/stock"
CACHE="$PLUGIN_DIR/build/golden-cache"
GOLDEN_KT="$PLUGIN_DIR/src/main/kotlin/dev/okhttpcronet/plugin/GoldenFingerprints.kt"
BRIDGE_KT="$ROOT_DIR/bridge/src/main/kotlin/dev/okhttpcronet/bridge/VerifiedOkHttpVersions.kt"
REPORT="$ROOT_DIR/plugin-build/recipe-verification-report.md"
REGISTRY_KT="$PLUGIN_DIR/src/main/kotlin/dev/okhttpcronet/plugin/RecipeRegistry.kt"

# Released stable okhttp 5.x versions on Maven Central (re-verified against
# .../com/squareup/okhttp3/okhttp/maven-metadata.xml). Pre-releases are NOT auto-pinned.
STABLE_5X=(5.0.0 5.1.0 5.2.0 5.2.1 5.2.2 5.2.3 5.3.0 5.3.1 5.3.2 5.4.0 5.5.0)

# Versions pinned in RecipeRegistry.kt (lines shaped like `        "5.0.0" to ...,`).
mapfile -t PINNED < <(grep -E '^[[:space:]]*"[0-9.]+" to ' "$REGISTRY_KT" | sed -E 's/.*"([^"]+)".*/\1/' | sort -u)

# Everything worth verifying this run; args add extra versions (e.g. probing a new release).
mapfile -t VERSIONS < <(printf '%s\n' "${STABLE_5X[@]}" "${PINNED[@]}" "$@" | sort -u)

ASM_BASE="https://repo1.maven.org/maven2/org/ow2/asm"
ASM_VER="9.7.1"

mkdir -p "$CACHE"
fetch() { [ -s "$2" ] || curl -fsSL -o "$2" "$1"; }
for m in asm asm-util asm-tree; do
  fetch "$ASM_BASE/$m/$ASM_VER/$m-$ASM_VER.jar" "$CACHE/$m-$ASM_VER.jar"
done

JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
CP="$CACHE/asm-$ASM_VER.jar:$CACHE/asm-util-$ASM_VER.jar:$CACHE/asm-tree-$ASM_VER.jar"

cat > "$CACHE/Dump.java" <<'EOF'
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.TraceClassVisitor;

/**
 * Textifier dump + one-method trampoline rewrite mirroring ConnectInterceptorRewriter, plus a
 * structural shape check mirroring dev.okhttpcronet.plugin.GuardSpec.verify on
 * RecipeRegistry.CANONICAL_GUARD (instruction strings recorded in the same format as the
 * plugin's RecordingMethodVisitor so the rule prefixes apply verbatim).
 *
 * stdout: "<sha256>", then "SHAPE canonical-5x MATCH|NOMATCH"; on NOMATCH the diverging
 * problems ("PROBLEM ...") and the full recorded instruction stream ("INSN ...") follow so
 * callers can report the exclusion reason.
 */
public class Dump {
    static final String INTERCEPT_NAME = "intercept";
    static final String INTERCEPT_DESC = "(Lokhttp3/Interceptor$Chain;)Lokhttp3/Response;";

    static final String SHAPE_NAME = "canonical-5x";
    // Mirrors RecipeRegistry.CANONICAL_GUARD exactly.
    static final String CAST_OWNER = "okhttp3/internal/http/RealInterceptorChain";
    static final int CAST_FIRST_MAX_INDEX = 4;
    static final int CAST_MIN = 1, CAST_MAX = 1;
    static final String[] REQUIRED_INVOKES = {
        "INVOKEVIRTUAL okhttp3/internal/connection/RealCall.initExchange$okhttp",
        "INVOKESTATIC okhttp3/internal/http/RealInterceptorChain.copy$okhttp$default ",
        "INVOKEVIRTUAL okhttp3/internal/http/RealInterceptorChain.proceed (Lokhttp3/Request;)",
    };

    public static void main(String[] args) throws Exception {
        byte[] in = Files.readAllBytes(Path.of(args[0]));
        dump(in, Path.of(args[1]));
        dump(rewrite(in), Path.of(args[2]));
        StringBuilder sb = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(in)) sb.append(String.format("%02x", b));
        System.out.println(sb);
        List<String> insns = new ArrayList<>();
        record(in, insns);
        List<String> problems = verify(insns);
        System.out.println("SHAPE " + SHAPE_NAME + (problems.isEmpty() ? " MATCH" : " NOMATCH"));
        for (String p : problems) System.out.println("PROBLEM " + p);
        if (!problems.isEmpty()) {
            for (String i : insns) System.out.println("INSN " + i);
        }
    }

    /** Same checks, order and message texts as GuardSpec.verify. */
    static List<String> verify(List<String> insns) {
        List<String> problems = new ArrayList<>();
        String checkcast = "CHECKCAST " + CAST_OWNER;
        int castIndex = insns.indexOf(checkcast);
        if (castIndex < 0 || castIndex > CAST_FIRST_MAX_INDEX) {
            problems.add("expected " + checkcast + " within the first " + (CAST_FIRST_MAX_INDEX + 1) + " instructions");
        }
        long castCount = insns.stream().filter(checkcast::equals).count();
        if (castCount < CAST_MIN || castCount > CAST_MAX) {
            problems.add("expected exactly one " + checkcast);
        }
        for (String prefix : REQUIRED_INVOKES) {
            long count = insns.stream().filter(s -> s.startsWith(prefix)).count();
            if (count != 1) {
                String name = prefix.split(" ")[1];
                name = name.substring(name.lastIndexOf('/') + 1);
                problems.add("expected exactly one " + name + " call");
            }
        }
        if (insns.isEmpty() || !insns.get(insns.size() - 1).equals("ARETURN")) {
            problems.add("expected method to end with ARETURN");
        }
        return problems;
    }

    /** Records the intercept instruction stream in the plugin's RecordingMethodVisitor format. */
    static void record(byte[] bytes, List<String> insns) {
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                if (!name.equals(INTERCEPT_NAME) || !descriptor.equals(INTERCEPT_DESC)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitInsn(int opcode) {
                        insns.add(NAME.getOrDefault(opcode, "0x" + Integer.toHexString(opcode)));
                    }
                    @Override public void visitIntInsn(int opcode, int operand) {
                        insns.add(NAME.getOrDefault(opcode, "?") + " " + operand);
                    }
                    @Override public void visitVarInsn(int opcode, int value) {
                        insns.add(NAME.getOrDefault(opcode, "?") + " " + value);
                    }
                    @Override public void visitTypeInsn(int opcode, String type) {
                        insns.add(NAME.getOrDefault(opcode, "?") + " " + type);
                    }
                    @Override public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                        insns.add(NAME.getOrDefault(opcode, "?") + " " + owner + "." + name + " " + descriptor);
                    }
                    @Override public void visitMethodInsn(int opcode, String owner, String name,
                            String descriptor, boolean isInterface) {
                        insns.add(NAME.getOrDefault(opcode, "?") + " " + owner + "." + name + " " + descriptor);
                    }
                    @Override public void visitJumpInsn(int opcode, org.objectweb.asm.Label label) {
                        insns.add(NAME.getOrDefault(opcode, "?") + " L" + System.identityHashCode(label));
                    }
                    @Override public void visitLdcInsn(Object value) { insns.add("LDC " + value); }
                    @Override public void visitIincInsn(int value, int increment) {
                        insns.add("IINC " + value + " " + increment);
                    }
                    @Override public void visitInvokeDynamicInsn(String name, String descriptor,
                            org.objectweb.asm.Handle handle, Object... args) {
                        insns.add("INVOKEDYNAMIC " + name + " " + descriptor);
                    }
                    @Override public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
                        insns.add("MULTIANEWARRAY " + descriptor + " " + numDimensions);
                    }
                    @Override public void visitTableSwitchInsn(int min, int max,
                            org.objectweb.asm.Label dflt, org.objectweb.asm.Label... labels) {
                        insns.add("TABLESWITCH " + min + " " + max);
                    }
                    @Override public void visitLookupSwitchInsn(org.objectweb.asm.Label dflt,
                            int[] keys, org.objectweb.asm.Label[] labels) {
                        StringBuilder sb = new StringBuilder("LOOKUPSWITCH ");
                        for (int k : keys) sb.append(k).append(",");
                        insns.add(sb.toString());
                    }
                };
            }
        }, 0);
    }

    // Readable names for the recorded stream; asm-util's Printer.OPCODES mirrors this but we
    // keep the plugin's hand-rolled map so both sides stay format-identical.
    static final Map<Integer, String> NAME = Map.ofEntries(
        Map.entry(Opcodes.NOP, "NOP"),
        Map.entry(Opcodes.ACONST_NULL, "ACONST_NULL"),
        Map.entry(Opcodes.ICONST_0, "ICONST_0"),
        Map.entry(Opcodes.ICONST_1, "ICONST_1"),
        Map.entry(Opcodes.ICONST_2, "ICONST_2"),
        Map.entry(Opcodes.ICONST_3, "ICONST_3"),
        Map.entry(Opcodes.ICONST_4, "ICONST_4"),
        Map.entry(Opcodes.ICONST_5, "ICONST_5"),
        Map.entry(Opcodes.ALOAD, "ALOAD"),
        Map.entry(Opcodes.ASTORE, "ASTORE"),
        Map.entry(Opcodes.ILOAD, "ILOAD"),
        Map.entry(Opcodes.ISTORE, "ISTORE"),
        Map.entry(Opcodes.DUP, "DUP"),
        Map.entry(Opcodes.POP, "POP"),
        Map.entry(Opcodes.CHECKCAST, "CHECKCAST"),
        Map.entry(Opcodes.IRETURN, "IRETURN"),
        Map.entry(Opcodes.ARETURN, "ARETURN"),
        Map.entry(Opcodes.RETURN, "RETURN"),
        Map.entry(Opcodes.ATHROW, "ATHROW"),
        Map.entry(Opcodes.GETSTATIC, "GETSTATIC"),
        Map.entry(Opcodes.PUTSTATIC, "PUTSTATIC"),
        Map.entry(Opcodes.GETFIELD, "GETFIELD"),
        Map.entry(Opcodes.PUTFIELD, "PUTFIELD"),
        Map.entry(Opcodes.INVOKEVIRTUAL, "INVOKEVIRTUAL"),
        Map.entry(Opcodes.INVOKESPECIAL, "INVOKESPECIAL"),
        Map.entry(Opcodes.INVOKESTATIC, "INVOKESTATIC"),
        Map.entry(Opcodes.INVOKEINTERFACE, "INVOKEINTERFACE"),
        Map.entry(Opcodes.NEW, "NEW"),
        Map.entry(Opcodes.MONITORENTER, "MONITORENTER"),
        Map.entry(Opcodes.MONITOREXIT, "MONITOREXIT"),
        Map.entry(Opcodes.IFNULL, "IFNULL"),
        Map.entry(Opcodes.IFNONNULL, "IFNONNULL"),
        Map.entry(Opcodes.LDC, "LDC")
    );

    static void dump(byte[] bytes, Path out) throws Exception {
        try (PrintWriter pw = new PrintWriter(out.toFile(), "UTF-8")) {
            new ClassReader(bytes).accept(new TraceClassVisitor(pw), 0);
        }
    }

    static byte[] rewrite(byte[] bytes) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                MethodVisitor target = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals(INTERCEPT_NAME) || !descriptor.equals(INTERCEPT_DESC)) return target;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitCode() {
                        target.visitCode();
                        target.visitVarInsn(Opcodes.ALOAD, 1);
                        target.visitMethodInsn(Opcodes.INVOKESTATIC, "dev/okhttpcronet/bridge/CronetBridge",
                                "intercept", INTERCEPT_DESC, false);
                        target.visitInsn(Opcodes.ARETURN);
                        target.visitMaxs(1, 2);
                    }
                    @Override public void visitEnd() { target.visitEnd(); }
                };
            }
        }, ClassReader.SKIP_FRAMES);
        return cw.toByteArray();
    }
}
EOF

"$JAVAC" -cp "$CP" -d "$CACHE/classes" "$CACHE/Dump.java"
DUMP_TOOL="$CACHE/classes:$CP"

SHAPE_NAME="canonical-5x"

# Per version: download both artifacts, extract ConnectInterceptor.class, dump + hash +
# structurally verify. THIRD_PARTY.md section lines and report rows accumulate here.
TP_LINES=""
KT_BODY=""
KT_WHEN=""
REPORT_ROWS=""
EXCLUDED_SECTIONS=""
declare -A VERIFIED_MAP=()

for V in "${VERSIONS[@]}"; do
  AAR_URL="https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp-android/$V/okhttp-android-$V.aar"
  JAR_URL="https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp-jvm/$V/okhttp-jvm-$V.jar"
  VRES="$RES/$V"
  mkdir -p "$VRES/android" "$VRES/jvm"

  fetch "$AAR_URL" "$CACHE/okhttp-android-$V.aar"
  fetch "$JAR_URL" "$CACHE/okhttp-jvm-$V.jar"

  rm -rf "$CACHE/aar-x-$V" "$CACHE/android-x-$V" "$CACHE/jvm-x-$V"
  unzip -q -o "$CACHE/okhttp-android-$V.aar" classes.jar -d "$CACHE/aar-x-$V"
  unzip -q -o "$CACHE/aar-x-$V/classes.jar" okhttp3/internal/connection/ConnectInterceptor.class -d "$CACHE/android-x-$V"
  unzip -q -o "$CACHE/okhttp-jvm-$V.jar" okhttp3/internal/connection/ConnectInterceptor.class -d "$CACHE/jvm-x-$V"

  cp "$CACHE/android-x-$V/okhttp3/internal/connection/ConnectInterceptor.class" "$VRES/android/ConnectInterceptor.class"
  cp "$CACHE/jvm-x-$V/okhttp3/internal/connection/ConnectInterceptor.class" "$VRES/jvm/ConnectInterceptor.class"

  ANDROID_OUT="$("$JAVA" -cp "$DUMP_TOOL" Dump "$VRES/android/ConnectInterceptor.class" "$VRES/android/stock.txt" "$VRES/android/rewritten.txt")"
  JVM_OUT="$("$JAVA" -cp "$DUMP_TOOL" Dump "$VRES/jvm/ConnectInterceptor.class" "$VRES/jvm/stock.txt" "$VRES/jvm/rewritten.txt")"
  ANDROID_HASH="$(grep -E '^[0-9a-f]{64}$' <<<"$ANDROID_OUT")"
  JVM_HASH="$(grep -E '^[0-9a-f]{64}$' <<<"$JVM_OUT")"
  AAR_SHA="$(sha256sum "$CACHE/okhttp-android-$V.aar" | cut -d' ' -f1)"
  JAR_SHA="$(sha256sum "$CACHE/okhttp-jvm-$V.jar" | cut -d' ' -f1)"

  if grep -q "SHAPE $SHAPE_NAME MATCH" <<<"$ANDROID_OUT" && grep -q "SHAPE $SHAPE_NAME MATCH" <<<"$JVM_OUT"; then
    VERIFIED_MAP[$V]=yes
    NOTES="structurally verified"
  else
    VERIFIED_MAP[$V]=no
    ALL_VERIFIED="no"
    REASON="ConnectInterceptor.intercept does not match the registered $SHAPE_NAME shape"
    NOTES="EXCLUDED: $REASON"
    EXCLUDED_SECTIONS+="### $V

$REASON. Problems reported by the shape check (android variant; jvm equivalent omitted):

\`\`\`
$(grep -E '^(PROBLEM|INSN) ' <<<"$ANDROID_OUT")
\`\`\`

"
  fi
  PINNED_CELL=no
  for P in "${PINNED[@]}"; do
    if [ "$P" = "$V" ]; then PINNED_CELL=yes; fi
  done
  REPORT_ROWS+="| $V | \`$ANDROID_HASH\` / \`$JVM_HASH\` | $( [ "${VERIFIED_MAP[$V]}" = yes ] && echo "$SHAPE_NAME" || echo '-' ) | $PINNED_CELL | $NOTES |
"

  TP_LINES+="- \`com.squareup.okhttp3:okhttp-android:$V\` AAR sha256: \`$AAR_SHA\`
  (source of stock/$V/android golden).
- \`com.squareup.okhttp3:okhttp-jvm:$V\` JAR sha256: \`$JAR_SHA\`
  (source of stock/$V/jvm golden).
- \`okhttp3/internal/connection/ConnectInterceptor.class\` sha256: android \`$ANDROID_HASH\`,
  jvm \`$JVM_HASH\` (shape check: ${VERIFIED_MAP[$V]}).
"
  KT_BODY+="    val OKHTTP_ANDROID_$(echo "$V" | tr '.-' '__') = \"$ANDROID_HASH\"
    val OKHTTP_JVM_$(echo "$V" | tr '.-' '__') = \"$JVM_HASH\"
"
  KT_WHEN+="        \"$V\" -> mapOf(Variant.ANDROID to OKHTTP_ANDROID_$(echo "$V" | tr '.-' '__'), Variant.JVM to OKHTTP_JVM_$(echo "$V" | tr '.-' '__'))
"

  echo "$V: android=$ANDROID_HASH jvm=$JVM_HASH verified=${VERIFIED_MAP[$V]}"
done

# Lie guard: a version pinned in RecipeRegistry.kt whose bytecode failed the shape check must
# stop everything - never keep a pin the evidence contradicts.
FAILURES=""
for P in "${PINNED[@]}"; do
  if [ "${VERIFIED_MAP[$P]:-missing}" != "yes" ]; then
    FAILURES+="pinned okhttp $P is not structurally verified (verdict: ${VERIFIED_MAP[$P]:-not processed this run})
"
  fi
done
if [ -n "$FAILURES" ]; then
  echo "FATAL: refusing to regenerate registry inputs; remove or fix the failing pins first:" >&2
  echo "$FAILURES" >&2
  exit 1
fi

# Pinned + verified versions feed the bridge runtime tripwire (same generation step as
# GoldenFingerprints so the two stay mechanically in sync).
VERIFIED_PINNED=""
for P in "${PINNED[@]}"; do
  VERIFIED_PINNED+="    \"$P\",
"
done

cat > "$BRIDGE_KT" <<EOF
package dev.okhttpcronet.bridge

// Generated by plugin-build/scripts/generate-fingerprints.sh: the okhttp versions pinned in
// RecipeRegistry and structurally verified against the registered guard (see
// plugin-build/recipe-verification-report.md). Do not hand-edit; rerun the script.
internal val VerifiedOkHttpVersions: Set<String> = setOf(
$VERIFIED_PINNED)
EOF

# Generated fingerprints consumed by RecipeRegistry (never hand-edit; rerun this script).
cat > "$GOLDEN_KT" <<EOF
package dev.okhttpcronet.plugin

// Generated by plugin-build/scripts/generate-fingerprints.sh from the pinned okhttp
// artifacts (see THIRD_PARTY.md "Golden bytecode artifacts"). Do not hand-edit.
object GoldenFingerprints {
$KT_BODY
    fun fingerprintsFor(version: String): Map<Variant, String>? = when (version) {
$KT_WHEN        else -> null
    }
}
EOF

REPORT_BODY="# OkHttp recipe verification report

Generated by \`plugin-build/scripts/generate-fingerprints.sh\` (pinned Maven Central artifacts).
Structural check = \`RecipeRegistry.CANONICAL_GUARD\` evaluated with ASM 9.7.1, mirroring
\`GuardSpec.verify\` (single CHECKCAST to okhttp3/internal/http/RealInterceptorChain within the
first 5 instructions, initExchange\$okhttp x1, copy\$okhttp\$default x1, proceed x1, ends ARETURN).
A version is pinned in \`RecipeRegistry\` only when BOTH variants match; versions matching no
registered shape are excluded, never force-fit. Do not hand-edit; rerun the script.

| okhttp version | ConnectInterceptor.class sha256 (android / jvm) | guard matched | pinned | notes |
|---|---|---|---|---|
$REPORT_ROWS
$EXCLUDED_SECTIONS"

printf '%s\n' "$REPORT_BODY" > "$REPORT"

TP="$ROOT_DIR/THIRD_PARTY.md"
if ! grep -q 'golden-bytecode:start' "$TP"; then
  printf '\n<!-- golden-bytecode:start -->\n<!-- golden-bytecode:end -->\n' >> "$TP"
fi
SECTION="## Golden bytecode artifacts
$TP_LINES- Regenerate: \`JAVA_HOME=<temurin-21> plugin-build/scripts/generate-fingerprints.sh [version ...]\`."
awk -v sec="$SECTION" '
  /<!-- golden-bytecode:start -->/ { print; print sec; skip = 1; next }
  /<!-- golden-bytecode:end -->/   { skip = 0; print; next }
  !skip { print }
' "$TP" > "$TP.tmp" && mv "$TP.tmp" "$TP"
