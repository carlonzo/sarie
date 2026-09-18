package dev.okhttpcronet.plugin

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/** Dot-notation class name of the only class this plugin may instrument. */
private const val TARGET_CLASS_DOT = "okhttp3.internal.connection.ConnectInterceptor"

/** The AGP instrumentation entry point; only ever installed on Android application variants. */
abstract class ConnectInterceptorVisitorFactory : AsmClassVisitorFactory<InstrumentationParameters.None> {
    override fun isInstrumentable(classData: ClassData): Boolean = isTargetClass(classData.className)

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor,
    ): ClassVisitor = ConnectInterceptorGuardVisitor(nextClassVisitor)
}

internal fun isTargetClass(className: String): Boolean = className == TARGET_CLASS_DOT

/**
 * Verifies that `ConnectInterceptor.intercept` still matches the pinned stock shape
 * (Kotlin null-check preamble, CHECKCAST to RealInterceptorChain, initExchange/copy/proceed
 * pattern, ARETURN) BEFORE emitting the trampoline; on any mismatch it throws with a
 * javap-style dump of the captured instructions (fail closed). All other members, including
 * `<clinit>`, `INSTANCE` and the constructor, pass through untouched.
 */
internal class ConnectInterceptorGuardVisitor(nextClassVisitor: ClassVisitor) :
    ClassVisitor(Opcodes.ASM9, nextClassVisitor) {

    private var interceptSeen = false

    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?,
    ): MethodVisitor {
        if (name != ConnectInterceptorRewriter.INTERCEPT_NAME || descriptor != ConnectInterceptorRewriter.INTERCEPT_DESC) {
            return super.visitMethod(access, name, descriptor, signature, exceptions)
        }
        interceptSeen = true
        return RecordingMethodVisitor(super.visitMethod(access, name, descriptor, signature, exceptions))
    }

    override fun visitEnd() {
        check(interceptSeen) {
            "okhttp-cronet: method intercept${ConnectInterceptorRewriter.INTERCEPT_DESC} not found in " +
                "$TARGET_CLASS_DOT; refusing to rewrite"
        }
        super.visitEnd()
    }

    private class RecordingMethodVisitor(private val delegate: MethodVisitor) : MethodVisitor(Opcodes.ASM9) {
        private val insns = mutableListOf<String>()

        override fun visitInsn(opcode: Int) {
            insns += OPCODE_NAMES[opcode] ?: "0x%02x".format(opcode)
        }

        override fun visitIntInsn(opcode: Int, operand: Int) {
            insns += "${OPCODE_NAMES[opcode]} $operand"
        }

        override fun visitVarInsn(opcode: Int, value: Int) {
            insns += "${OPCODE_NAMES[opcode]} $value"
        }

        override fun visitTypeInsn(opcode: Int, type: String) {
            insns += "${OPCODE_NAMES[opcode]} $type"
        }

        override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
            insns += "${OPCODE_NAMES[opcode]} $owner.$name $descriptor"
        }

        override fun visitMethodInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
            isInterface: Boolean,
        ) {
            insns += "${OPCODE_NAMES[opcode]} $owner.$name $descriptor"
        }

        override fun visitJumpInsn(opcode: Int, label: org.objectweb.asm.Label) {
            insns += "${OPCODE_NAMES[opcode]} L${System.identityHashCode(label)}"
        }

        override fun visitLdcInsn(value: Any) {
            insns += "LDC $value"
        }

        override fun visitIincInsn(value: Int, increment: Int) {
            insns += "IINC $value $increment"
        }

        override fun visitInvokeDynamicInsn(
            name: String,
            descriptor: String,
            bootstrapMethodHandle: org.objectweb.asm.Handle,
            vararg bootstrapMethodArguments: Any,
        ) {
            insns += "INVOKEDYNAMIC $name $descriptor"
        }

        override fun visitMultiANewArrayInsn(descriptor: String, numDimensions: Int) {
            insns += "MULTIANEWARRAY $descriptor $numDimensions"
        }

        override fun visitTableSwitchInsn(min: Int, max: Int, dflt: org.objectweb.asm.Label, vararg labels: org.objectweb.asm.Label) {
            insns += "TABLESWITCH $min $max"
        }

        override fun visitLookupSwitchInsn(dflt: org.objectweb.asm.Label, keys: IntArray, labels: Array<out org.objectweb.asm.Label>) {
            insns += "LOOKUPSWITCH ${keys.joinToString(",")}"
        }

        // Labels, frames, line numbers, locals, try/catch blocks and annotations are recorded
        // out (dropped) - the shape verification is instruction-based and the trampoline body
        // regenerates nothing from them.

        override fun visitEnd() {
            verifyStockShape(insns)
            ConnectInterceptorRewriter.emitTrampoline(delegate)
            delegate.visitEnd()
        }
    }

    private companion object {
        // Readable names for the dump; asm-util's Printer.OPCODES is not on our classpath.
        val OPCODE_NAMES: Map<Int, String> = mapOf(
            Opcodes.NOP to "NOP",
            Opcodes.ACONST_NULL to "ACONST_NULL",
            Opcodes.ICONST_0 to "ICONST_0",
            Opcodes.ICONST_1 to "ICONST_1",
            Opcodes.ICONST_2 to "ICONST_2",
            Opcodes.ICONST_3 to "ICONST_3",
            Opcodes.ICONST_4 to "ICONST_4",
            Opcodes.ICONST_5 to "ICONST_5",
            Opcodes.ALOAD to "ALOAD",
            Opcodes.ASTORE to "ASTORE",
            Opcodes.ILOAD to "ILOAD",
            Opcodes.ISTORE to "ISTORE",
            Opcodes.DUP to "DUP",
            Opcodes.POP to "POP",
            Opcodes.CHECKCAST to "CHECKCAST",
            Opcodes.IRETURN to "IRETURN",
            Opcodes.ARETURN to "ARETURN",
            Opcodes.RETURN to "RETURN",
            Opcodes.ATHROW to "ATHROW",
            Opcodes.GETSTATIC to "GETSTATIC",
            Opcodes.PUTSTATIC to "PUTSTATIC",
            Opcodes.GETFIELD to "GETFIELD",
            Opcodes.PUTFIELD to "PUTFIELD",
            Opcodes.INVOKEVIRTUAL to "INVOKEVIRTUAL",
            Opcodes.INVOKESPECIAL to "INVOKESPECIAL",
            Opcodes.INVOKESTATIC to "INVOKESTATIC",
            Opcodes.INVOKEINTERFACE to "INVOKEINTERFACE",
            Opcodes.NEW to "NEW",
            Opcodes.MONITORENTER to "MONITORENTER",
            Opcodes.MONITOREXIT to "MONITOREXIT",
            Opcodes.IFNULL to "IFNULL",
            Opcodes.IFNONNULL to "IFNONNULL",
        )
    }
}

private const val REAL_CHAIN = "okhttp3/internal/http/RealInterceptorChain"

/**
 * Structural requirements distilled from the pinned okhttp 5.5.0 bytecode (see the
 * per-variant stock.txt golden dumps in the test resources): Kotlin Intrinsics preamble,
 * exactly one RealInterceptorChain CHECKCAST within the first 5 instructions, exactly one
 * initExchange$okhttp call, exactly one copy$okhttp$default call, exactly one
 * RealInterceptorChain.proceed call, ending in ARETURN.
 */
private fun verifyStockShape(insns: List<String>) {
    val problems = mutableListOf<String>()
    val checkcast = "CHECKCAST $REAL_CHAIN"
    val castIndex = insns.indexOf(checkcast)
    if (castIndex !in 0..4) problems += "expected $checkcast within the first 5 instructions"
    if (insns.count { it == checkcast } != 1) problems += "expected exactly one $checkcast"
    if (insns.count { it.startsWith("INVOKEVIRTUAL okhttp3/internal/connection/RealCall.initExchange\$okhttp") } != 1) {
        problems += "expected exactly one RealCall.initExchange\$okhttp call"
    }
    if (insns.count { it.startsWith("INVOKESTATIC $REAL_CHAIN.copy\$okhttp\$default ") } != 1) {
        problems += "expected exactly one RealInterceptorChain.copy\$okhttp\$default call"
    }
    if (insns.count { it.startsWith("INVOKEVIRTUAL $REAL_CHAIN.proceed (Lokhttp3/Request;)") } != 1) {
        problems += "expected exactly one RealInterceptorChain.proceed call"
    }
    if (insns.lastOrNull() != "ARETURN") problems += "expected method to end with ARETURN"
    if (problems.isNotEmpty()) {
        throw IllegalStateException(
            "okhttp-cronet: ConnectInterceptor.intercept does not match the pinned stock shape; " +
                "refusing to rewrite.\n" +
                problems.joinToString("\n") { "- $it" } +
                "\nCaptured instructions:\n" +
                insns.joinToString("\n") { "  $it" },
        )
    }
}
