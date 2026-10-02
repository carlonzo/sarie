package sarie.plugin

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Splices one `CronetBridge.notifyCanceled(RealCall)` call in front of
 * `eventListener.canceled(this)` in `RealCall.cancel()V`. The stock body is otherwise preserved:
 * the exchange, the route plan, and the listener all tear down exactly as stock does, and the
 * already-canceled fast path is never reached, so a second `cancel()` does not re-notify.
 *
 * Before the listener, not after: a host `EventListener` is user code that may throw, and a
 * throw there would skip the bridge and leave the engine request running. Every other RealCall
 * member is copied through; `RealCall$AsyncCall` is a separate class and is not a target.
 *
 * Frames are dropped; the caller computes them.
 */
internal object RealCallRewriter {

    fun rewrite(classBytes: ByteArray): ByteArray {
        val reader = ClassReader(classBytes)
        val expected = InstrumentTarget.REAL_CALL.internalName
        check(reader.className == expected) {
            "sarie: expected class $expected, got ${reader.className}"
        }
        var found = false
        val writer = CallServerRewriter.framingWriter(reader)
        reader.accept(object : ClassVisitor(Opcodes.ASM9, writer) {
            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor {
                val delegate = super.visitMethod(access, name, descriptor, signature, exceptions)
                if (name != InstrumentationTargets.CANCEL_NAME ||
                    descriptor != InstrumentationTargets.CANCEL_DESC
                ) {
                    return delegate
                }
                found = true
                return RealCallAppendVisitor(delegate)
            }
        }, ClassReader.SKIP_FRAMES)
        check(found) {
            "sarie: method ${InstrumentationTargets.CANCEL_NAME}" +
                "${InstrumentationTargets.CANCEL_DESC} not found in $expected"
        }
        return writer.toByteArray()
    }

    /** `aload 0` / `INVOKESTATIC CronetBridge.notifyCanceled`. The stock `RETURN` still follows. */
    fun emitHook(delegate: MethodVisitor) {
        delegate.visitVarInsn(Opcodes.ALOAD, 0)
        delegate.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            InstrumentationTargets.BRIDGE_OWNER,
            InstrumentationTargets.NOTIFY_CANCELED_METHOD,
            InstrumentationTargets.NOTIFY_CANCELED_DESC,
            false,
        )
    }
}

/**
 * Buffers `RealCall.cancel()`, fails closed on [RealCallCancelGuard], then replays it with
 * [RealCallRewriter.emitHook] spliced in front of the `GETFIELD eventListener`.
 * already-canceled fast path (the `RETURN` at instruction index 3) is replayed untouched, so the
 * hook only ever runs for a cancel that actually does something.
 */
internal class RealCallAppendVisitor(
    private val delegate: MethodVisitor,
) : MethodVisitor(Opcodes.ASM9) {
    private val recorder = InstructionRecorder()
    private val events = mutableListOf<MethodEvent>()
    private var codeStarted = false

    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? =
        delegate.visitAnnotation(descriptor, visible)

    override fun visitParameterAnnotation(
        parameter: Int,
        descriptor: String,
        visible: Boolean,
    ): AnnotationVisitor? = delegate.visitParameterAnnotation(parameter, descriptor, visible)

    override fun visitCode() {
        codeStarted = true
    }

    override fun visitInsn(opcode: Int) {
        recorder.insn(opcode)
        events += MethodEvent.Insn(opcode)
    }

    override fun visitIntInsn(opcode: Int, operand: Int) {
        recorder.intInsn(opcode, operand)
        events += MethodEvent.IntInsn(opcode, operand)
    }

    override fun visitVarInsn(opcode: Int, value: Int) {
        recorder.varInsn(opcode, value)
        events += MethodEvent.VarInsn(opcode, value)
    }

    override fun visitTypeInsn(opcode: Int, type: String) {
        recorder.typeInsn(opcode, type)
        events += MethodEvent.TypeInsn(opcode, type)
    }

    override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
        recorder.fieldInsn(opcode, owner, name, descriptor)
        events += MethodEvent.FieldInsn(opcode, owner, name, descriptor)
    }

    override fun visitMethodInsn(
        opcode: Int,
        owner: String,
        name: String,
        descriptor: String,
        isInterface: Boolean,
    ) {
        recorder.methodInsn(opcode, owner, name, descriptor)
        events += MethodEvent.MethodInsn(opcode, owner, name, descriptor, isInterface)
    }

    override fun visitJumpInsn(opcode: Int, label: Label) {
        recorder.jumpInsn(opcode, label)
        events += MethodEvent.Jump(opcode, label)
    }

    override fun visitLabel(label: Label) {
        events += MethodEvent.LabelEvent(label)
    }

    override fun visitLdcInsn(value: Any) {
        recorder.ldc(value)
        events += MethodEvent.Ldc(value)
    }

    override fun visitIincInsn(value: Int, increment: Int) {
        recorder.iinc(value, increment)
        events += MethodEvent.Iinc(value, increment)
    }

    override fun visitInvokeDynamicInsn(
        name: String,
        descriptor: String,
        bootstrapMethodHandle: Handle,
        vararg bootstrapMethodArguments: Any,
    ) {
        recorder.invokeDynamic(name, descriptor)
        events += MethodEvent.InvokeDynamic(
            name,
            descriptor,
            bootstrapMethodHandle,
            bootstrapMethodArguments.toList(),
        )
    }

    override fun visitMultiANewArrayInsn(descriptor: String, numDimensions: Int) {
        recorder.multiANewArray(descriptor, numDimensions)
        events += MethodEvent.MultiANewArray(descriptor, numDimensions)
    }

    override fun visitTableSwitchInsn(min: Int, max: Int, dflt: Label, vararg labels: Label) {
        recorder.tableSwitch(min, max)
        events += MethodEvent.TableSwitch(min, max, dflt, labels.toList())
    }

    override fun visitLookupSwitchInsn(dflt: Label, keys: IntArray, labels: Array<out Label>) {
        recorder.lookupSwitch(keys)
        events += MethodEvent.LookupSwitch(dflt, keys, labels.toList())
    }

    override fun visitTryCatchBlock(start: Label, end: Label, handler: Label, type: String?) {
        events += MethodEvent.TryCatch(start, end, handler, type)
    }

    override fun visitLineNumber(line: Int, start: Label) {
        events += MethodEvent.LineNumber(line, start)
    }

    override fun visitLocalVariable(
        name: String,
        descriptor: String,
        signature: String?,
        start: Label,
        end: Label,
        index: Int,
    ) {
        events += MethodEvent.LocalVariable(name, descriptor, signature, start, end, index)
    }

    override fun visitMaxs(maxStack: Int, maxLocals: Int) {
        events += MethodEvent.Maxs(maxStack, maxLocals)
    }

    override fun visitEnd() {
        val site = "${InstrumentationTargets.CANCEL_NAME}${InstrumentationTargets.CANCEL_DESC} " +
            "in ${InstrumentTarget.REAL_CALL.internalName}"
        check(codeStarted) { "sarie: $site has no code; refusing to rewrite" }
        val insns = recorder.insns
        val problems = RealCallCancelGuard.verify(insns)
        check(problems.isEmpty()) {
            "sarie: $site does not match the pinned stock shape; refusing to rewrite.\n" +
                problems.joinToString("\n") { "- $it" } +
                "\nCaptured instructions:\n" +
                insns.joinToString("\n") { "  $it" }
        }
        // The hook goes in FRONT of `eventListener.canceled(this)`, not at the end of the method.
        // A host EventListener is user code that can throw, and a throw there would otherwise skip
        // the bridge and leave the engine request uncancellable. Anchoring on the
        // `GETFIELD eventListener` also means the hook runs with an empty operand stack, so there
        // is nothing to keep balanced.
        val hookAt = events.indexOfFirst { event ->
            event is MethodEvent.FieldInsn &&
                event.opcode == Opcodes.GETFIELD &&
                event.owner == InstrumentTarget.REAL_CALL.internalName &&
                event.name == InstrumentationTargets.EVENT_LISTENER_FIELD
        }
        check(hookAt >= 0) {
            "sarie: $site never loads ${InstrumentationTargets.EVENT_LISTENER_FIELD}; " +
                "refusing to rewrite"
        }
        delegate.visitCode()
        events.forEachIndexed { index, event ->
            if (index == hookAt) RealCallRewriter.emitHook(delegate)
            // The hook pushes the receiver, so the stock method needs one more stack slot.
            event.replay(delegate, maxStackDelta = 1)
        }
        delegate.visitEnd()
    }
}