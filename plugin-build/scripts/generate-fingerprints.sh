#!/usr/bin/env bash
# Generate golden stock/rewritten ConnectInterceptor artifacts for every okhttp version in
# the recipe list (default: 5.5.0), plus the generated GoldenFingerprints.kt consumed by
# RecipeRegistry.
#
# Deterministic + re-runnable: pinned Maven Central URLs, pinned ASM 9.7.1 for the
# Textifier dump tool. Writes into plugin/src/test/resources/stock/<version>/{android,jvm}/
# and refreshes the "Golden bytecode artifacts" section of THIRD_PARTY.md (between the
# marker comments).
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

VERSIONS=("$@")
if [ ${#VERSIONS[@]} -eq 0 ]; then
  VERSIONS=(5.5.0)
fi

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
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.TraceClassVisitor;

/** Textifier dump + one-method trampoline rewrite mirroring ConnectInterceptorRewriter. */
public class Dump {
    static final String INTERCEPT_DESC = "(Lokhttp3/Interceptor$Chain;)Lokhttp3/Response;";

    public static void main(String[] args) throws Exception {
        byte[] in = Files.readAllBytes(Path.of(args[0]));
        dump(in, Path.of(args[1]));
        dump(rewrite(in), Path.of(args[2]));
        StringBuilder sb = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(in)) sb.append(String.format("%02x", b));
        System.out.println(sb);
    }

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
                if (!name.equals("intercept") || !descriptor.equals(INTERCEPT_DESC)) return target;
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

# Per version: download both artifacts, extract ConnectInterceptor.class, dump + hash.
# THIRD_PARTY.md section lines accumulate here.
TP_LINES=""
KT_BODY=""

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

  ANDROID_HASH="$("$JAVA" -cp "$DUMP_TOOL" Dump "$VRES/android/ConnectInterceptor.class" "$VRES/android/stock.txt" "$VRES/android/rewritten.txt")"
  JVM_HASH="$("$JAVA" -cp "$DUMP_TOOL" Dump "$VRES/jvm/ConnectInterceptor.class" "$VRES/jvm/stock.txt" "$VRES/jvm/rewritten.txt")"
  AAR_SHA="$(sha256sum "$CACHE/okhttp-android-$V.aar" | cut -d' ' -f1)"
  JAR_SHA="$(sha256sum "$CACHE/okhttp-jvm-$V.jar" | cut -d' ' -f1)"

  TP_LINES+="- \`com.squareup.okhttp3:okhttp-android:$V\` AAR sha256: \`$AAR_SHA\`
  (source of stock/$V/android golden).
- \`com.squareup.okhttp3:okhttp-jvm:$V\` JAR sha256: \`$JAR_SHA\`
  (source of stock/$V/jvm golden).
- \`okhttp3/internal/connection/ConnectInterceptor.class\` sha256: android \`$ANDROID_HASH\`,
  jvm \`$JVM_HASH\`.
"
  KT_BODY+="    val OKHTTP_ANDROID_$(echo "$V" | tr '.' '_') = \"$ANDROID_HASH\"
    val OKHTTP_JVM_$(echo "$V" | tr '.' '_') = \"$JVM_HASH\"
"

  echo "okhttp-android-$V.aar sha256: $AAR_SHA"
  echo "okhttp-jvm-$V.jar     sha256: $JAR_SHA"
  echo "ConnectInterceptor.class (android) sha256: $ANDROID_HASH"
  echo "ConnectInterceptor.class (jvm)     sha256: $JVM_HASH"
done

# Generated fingerprints consumed by RecipeRegistry (never hand-edit; rerun this script).
cat > "$GOLDEN_KT" <<EOF
package dev.okhttpcronet.plugin

// Generated by plugin-build/scripts/generate-fingerprints.sh from the pinned okhttp
// artifacts (see THIRD_PARTY.md "Golden bytecode artifacts"). Do not hand-edit.
object GoldenFingerprints {
$KT_BODY}
EOF

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
