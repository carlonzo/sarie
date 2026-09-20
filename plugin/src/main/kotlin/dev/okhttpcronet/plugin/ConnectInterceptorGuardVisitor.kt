package dev.okhttpcronet.plugin

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

import org.gradle.api.tasks.Optional

/** Dot-notation class name of the only class this plugin may instrument. */
private const val TARGET_CLASS_DOT = "okhttp3.internal.connection.ConnectInterceptor"

/**
 * Worker-serializable instrumentation parameters; only simple Property types cross the AGP
 * instrumentation worker boundary.
 */
interface OkhttpCronetInstrumentationParams : InstrumentationParameters {
    @get:Input
    val okhttpVersion: Property<String>

    @get:Input
    @get:Optional
    val invalidateToken: Property<Long>
}

/** The AGP instrumentation entry point; only ever installed on Android application variants. */
abstract class ConnectInterceptorVisitorFactory : AsmClassVisitorFactory<OkhttpCronetInstrumentationParams> {
    override fun isInstrumentable(classData: ClassData): Boolean = isTargetClass(classData.className)

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor,
    ): ClassVisitor {
        val version = parameters.get().okhttpVersion.orNull ?: "family"
        return ConnectInterceptorGuardVisitor(
            nextClassVisitor,
            // Guard lookup based on the configured/resolved version parameter, falling back to familyGuard
            RecipeRegistry.guardFor(version),
        )
    }
}

internal fun isTargetClass(className: String): Boolean = className == TARGET_CLASS_DOT

/**
 * Verifies that `ConnectInterceptor.intercept` still matches the recipe's pinned stock shape
 * (Kotlin null-check preamble, CHECKCAST to RealInterceptorChain, initExchange/copy/proceed
 * pattern, ARETURN) BEFORE emitting the trampoline; on any mismatch it throws with a
 * javap-style dump of the captured instructions (fail closed). All other members, including
 * `<clinit>`, `INSTANCE` and the constructor, pass through untouched.
 */
internal class ConnectInterceptorGuardVisitor(
    nextClassVisitor: ClassVisitor,
    private val guard: GuardSpec,
) : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {

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
        val delegate = super.visitMethod(access, name, descriptor, signature, exceptions)
        return RecordingMethodVisitor(delegate) { insns ->
            val problems = guard.verify(insns)
            check(problems.isEmpty()) {
                "okhttp-cronet: ConnectInterceptor.intercept does not match the pinned stock shape; " +
                    "refusing to rewrite.\n" +
                    problems.joinToString("\n") { "- $it" } +
                    "\nCaptured instructions:\n" +
                    insns.joinToString("\n") { "  $it" }
            }
            ConnectInterceptorRewriter.emitTrampoline(delegate)
        }
    }

    override fun visitEnd() {
        check(interceptSeen) {
            "okhttp-cronet: method intercept${ConnectInterceptorRewriter.INTERCEPT_DESC} not found in " +
                "$TARGET_CLASS_DOT; refusing to rewrite"
        }
        super.visitEnd()
    }
}

/**
 * Records the intercepted method's instruction stream as javap-style strings and hands it to
 * [onEnd] right before the delegate's visitEnd. Labels, frames, line numbers, locals, try/catch
 * blocks and annotations are recorded out (dropped) - the shape verification is
 * instruction-based and the trampoline body regenerates nothing from them.
 */
internal class RecordingMethodVisitor(
    private val delegate: MethodVisitor,
    private val onEnd: (List<String>) -> Unit,
) : MethodVisitor(Opcodes.ASM9) {
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

    override fun visitEnd() {
        onEnd(insns.toList())
        delegate.visitEnd()
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
