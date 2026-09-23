#!/usr/bin/env bash
# Generate golden stock/rewritten artifacts for every released okhttp 5.x version,
# structurally verify each against the registered guard shapes, and regenerate:
#   - plugin/src/test/resources/stock/<version>/{android,jvm}/ (goldens + Textifier dumps)
#   - plugin/src/main/kotlin/sarie/plugin/GoldenFingerprints.kt
#   - bridge/src/main/kotlin/sarie/bridge/VerifiedOkHttpVersions.kt
#   - plugin/recipe-verification-report.md (version -> guard -> pinned/excluded)
#
# Deterministic + re-runnable: pinned Maven Central URLs, pinned ASM 9.7.1 for the dump +
# shape-check tool. Versions processed = released 5.x list ∪ registry-pinned versions ∪ args,
# so every run re-verifies every pinned version and refuses to keep a pinned version whose
# bytecode no longer matches the registered shape (never force-fit, never pin a lie).
#
# rewritten.txt mirrors sarie.plugin.ConnectInterceptorRewriter's algorithm
# (discard intercept body, emit ALOAD 1 / INVOKESTATIC CronetBridge.intercept / ARETURN,
# COMPUTE_FRAMES); the unit tests assert the instruction list independently.
#
# Usage: JAVA_HOME=<temurin-21> ./generate-fingerprints.sh [okhttp-version ...]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLUGIN_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"          # plugin/
ROOT_DIR="$(cd "$PLUGIN_DIR/.." && pwd)"            # repo root
RES="$PLUGIN_DIR/src/test/resources/stock"
CACHE="$PLUGIN_DIR/build/golden-cache"
GOLDEN_KT="$PLUGIN_DIR/src/main/kotlin/sarie/plugin/GoldenFingerprints.kt"
BRIDGE_KT="$ROOT_DIR/bridge/src/main/kotlin/sarie/bridge/VerifiedOkHttpVersions.kt"
REPORT="$PLUGIN_DIR/recipe-verification-report.md"
REGISTRY_KT="$PLUGIN_DIR/src/main/kotlin/sarie/plugin/RecipeRegistry.kt"

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
 * structural shape check mirroring sarie.plugin.GuardSpec.verify on
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

    static final String[] CALL_SERVER_PREFIX = {
        "ALOAD 1",
        "LDC chain",
        "INVOKESTATIC kotlin/jvm/internal/Intrinsics.checkNotNullParameter (Ljava/lang/Object;Ljava/lang/String;)V",
        "ALOAD 1",
        "CHECKCAST okhttp3/internal/http/RealInterceptorChain",
        "ASTORE 2",
        "ALOAD 2",
        "INVOKEVIRTUAL okhttp3/internal/http/RealInterceptorChain.getExchange$okhttp ()Lokhttp3/internal/connection/Exchange;",
        "DUP",
        "INVOKESTATIC kotlin/jvm/internal/Intrinsics.checkNotNull (Ljava/lang/Object;)V",
        "ASTORE 3",
    };

    static final String ENTRY_INIT = "<init>";
    static final String ENTRY_INIT_DESC = "(Lokio/Source;)V";
    static final String COMPUTE_CANDIDATE = "computeCandidate";
    static final String COMPUTE_CANDIDATE_DESC = "()Lokhttp3/internal/cache/CacheStrategy;";

    public static void main(String[] args) throws Exception {
        byte[] in = Files.readAllBytes(Path.of(args[0]));
        String mode = args.length > 3 ? args[3] : "connect";
        dump(in, Path.of(args[1]));
        byte[] rewritten;
        if (mode.equals("callserver")) rewritten = rewriteCallServer(in);
        else if (mode.equals("cacheentry")) rewritten = rewriteCacheEntry(in);
        else if (mode.equals("cachestrategy")) rewritten = rewriteCacheStrategy(in);
        else rewritten = rewrite(in);
        dump(rewritten, Path.of(args[2]));
        StringBuilder sb = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(in)) sb.append(String.format("%02x", b));
        System.out.println(sb);
        List<String> insns = new ArrayList<>();
        String shape;
        List<String> problems;
        if (mode.equals("callserver")) {
            record(in, insns, INTERCEPT_NAME, INTERCEPT_DESC);
            problems = verifyCallServerPrefix(insns);
            shape = "callserver-prefix";
        } else if (mode.equals("cacheentry")) {
            record(in, insns, ENTRY_INIT, ENTRY_INIT_DESC);
            problems = verifyCacheEntry(insns);
            shape = "cache-entry";
        } else if (mode.equals("cachestrategy")) {
            record(in, insns, COMPUTE_CANDIDATE, COMPUTE_CANDIDATE_DESC);
            problems = verifyCacheStrategy(insns);
            shape = "cache-strategy";
        } else {
            record(in, insns, INTERCEPT_NAME, INTERCEPT_DESC);
            problems = verify(insns);
            shape = SHAPE_NAME;
        }
        System.out.println("SHAPE " + shape + (problems.isEmpty() ? " MATCH" : " NOMATCH"));
        for (String p : problems) System.out.println("PROBLEM " + p);
        if (!problems.isEmpty()) {
            for (String i : insns) System.out.println("INSN " + i);
        }
    }

    /** Mirrors CacheEntryGuard: one HttpUrl.isHttps, local 6 is the Okio.buffer result. */
    static List<String> verifyCacheEntry(List<String> insns) {
        List<String> problems = new ArrayList<>();
        String https = "INVOKEVIRTUAL okhttp3/HttpUrl.isHttps ()Z";
        String buffer = "INVOKESTATIC okio/Okio.buffer (Lokio/Source;)Lokio/BufferedSource;";
        long httpsCount = insns.stream().filter(https::equals).count();
        if (httpsCount != 1) {
            problems.add("expected exactly one HttpUrl.isHttps in Cache.Entry.<init>(Source), found " + httpsCount);
        }
        long storeCount = insns.stream().filter(s -> s.equals("ASTORE 6")).count();
        if (storeCount != 1) {
            problems.add("expected exactly one ASTORE 6 (Okio.buffer result), found " + storeCount);
        }
        int bufferIdx = insns.indexOf(buffer);
        if (bufferIdx < 0 || bufferIdx + 1 >= insns.size() || !insns.get(bufferIdx + 1).equals("ASTORE 6")) {
            problems.add("local 6 is not the Okio.buffer result");
        }
        return problems;
    }

    /**
     * Mirrors CacheStrategyGuard. The stock `&&` compiles to isHttps; ifeq L; then
     * handshake(); ifnonnull L. Both jumps share L. isHttps is the only one replaced.
     */
    static List<String> verifyCacheStrategy(List<String> insns) {
        List<String> problems = new ArrayList<>();
        String https = "INVOKEVIRTUAL okhttp3/Request.isHttps ()Z";
        int at = -1;
        int count = 0;
        for (int i = 0; i < insns.size(); i++) {
            if (insns.get(i).equals(https)) {
                count++;
                at = i;
            }
        }
        if (count != 1) {
            problems.add("expected exactly one Request.isHttps in computeCandidate, found " + count);
            return problems;
        }
        if (at + 5 >= insns.size() || !cacheStrategyWindow(insns, at)) {
            problems.add("Request.isHttps must be followed by the handshake(); ifnonnull sequence");
        }
        return problems;
    }

    static boolean cacheStrategyWindow(List<String> insns, int at) {
        String ifeq = insns.get(at + 1);
        String ifnn = insns.get(at + 5);
        if (!ifeq.startsWith("IFEQ ") || !ifnn.startsWith("IFNONNULL ")) return false;
        if (!ifeq.substring("IFEQ ".length()).equals(ifnn.substring("IFNONNULL ".length()))) return false;
        return insns.get(at + 2).equals("ALOAD 0")
                && insns.get(at + 3).equals(
                        "GETFIELD okhttp3/internal/cache/CacheStrategy$Factory.cacheResponse Lokhttp3/Response;")
                && insns.get(at + 4).equals("INVOKEVIRTUAL okhttp3/Response.handshake ()Lokhttp3/Handshake;");
    }

    /** F7 prefix of CallServerInterceptor.intercept. Label ids are not part of the prefix. */
    static List<String> verifyCallServerPrefix(List<String> insns) {
        List<String> problems = new ArrayList<>();
        if (insns.size() < CALL_SERVER_PREFIX.length) {
            problems.add("expected CallServerInterceptor.intercept to start with the pinned F7 prefix");
            return problems;
        }
        for (int i = 0; i < CALL_SERVER_PREFIX.length; i++) {
            if (!insns.get(i).equals(CALL_SERVER_PREFIX[i])) {
                problems.add("expected CallServerInterceptor.intercept to start with the pinned F7 prefix");
                return problems;
            }
        }
        return problems;
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

    /** Records one method in the plugin's RecordingMethodVisitor format. */
    static void record(byte[] bytes, List<String> insns, String methodName, String methodDesc) {
        LABELS.clear();
        labelSeq = 0;
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                if (!name.equals(methodName) || !descriptor.equals(methodDesc)) return null;
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
                        insns.add(NAME.getOrDefault(opcode, "?") + " L" + labelId(label));
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
    static final java.util.IdentityHashMap<org.objectweb.asm.Label, Integer> LABELS =
        new java.util.IdentityHashMap<>();
    static int labelSeq = 0;
    static String labelId(org.objectweb.asm.Label label) {
        Integer id = LABELS.get(label);
        if (id == null) {
            id = labelSeq++;
            LABELS.put(label, id);
        }
        return "L" + id;
    }

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
        Map.entry(Opcodes.IFEQ, "IFEQ"),
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
                        target.visitMethodInsn(Opcodes.INVOKESTATIC, "sarie/bridge/CronetBridge",
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

    /** Prefix injection after the 3-instruction Kotlin preamble. Frames use a lenient hierarchy. */
    static byte[] rewriteCallServer(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter cw = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                try {
                    return super.getCommonSuperClass(type1, type2);
                } catch (RuntimeException e) {
                    return "java/lang/Object";
                }
            }
        };
        reader.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                MethodVisitor target = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals(INTERCEPT_NAME) || !descriptor.equals(INTERCEPT_DESC)) return target;
                return new MethodVisitor(Opcodes.ASM9) {
                    int instructions = 0;
                    boolean injected = false;
                    @Override public void visitCode() { target.visitCode(); }
                    @Override public void visitInsn(int opcode) {
                        target.visitInsn(opcode);
                        afterInstruction(target);
                    }
                    @Override public void visitIntInsn(int opcode, int operand) {
                        target.visitIntInsn(opcode, operand);
                        afterInstruction(target);
                    }
                    @Override public void visitVarInsn(int opcode, int value) {
                        target.visitVarInsn(opcode, value);
                        afterInstruction(target);
                    }
                    @Override public void visitTypeInsn(int opcode, String type) {
                        target.visitTypeInsn(opcode, type);
                        afterInstruction(target);
                    }
                    @Override public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                        target.visitFieldInsn(opcode, owner, name, descriptor);
                        afterInstruction(target);
                    }
                    @Override public void visitMethodInsn(int opcode, String owner, String name,
                            String descriptor, boolean isInterface) {
                        target.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                        afterInstruction(target);
                    }
                    @Override public void visitJumpInsn(int opcode, org.objectweb.asm.Label label) {
                        target.visitJumpInsn(opcode, label);
                        afterInstruction(target);
                    }
                    @Override public void visitLabel(org.objectweb.asm.Label label) { target.visitLabel(label); }
                    @Override public void visitLdcInsn(Object value) {
                        target.visitLdcInsn(value);
                        afterInstruction(target);
                    }
                    @Override public void visitIincInsn(int value, int increment) {
                        target.visitIincInsn(value, increment);
                        afterInstruction(target);
                    }
                    @Override public void visitTryCatchBlock(org.objectweb.asm.Label start, org.objectweb.asm.Label end,
                            org.objectweb.asm.Label handler, String type) {
                        target.visitTryCatchBlock(start, end, handler, type);
                    }
                    @Override public void visitLineNumber(int line, org.objectweb.asm.Label start) {
                        target.visitLineNumber(line, start);
                    }
                    @Override public void visitMaxs(int maxStack, int maxLocals) {
                        target.visitMaxs(maxStack + 2, maxLocals);
                    }
                    @Override public void visitEnd() { target.visitEnd(); }
                    void afterInstruction(MethodVisitor target) {
                        instructions++;
                        if (!injected && instructions == 3) {
                            org.objectweb.asm.Label stock = new org.objectweb.asm.Label();
                            target.visitVarInsn(Opcodes.ALOAD, 1);
                            target.visitMethodInsn(Opcodes.INVOKESTATIC, "sarie/bridge/CronetBridge",
                                    "callServer", INTERCEPT_DESC, false);
                            target.visitInsn(Opcodes.DUP);
                            target.visitJumpInsn(Opcodes.IFNULL, stock);
                            target.visitInsn(Opcodes.ARETURN);
                            target.visitLabel(stock);
                            target.visitInsn(Opcodes.POP);
                            injected = true;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
        return cw.toByteArray();
    }

    static ClassWriter framingWriter(ClassReader reader) {
        return new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                try {
                    return super.getCommonSuperClass(type1, type2);
                } catch (RuntimeException e) {
                    return "java/lang/Object";
                }
            }
        };
    }

    /** Replaces the one HttpUrl.isHttps in Cache.Entry.<init>(Source). writeTo is untouched. */
    static byte[] rewriteCacheEntry(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter cw = framingWriter(reader);
        reader.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                MethodVisitor target = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals(ENTRY_INIT) || !descriptor.equals(ENTRY_INIT_DESC)) return target;
                return new MethodVisitor(Opcodes.ASM9, target) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                        if (opcode == Opcodes.INVOKEVIRTUAL && owner.equals("okhttp3/HttpUrl")
                                && name.equals("isHttps") && descriptor.equals("()Z")) {
                            target.visitVarInsn(Opcodes.ALOAD, 6);
                            target.visitMethodInsn(Opcodes.INVOKESTATIC, "sarie/bridge/CacheHooks",
                                    "expectTlsBlock", "(Lokhttp3/HttpUrl;Lokio/BufferedSource;)Z", false);
                        } else {
                            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
        return cw.toByteArray();
    }

    /** Replaces the one Request.isHttps in computeCandidate. The handshake sequence stays. */
    static byte[] rewriteCacheStrategy(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter cw = framingWriter(reader);
        reader.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                MethodVisitor target = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals(COMPUTE_CANDIDATE) || !descriptor.equals(COMPUTE_CANDIDATE_DESC)) return target;
                return new MethodVisitor(Opcodes.ASM9, target) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                        if (opcode == Opcodes.INVOKEVIRTUAL && owner.equals("okhttp3/Request")
                                && name.equals("isHttps") && descriptor.equals("()Z")) {
                            target.visitMethodInsn(Opcodes.INVOKESTATIC, "sarie/bridge/CacheHooks",
                                    "requireHandshake", "(Lokhttp3/Request;)Z", false);
                        } else {
                            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                        }
                    }
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
# structurally verify. Report rows accumulate here.
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

  VER_ID="$(echo "$V" | tr '.-' '__')"
  KT_BODY+="    val CONNECT_INTERCEPTOR_ANDROID_${VER_ID} = \"$ANDROID_HASH\"
    val CONNECT_INTERCEPTOR_JVM_${VER_ID} = \"$JVM_HASH\"
"
  CALL_MAP=""
  CACHE_MAP=""
  CS_CELL="-"
  CACHE_CELL="-"
  # Historical dumps stay ConnectInterceptor-only. Pinned versions also fingerprint
  # CallServerInterceptor and both cache sites on both artifacts; a shape miss fails the pin.
  if [ "$PINNED_CELL" = yes ]; then
    rm -rf "$CACHE/android-cs-$V" "$CACHE/jvm-cs-$V"
    unzip -q -o "$CACHE/aar-x-$V/classes.jar" okhttp3/internal/http/CallServerInterceptor.class -d "$CACHE/android-cs-$V"
    unzip -q -o "$CACHE/okhttp-jvm-$V.jar" okhttp3/internal/http/CallServerInterceptor.class -d "$CACHE/jvm-cs-$V"
    cp "$CACHE/android-cs-$V/okhttp3/internal/http/CallServerInterceptor.class" "$VRES/android/CallServerInterceptor.class"
    cp "$CACHE/jvm-cs-$V/okhttp3/internal/http/CallServerInterceptor.class" "$VRES/jvm/CallServerInterceptor.class"
    CS_ANDROID_OUT="$("$JAVA" -cp "$DUMP_TOOL" Dump "$VRES/android/CallServerInterceptor.class" "$VRES/android/CallServerInterceptor.stock.txt" "$VRES/android/CallServerInterceptor.rewritten.txt" callserver)"
    CS_JVM_OUT="$("$JAVA" -cp "$DUMP_TOOL" Dump "$VRES/jvm/CallServerInterceptor.class" "$VRES/jvm/CallServerInterceptor.stock.txt" "$VRES/jvm/CallServerInterceptor.rewritten.txt" callserver)"
    CS_ANDROID_HASH="$(grep -E '^[0-9a-f]{64}$' <<<"$CS_ANDROID_OUT")"
    CS_JVM_HASH="$(grep -E '^[0-9a-f]{64}$' <<<"$CS_JVM_OUT")"
    KT_BODY+="    val CALL_SERVER_INTERCEPTOR_ANDROID_${VER_ID} = \"$CS_ANDROID_HASH\"
    val CALL_SERVER_INTERCEPTOR_JVM_${VER_ID} = \"$CS_JVM_HASH\"
"
    CALL_MAP=",
            InstrumentTarget.CALL_SERVER_INTERCEPTOR to mapOf(
                Variant.ANDROID to CALL_SERVER_INTERCEPTOR_ANDROID_${VER_ID},
                Variant.JVM to CALL_SERVER_INTERCEPTOR_JVM_${VER_ID},
            )"
    if grep -q "SHAPE callserver-prefix MATCH" <<<"$CS_ANDROID_OUT" && grep -q "SHAPE callserver-prefix MATCH" <<<"$CS_JVM_OUT"; then
      CS_CELL="callserver-prefix"
    else
      VERIFIED_MAP[$V]=no
      CS_CELL="NOMATCH"
      REASON="CallServerInterceptor.intercept does not match the pinned F7 prefix"
      NOTES="EXCLUDED: $REASON"
      EXCLUDED_SECTIONS+="### $V CallServerInterceptor

$REASON. Problems reported by the shape check (android variant; jvm equivalent omitted):

\`\`\`
$(grep -E '^(PROBLEM|INSN) ' <<<"$CS_ANDROID_OUT")
\`\`\`

"
    fi
    echo "$V callserver: android=$CS_ANDROID_HASH jvm=$CS_JVM_HASH shape=$CS_CELL"

    rm -rf "$CACHE/android-ce-$V" "$CACHE/jvm-ce-$V" "$CACHE/android-cf-$V" "$CACHE/jvm-cf-$V"
    unzip -q -o "$CACHE/aar-x-$V/classes.jar" 'okhttp3/Cache$Entry.class' -d "$CACHE/android-ce-$V"
    unzip -q -o "$CACHE/okhttp-jvm-$V.jar" 'okhttp3/Cache$Entry.class' -d "$CACHE/jvm-ce-$V"
    unzip -q -o "$CACHE/aar-x-$V/classes.jar" 'okhttp3/internal/cache/CacheStrategy$Factory.class' -d "$CACHE/android-cf-$V"
    unzip -q -o "$CACHE/okhttp-jvm-$V.jar" 'okhttp3/internal/cache/CacheStrategy$Factory.class' -d "$CACHE/jvm-cf-$V"
    cp "$CACHE/android-ce-$V/okhttp3/Cache\$Entry.class" "$VRES/android/Cache\$Entry.class"
    cp "$CACHE/jvm-ce-$V/okhttp3/Cache\$Entry.class" "$VRES/jvm/Cache\$Entry.class"
    cp "$CACHE/android-cf-$V/okhttp3/internal/cache/CacheStrategy\$Factory.class" "$VRES/android/CacheStrategy\$Factory.class"
    cp "$CACHE/jvm-cf-$V/okhttp3/internal/cache/CacheStrategy\$Factory.class" "$VRES/jvm/CacheStrategy\$Factory.class"
    CE_ANDROID_OUT="$("$JAVA" -cp "$DUMP_TOOL" Dump "$VRES/android/Cache\$Entry.class" "$VRES/android/Cache\$Entry.stock.txt" "$VRES/android/Cache\$Entry.rewritten.txt" cacheentry)"
    CE_JVM_OUT="$("$JAVA" -cp "$DUMP_TOOL" Dump "$VRES/jvm/Cache\$Entry.class" "$VRES/jvm/Cache\$Entry.stock.txt" "$VRES/jvm/Cache\$Entry.rewritten.txt" cacheentry)"
    CF_ANDROID_OUT="$("$JAVA" -cp "$DUMP_TOOL" Dump "$VRES/android/CacheStrategy\$Factory.class" "$VRES/android/CacheStrategy\$Factory.stock.txt" "$VRES/android/CacheStrategy\$Factory.rewritten.txt" cachestrategy)"
    CF_JVM_OUT="$("$JAVA" -cp "$DUMP_TOOL" Dump "$VRES/jvm/CacheStrategy\$Factory.class" "$VRES/jvm/CacheStrategy\$Factory.stock.txt" "$VRES/jvm/CacheStrategy\$Factory.rewritten.txt" cachestrategy)"
    CE_ANDROID_HASH="$(grep -E '^[0-9a-f]{64}$' <<<"$CE_ANDROID_OUT")"
    CE_JVM_HASH="$(grep -E '^[0-9a-f]{64}$' <<<"$CE_JVM_OUT")"
    CF_ANDROID_HASH="$(grep -E '^[0-9a-f]{64}$' <<<"$CF_ANDROID_OUT")"
    CF_JVM_HASH="$(grep -E '^[0-9a-f]{64}$' <<<"$CF_JVM_OUT")"
    KT_BODY+="    val CACHE_ENTRY_ANDROID_${VER_ID} = \"$CE_ANDROID_HASH\"
    val CACHE_ENTRY_JVM_${VER_ID} = \"$CE_JVM_HASH\"
    val CACHE_STRATEGY_FACTORY_ANDROID_${VER_ID} = \"$CF_ANDROID_HASH\"
    val CACHE_STRATEGY_FACTORY_JVM_${VER_ID} = \"$CF_JVM_HASH\"
"
    CACHE_MAP=",
            InstrumentTarget.CACHE_ENTRY to mapOf(
                Variant.ANDROID to CACHE_ENTRY_ANDROID_${VER_ID},
                Variant.JVM to CACHE_ENTRY_JVM_${VER_ID},
            ),
            InstrumentTarget.CACHE_STRATEGY_FACTORY to mapOf(
                Variant.ANDROID to CACHE_STRATEGY_FACTORY_ANDROID_${VER_ID},
                Variant.JVM to CACHE_STRATEGY_FACTORY_JVM_${VER_ID},
            )"
    CE_OK=no
    CF_OK=no
    if grep -q "SHAPE cache-entry MATCH" <<<"$CE_ANDROID_OUT" && grep -q "SHAPE cache-entry MATCH" <<<"$CE_JVM_OUT"; then
      CE_OK=yes
    fi
    if grep -q "SHAPE cache-strategy MATCH" <<<"$CF_ANDROID_OUT" && grep -q "SHAPE cache-strategy MATCH" <<<"$CF_JVM_OUT"; then
      CF_OK=yes
    fi
    if [ "$CE_OK" = yes ] && [ "$CF_OK" = yes ]; then
      CACHE_CELL="entry+strategy"
    else
      VERIFIED_MAP[$V]=no
      CACHE_CELL="NOMATCH"
      REASON="cache site shape mismatch (entry=$CE_OK strategy=$CF_OK)"
      if [ "$NOTES" = "structurally verified" ]; then
        NOTES="EXCLUDED: $REASON"
      else
        NOTES="$NOTES; $REASON"
      fi
      EXCLUDED_SECTIONS+="### $V cache sites

$REASON. Problems reported by the shape check (android variant; jvm equivalent omitted):

\`\`\`
$(grep -E '^(PROBLEM|INSN) ' <<<"$CE_ANDROID_OUT")
$(grep -E '^(PROBLEM|INSN) ' <<<"$CF_ANDROID_OUT")
\`\`\`

"
    fi
    echo "$V cache-entry: android=$CE_ANDROID_HASH jvm=$CE_JVM_HASH shape=$CE_OK"
    echo "$V cache-strategy: android=$CF_ANDROID_HASH jvm=$CF_JVM_HASH shape=$CF_OK"
  fi

  REPORT_ROWS+="| $V | \`$ANDROID_HASH\` / \`$JVM_HASH\` | $( [ "${VERIFIED_MAP[$V]}" = yes ] && echo "$SHAPE_NAME" || echo '-' ) | $PINNED_CELL | $CS_CELL | $CACHE_CELL | $NOTES |
"

  KT_WHEN+="        \"$V\" -> mapOf(
            InstrumentTarget.CONNECT_INTERCEPTOR to mapOf(
                Variant.ANDROID to CONNECT_INTERCEPTOR_ANDROID_${VER_ID},
                Variant.JVM to CONNECT_INTERCEPTOR_JVM_${VER_ID},
            )${CALL_MAP}${CACHE_MAP},
        )
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
package sarie.bridge

// Generated by plugin/scripts/generate-fingerprints.sh: the okhttp versions pinned in
// RecipeRegistry and structurally verified against the registered guard (see
// plugin/recipe-verification-report.md). Do not hand-edit; rerun the script.
internal val VerifiedOkHttpVersions: Set<String> = setOf(
$VERIFIED_PINNED)
EOF

# Generated fingerprints consumed by RecipeRegistry (never hand-edit; rerun this script).
cat > "$GOLDEN_KT" <<EOF
package sarie.plugin

// Generated by plugin/scripts/generate-fingerprints.sh from the pinned okhttp
// artifacts (see plugin/recipe-verification-report.md). Do not hand-edit.
object GoldenFingerprints {
$KT_BODY
    fun fingerprintsFor(version: String): Map<InstrumentTarget, Map<Variant, String>>? = when (version) {
$KT_WHEN        else -> null
    }
}
EOF

REPORT_BODY="# OkHttp recipe verification report

Generated by \`plugin/scripts/generate-fingerprints.sh\` (pinned Maven Central artifacts).
ConnectInterceptor structural check = \`RecipeRegistry.CANONICAL_GUARD\` (ASM 9.7.1).
Pinned versions also require the CallServerInterceptor F7 prefix and both cache sites
(Cache.Entry source constructor, CacheStrategy.Factory.computeCandidate) on both artifacts.
Historical (unpinned) versions stay ConnectInterceptor-only so an unpinned shape cannot fail the script.
A version is pinned in \`RecipeRegistry\` only when every checked shape matches; versions matching no
registered shape are excluded, never force-fit. Do not hand-edit; rerun the script.

| okhttp version | ConnectInterceptor.class sha256 (android / jvm) | connect guard | pinned | callserver guard | cache guards | notes |
|---|---|---|---|---|---|---|
$REPORT_ROWS
$EXCLUDED_SECTIONS"

printf '%s\n' "$REPORT_BODY" > "$REPORT"
