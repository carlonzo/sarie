package sarie.plugin

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

internal fun isTargetClass(className: String): Boolean = InstrumentationTargets.isTarget(className)

/**
 * Verifies the pinned stock shape of each registered target BEFORE rewriting it.
 * ConnectInterceptor becomes a full trampoline. CallServerInterceptor keeps its body behind
 * the callServer prefix. `<clinit>`, constructors and every other method pass through untouched.
 * A shape mismatch throws with a javap-style dump (fail closed).
 */
internal class ConnectInterceptorGuardVisitor(
    nextClassVisitor: ClassVisitor,
    private val guard: GuardSpec,
) : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {

    private var internalName: String? = null
    private var interceptSeen = false

    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?,
    ) {
        internalName = name
        super.visit(version, access, name, signature, superName, interfaces)
    }

    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?,
    ): MethodVisitor {
        val target = InstrumentationTargets.byInternalName(internalName ?: "")
        if (
            target == null ||
            name != InstrumentationTargets.INTERCEPT_NAME ||
            descriptor != InstrumentationTargets.INTERCEPT_DESC
        ) {
            return super.visitMethod(access, name, descriptor, signature, exceptions)
        }
        interceptSeen = true
        val delegate = super.visitMethod(access, name, descriptor, signature, exceptions)
        return when (target) {
            InstrumentTarget.CONNECT_INTERCEPTOR -> RecordingMethodVisitor(delegate) { insns ->
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
            InstrumentTarget.CALL_SERVER_INTERCEPTOR -> CallServerPrefixVisitor(delegate)
        }
    }

    override fun visitEnd() {
        val name = internalName ?: "(unknown)"
        check(interceptSeen) {
            "okhttp-cronet: method intercept${InstrumentationTargets.INTERCEPT_DESC} not found in " +
                "$name; refusing to rewrite"
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
    private val recorder = InstructionRecorder()

    override fun visitInsn(opcode: Int) {
        recorder.insn(opcode)
    }

    override fun visitIntInsn(opcode: Int, operand: Int) {
        recorder.intInsn(opcode, operand)
    }

    override fun visitVarInsn(opcode: Int, value: Int) {
        recorder.varInsn(opcode, value)
    }

    override fun visitTypeInsn(opcode: Int, type: String) {
        recorder.typeInsn(opcode, type)
    }

    override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
        recorder.fieldInsn(opcode, owner, name, descriptor)
    }

    override fun visitMethodInsn(
        opcode: Int,
        owner: String,
        name: String,
        descriptor: String,
        isInterface: Boolean,
    ) {
        recorder.methodInsn(opcode, owner, name, descriptor)
    }

    override fun visitJumpInsn(opcode: Int, label: org.objectweb.asm.Label) {
        recorder.jumpInsn(opcode, label)
    }

    override fun visitLdcInsn(value: Any) {
        recorder.ldc(value)
    }

    override fun visitIincInsn(value: Int, increment: Int) {
        recorder.iinc(value, increment)
    }

    override fun visitInvokeDynamicInsn(
        name: String,
        descriptor: String,
        bootstrapMethodHandle: org.objectweb.asm.Handle,
        vararg bootstrapMethodArguments: Any,
    ) {
        recorder.invokeDynamic(name, descriptor)
    }

    override fun visitMultiANewArrayInsn(descriptor: String, numDimensions: Int) {
        recorder.multiANewArray(descriptor, numDimensions)
    }

    override fun visitTableSwitchInsn(min: Int, max: Int, dflt: org.objectweb.asm.Label, vararg labels: org.objectweb.asm.Label) {
        recorder.tableSwitch(min, max)
    }

    override fun visitLookupSwitchInsn(dflt: org.objectweb.asm.Label, keys: IntArray, labels: Array<out org.objectweb.asm.Label>) {
        recorder.lookupSwitch(keys)
    }

    override fun visitEnd() {
        onEnd(recorder.insns)
        delegate.visitEnd()
    }
}
