#!/usr/bin/env bash
# Generate golden stock/rewritten ConnectInterceptor artifacts for okhttp 5.5.0.
#
# Deterministic + re-runnable: pinned Maven Central URLs, pinned ASM 9.7.1 for the
# Textifier dump tool. Writes into plugin/src/test/resources/stock/ and refreshes the
# "Golden bytecode artifacts" section of THIRD_PARTY.md (between the marker comments).
#
# rewritten.txt mirrors dev.okhttpcronet.plugin.ConnectInterceptorRewriter's algorithm
# (discard intercept body, emit ALOAD 1 / INVOKESTATIC CronetBridge.intercept / ARETURN,
# COMPUTE_FRAMES); the unit tests assert the instruction list independently.
#
# Usage: JAVA_HOME=<temurin-21> ./generate-fingerprints.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLUGIN_DIR="$(cd "$SCRIPT_DIR/../plugin" && pwd)"   # plugin-build/plugin
ROOT_DIR="$(cd "$PLUGIN_DIR/../.." && pwd)"         # repo root
RES="$PLUGIN_DIR/src/test/resources/stock"
CACHE="$PLUGIN_DIR/build/golden-cache"

AAR_URL="https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp-android/5.5.0/okhttp-android-5.5.0.aar"
JAR_URL="https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp-jvm/5.5.0/okhttp-jvm-5.5.0.jar"
ASM_BASE="https://repo1.maven.org/maven2/org/ow2/asm"
ASM_VER="9.7.1"

mkdir -p "$CACHE" "$RES/android" "$RES/jvm"

fetch() { [ -s "$2" ] || curl -fsSL -o "$2" "$1"; }
fetch "$AAR_URL" "$CACHE/okhttp-android-5.5.0.aar"
fetch "$JAR_URL" "$CACHE/okhttp-jvm-5.5.0.jar"
for m in asm asm-util asm-tree; do
  fetch "$ASM_BASE/$m/$ASM_VER/$m-$ASM_VER.jar" "$CACHE/$m-$ASM_VER.jar"
done

rm -rf "$CACHE/aar-x" "$CACHE/android-x" "$CACHE/jvm-x"
unzip -q -o "$CACHE/okhttp-android-5.5.0.aar" classes.jar -d "$CACHE/aar-x"
unzip -q -o "$CACHE/aar-x/classes.jar" okhttp3/internal/connection/ConnectInterceptor.class -d "$CACHE/android-x"
unzip -q -o "$CACHE/okhttp-jvm-5.5.0.jar" okhttp3/internal/connection/ConnectInterceptor.class -d "$CACHE/jvm-x"

cp "$CACHE/android-x/okhttp3/internal/connection/ConnectInterceptor.class" "$RES/android/ConnectInterceptor.class"
cp "$CACHE/jvm-x/okhttp3/internal/connection/ConnectInterceptor.class" "$RES/jvm/ConnectInterceptor.class"

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

ANDROID_HASH="$("$JAVA" -cp "$DUMP_TOOL" Dump "$RES/android/ConnectInterceptor.class" "$RES/android/stock.txt" "$RES/android/rewritten.txt")"
JVM_HASH="$("$JAVA" -cp "$DUMP_TOOL" Dump "$RES/jvm/ConnectInterceptor.class" "$RES/jvm/stock.txt" "$RES/jvm/rewritten.txt")"
AAR_SHA="$(sha256sum "$CACHE/okhttp-android-5.5.0.aar" | cut -d' ' -f1)"
JAR_SHA="$(sha256sum "$CACHE/okhttp-jvm-5.5.0.jar" | cut -d' ' -f1)"

TP="$ROOT_DIR/THIRD_PARTY.md"
if ! grep -q 'golden-bytecode:start' "$TP"; then
  printf '\n<!-- golden-bytecode:start -->\n<!-- golden-bytecode:end -->\n' >> "$TP"
fi
SECTION="## Golden bytecode artifacts
- \`com.squareup.okhttp3:okhttp-android:5.5.0\` AAR sha256: \`$AAR_SHA\`
  (source of stock/android golden).
- \`com.squareup.okhttp3:okhttp-jvm:5.5.0\` JAR sha256: \`$JAR_SHA\`
  (source of stock/jvm golden).
- \`okhttp3/internal/connection/ConnectInterceptor.class\` sha256: android \`$ANDROID_HASH\`,
  jvm \`$JVM_HASH\`.
- Regenerate: \`JAVA_HOME=<temurin-21> plugin-build/scripts/generate-fingerprints.sh\`."
awk -v sec="$SECTION" '
  /<!-- golden-bytecode:start -->/ { print; print sec; skip = 1; next }
  /<!-- golden-bytecode:end -->/   { skip = 0; print; next }
  !skip { print }
' "$TP" > "$TP.tmp" && mv "$TP.tmp" "$TP"

echo "okhttp-android-5.5.0.aar sha256: $AAR_SHA"
echo "okhttp-jvm-5.5.0.jar     sha256: $JAR_SHA"
echo "ConnectInterceptor.class (android) sha256: $ANDROID_HASH"
echo "ConnectInterceptor.class (jvm)     sha256: $JVM_HASH"
