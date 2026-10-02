package sarie.plugin

import java.io.File
import java.util.IdentityHashMap
import java.util.zip.ZipFile
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

internal const val TRAMPOLINE_DESC: String = "(Lokhttp3/Interceptor\$Chain;)Lokhttp3/Response;"

/** Every (version, variant) golden pair the registry covers; new registry lines gain coverage automatically. */
internal fun allRecipeVariants(): List<Pair<String, Variant>> =
    RecipeRegistry.recipes.keys.sorted().flatMap { version -> Variant.entries.map { version to it } }

/**
 * Stock bytecode of one registered target, read at test time out of the resolved OkHttp jar for
 * [version] rather than from a checked-in copy, so it can never drift from the dependency.
 *
 * [variant] does not select different bytes: OkHttp's android and jvm artifacts are
 * byte-identical for every target here (their `GoldenFingerprints` hashes match), so it only
 * tells the caller which artifact fingerprint to compare against. The build-time
 * `verifyOkHttpFingerprint` still checks the real android aar and jvm jar separately, so a
 * future divergence is caught fail-closed there rather than here.
 */
internal fun stock(
    version: String,
    @Suppress("UNUSED_PARAMETER") variant: Variant,
    fileName: String = InstrumentTarget.CONNECT_INTERCEPTOR.fileName,
): ByteArray {
    val entry = checkNotNull(InstrumentationTargets.byFileName(fileName)) {
        "no registered target owns the class file name '$fileName'"
    }.classEntry
    val jar = checkNotNull(System.getProperty("sarie.stock.okhttp.$version")) {
        "no OkHttp jar was provided for $version; the stock bytecode is read from it at test time"
    }
    return ZipFile(jar).use { zip ->
        val stream = checkNotNull(zip.getEntry(entry)) { "$jar has no $entry" }
        zip.getInputStream(stream).use { it.readBytes() }
    }
}

internal fun callServerStock(version: String, variant: Variant): ByteArray =
    stock(version, variant, InstrumentTarget.CALL_SERVER_INTERCEPTOR.fileName)

internal fun realCallStock(version: String, variant: Variant): ByteArray =
    stock(version, variant, InstrumentTarget.REAL_CALL.fileName)

/** `RealCall.cancel()`'s listener load; the notifyCanceled hook is spliced in front of it. */
internal const val LISTENER_LOAD: String =
    "GETFIELD okhttp3/internal/connection/RealCall.eventListener"

internal const val NOTIFY_CANCELED: String =
    "INVOKESTATIC sarie/bridge/CronetBridge.notifyCanceled " +
        "(Lokhttp3/internal/connection/RealCall;)V"

/** Captures the recorded javap-style instruction strings of one method. */
internal fun methodInsns(classBytes: ByteArray, methodName: String, methodDesc: String): List<String> {
    var captured: List<String> = emptyList()
    ClassReader(classBytes).accept(object : ClassVisitor(Opcodes.ASM9) {
        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor? {
            if (name != methodName || descriptor != methodDesc) return null
            return RecordingMethodVisitor(object : MethodVisitor(Opcodes.ASM9) {}) { insns -> captured = insns }
        }
    }, 0)
    return captured
}

internal fun cancelInsns(classBytes: ByteArray): List<String> =
    methodInsns(classBytes, InstrumentationTargets.CANCEL_NAME, InstrumentationTargets.CANCEL_DESC)

/** Rewrites one method with [mutate] wrapping the passthrough visitor. */
internal fun tamperMethod(
    classBytes: ByteArray,
    methodName: String,
    methodDesc: String,
    mutate: (MethodVisitor) -> MethodVisitor,
): ByteArray {
    val writer = ClassWriter(0)
    ClassReader(classBytes).accept(object : ClassVisitor(Opcodes.ASM9, writer) {
        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor {
            val passthrough = super.visitMethod(access, name, descriptor, signature, exceptions)
            return if (name == methodName && descriptor == methodDesc) mutate(passthrough) else passthrough
        }
    }, 0)
    return writer.toByteArray()
}

internal fun assertFailsClosed(block: () -> Unit): String {
    try {
        block()
    } catch (e: IllegalStateException) {
        return e.message ?: ""
    }
    throw AssertionError("expected IllegalStateException")
}

/** Captures the recorded javap-style instruction strings of the intercept method. */
internal fun recordedInsns(classBytes: ByteArray): List<String> {
    var captured: List<String> = emptyList()
    ClassReader(classBytes).accept(object : ClassVisitor(Opcodes.ASM9) {
        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor? {
            if (name != "intercept" || descriptor != TRAMPOLINE_DESC) return null
            return RecordingMethodVisitor(object : MethodVisitor(Opcodes.ASM9) {}) { insns -> captured = insns }
        }
    }, 0)
    return captured
}

/** Rewrites the intercept method with [mutate] wrapping the passthrough visitor. */
internal fun tamper(classBytes: ByteArray, mutate: (MethodVisitor) -> MethodVisitor): ByteArray {
    val writer = ClassWriter(0)
    ClassReader(classBytes).accept(object : ClassVisitor(Opcodes.ASM9, writer) {
        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor {
            val passthrough = super.visitMethod(access, name, descriptor, signature, exceptions)
            return if (name == "intercept" && descriptor == TRAMPOLINE_DESC) mutate(passthrough) else passthrough
        }
    }, 0)
    return writer.toByteArray()
}

internal val expectedTrampoline = listOf(
    "ALOAD 1",
    "INVOKESTATIC sarie/bridge/CronetBridge.intercept $TRAMPOLINE_DESC itf=false",
    "ARETURN",
)

/** Readable opcode dump of ONLY the intercept method; any unexpected visit type fails the assertion. */
internal fun interceptInstructions(bytes: ByteArray): List<String> {
    val out = mutableListOf<String>()
    ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor? {
            if (name != "intercept" || descriptor != TRAMPOLINE_DESC) return null
            return object : MethodVisitor(Opcodes.ASM9) {
                override fun visitVarInsn(opcode: Int, value: Int) {
                    check(opcode == Opcodes.ALOAD) { "unexpected opcode $opcode" }
                    out += "ALOAD $value"
                }

                override fun visitMethodInsn(
                    opcode: Int,
                    owner: String,
                    name: String,
                    descriptor: String,
                    isInterface: Boolean,
                ) {
                    check(opcode == Opcodes.INVOKESTATIC) { "unexpected opcode $opcode" }
                    out += "INVOKESTATIC $owner.$name $descriptor itf=$isInterface"
                }

                override fun visitInsn(opcode: Int) {
                    check(opcode == Opcodes.ARETURN) { "unexpected opcode $opcode" }
                    out += "ARETURN"
                }
            }
        }
    }, 0)
    return out
}

/**
 * Header + instruction listing of every member; the intercept method contributes its header
 * only (its body is asserted separately). Frames, line numbers, locals and annotations are
 * excluded: COMPUTE_FRAMES legitimately regenerates those. [bodyOmitted] names additional
 * methods whose bodies are asserted separately.
 */
internal fun memberDump(bytes: ByteArray, vararg bodyOmitted: String): List<String> {
    val out = mutableListOf<String>()
    val labelIds = IdentityHashMap<Label, Int>()
    var labelSeq = 0
    fun Label.id(): Int = labelIds.getOrPut(this) { labelSeq++ }
    ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
        override fun visitField(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            value: Any?,
        ): FieldVisitor? {
            out += "FIELD $access $name $descriptor"
            return null
        }

        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor? {
            out += "METHOD $access $name $descriptor sig=${signature ?: "-"} " +
                "throws=${exceptions?.joinToString(",") ?: "-"}"
            if (name == "intercept" && descriptor == TRAMPOLINE_DESC) return null
            if ("$name$descriptor" in bodyOmitted) return null
            return object : MethodVisitor(Opcodes.ASM9) {
                override fun visitLabel(label: Label) {
                    out += "  LABEL ${label.id()}"
                }

                override fun visitInsn(opcode: Int) {
                    out += "  INSN $opcode"
                }

                override fun visitIntInsn(opcode: Int, operand: Int) {
                    out += "  $opcode $operand"
                }

                override fun visitVarInsn(opcode: Int, value: Int) {
                    out += "  $opcode $value"
                }

                override fun visitTypeInsn(opcode: Int, type: String) {
                    out += "  $opcode $type"
                }

                override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
                    out += "  $opcode $owner.$name $descriptor"
                }

                override fun visitMethodInsn(
                    opcode: Int,
                    owner: String,
                    name: String,
                    descriptor: String,
                    isInterface: Boolean,
                ) {
                    out += "  $opcode $owner.$name $descriptor itf=$isInterface"
                }

                override fun visitJumpInsn(opcode: Int, label: Label) {
                    out += "  $opcode ${label.id()}"
                }

                override fun visitLdcInsn(value: Any) {
                    out += "  LDC $value"
                }

                override fun visitIincInsn(value: Int, increment: Int) {
                    out += "  IINC $value $increment"
                }

                override fun visitMultiANewArrayInsn(descriptor: String, numDimensions: Int) {
                    out += "  MULTIANEWARRAY $descriptor $numDimensions"
                }

                override fun visitTableSwitchInsn(min: Int, max: Int, dflt: Label, vararg labels: Label) {
                    out += "  TABLESWITCH $min $max ${dflt.id()} ${labels.joinToString(" ") { it.id().toString() }}"
                }

                override fun visitLookupSwitchInsn(dflt: Label, keys: IntArray, labels: Array<out Label>) {
                    out += "  LOOKUPSWITCH ${dflt.id()} ${keys.zip(labels) { k, l -> "$k=${l.id()}" }}"
                }
            }
        }
    }, 0)
    return out
}

/** Walks up from the TestKit / included-build working directory to the repo file. */
internal fun repoFile(relative: String): File {
    var dir = File(".").canonicalFile
    repeat(8) {
        val candidate = File(dir, relative)
        if (candidate.isFile) return candidate
        dir = dir.parentFile ?: return@repeat
    }
    error("cannot find $relative from ${File(".").canonicalFile}")
}
