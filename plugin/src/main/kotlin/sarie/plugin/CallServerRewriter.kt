package sarie.plugin

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Prefix-injects `CallServerInterceptor.intercept` after the Kotlin `checkNotNullParameter`
 * preamble. The stock body is kept. A non-null [CronetBridge.callServer] result returns
 * immediately; null falls through to stock (the exchange was non-null).
 */
internal object CallServerRewriter {
    fun rewrite(classBytes: ByteArray): ByteArray {
        val reader = ClassReader(classBytes)
        val expected = InstrumentTarget.CALL_SERVER_INTERCEPTOR.internalName
        check(reader.className == expected) {
            "okhttp-cronet: expected class $expected, got ${reader.className}"
        }
        var found = false
        val writer = framingWriter(reader)
        reader.accept(object : ClassVisitor(Opcodes.ASM9, writer) {
            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor {
                val delegate = super.visitMethod(access, name, descriptor, signature, exceptions)
                if (name != InstrumentationTargets.INTERCEPT_NAME || descriptor != InstrumentationTargets.INTERCEPT_DESC) {
                    return delegate
                }
                found = true
                return CallServerPrefixVisitor(delegate)
            }
        }, ClassReader.SKIP_FRAMES)
        check(found) {
            "okhttp-cronet: method ${InstrumentationTargets.INTERCEPT_NAME}${InstrumentationTargets.INTERCEPT_DESC} " +
                "not found in $expected"
        }
        return writer.toByteArray()
    }

    fun emitPrefix(delegate: MethodVisitor) {
        val stock = Label()
        delegate.visitVarInsn(Opcodes.ALOAD, 1)
        delegate.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            InstrumentationTargets.BRIDGE_OWNER,
            InstrumentationTargets.CALL_SERVER_METHOD,
            InstrumentationTargets.INTERCEPT_DESC,
            false,
        )
        delegate.visitInsn(Opcodes.DUP)
        delegate.visitJumpInsn(Opcodes.IFNULL, stock)
        delegate.visitInsn(Opcodes.ARETURN)
        delegate.visitLabel(stock)
        delegate.visitInsn(Opcodes.POP)
    }

    internal fun framingWriter(reader: ClassReader): ClassWriter =
        object : ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
            override fun getCommonSuperClass(type1: String, type2: String): String = try {
                super.getCommonSuperClass(type1, type2)
            } catch (_: RuntimeException) {
                "java/lang/Object"
            }
        }
}

/**
 * Buffers one method, checks [CallServerGuard] against the stock instruction stream, then
 * replays it with the [CallServerRewriter] prefix spliced in after the preamble.
 * Frames are dropped; the caller computes them.
 */
internal class CallServerPrefixVisitor(
    private val delegate: MethodVisitor,
) : MethodVisitor(Opcodes.ASM9) {
    private val recorder = InstructionRecorder()
    private val events = mutableListOf<MethodEvent>()
    private var codeStarted = false

    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? =
        delegate.visitAnnotation(descriptor, visible)

    override fun visitParameterAnnotation(parameter: Int, descriptor: String, visible: Boolean): AnnotationVisitor? =
        delegate.visitParameterAnnotation(parameter, descriptor, visible)

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
        events += MethodEvent.InvokeDynamic(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments.toList())
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
        check(codeStarted) {
            "okhttp-cronet: CallServerInterceptor.intercept has no code; refusing to rewrite"
        }
        val insns = recorder.insns
        val problems = CallServerGuard.verify(insns)
        check(problems.isEmpty()) {
            "okhttp-cronet: CallServerInterceptor.intercept does not match the pinned stock shape; " +
                "refusing to rewrite.\n" +
                problems.joinToString("\n") { "- $it" } +
                "\nCaptured instructions:\n" +
                insns.joinToString("\n") { "  $it" }
        }
        replay(delegate, events)
        delegate.visitEnd()
    }

    private fun replay(target: MethodVisitor, events: List<MethodEvent>) {
        target.visitCode()
        var instructions = 0
        var injected = false
        for (event in events) {
            event.replay(target)
            if (event is MethodEvent.Instruction) {
                instructions++
                if (!injected && instructions == CallServerGuard.PREAMBLE_INSNS) {
                    CallServerRewriter.emitPrefix(target)
                    injected = true
                }
            }
        }
        check(injected) {
            "okhttp-cronet: CallServerInterceptor.intercept preamble was shorter than " +
                "${CallServerGuard.PREAMBLE_INSNS} instructions; refusing to rewrite"
        }
    }
}

private sealed class MethodEvent {
    interface Instruction

    data class Insn(val opcode: Int) : MethodEvent(), Instruction
    data class IntInsn(val opcode: Int, val operand: Int) : MethodEvent(), Instruction
    data class VarInsn(val opcode: Int, val value: Int) : MethodEvent(), Instruction
    data class TypeInsn(val opcode: Int, val type: String) : MethodEvent(), Instruction
    data class FieldInsn(val opcode: Int, val owner: String, val name: String, val descriptor: String) :
        MethodEvent(), Instruction
    data class MethodInsn(
        val opcode: Int,
        val owner: String,
        val name: String,
        val descriptor: String,
        val isInterface: Boolean,
    ) : MethodEvent(), Instruction
    data class Jump(val opcode: Int, val label: Label) : MethodEvent(), Instruction
    data class Ldc(val value: Any) : MethodEvent(), Instruction
    data class Iinc(val value: Int, val increment: Int) : MethodEvent(), Instruction
    data class InvokeDynamic(
        val name: String,
        val descriptor: String,
        val handle: Handle,
        val args: List<Any>,
    ) : MethodEvent(), Instruction
    data class MultiANewArray(val descriptor: String, val numDimensions: Int) : MethodEvent(), Instruction
    data class TableSwitch(val min: Int, val max: Int, val default: Label, val labels: List<Label>) :
        MethodEvent(), Instruction
    data class LookupSwitch(val default: Label, val keys: IntArray, val labels: List<Label>) :
        MethodEvent(), Instruction

    data class LabelEvent(val label: Label) : MethodEvent()
    data class TryCatch(val start: Label, val end: Label, val handler: Label, val type: String?) : MethodEvent()
    data class LineNumber(val line: Int, val start: Label) : MethodEvent()
    data class LocalVariable(
        val name: String,
        val descriptor: String,
        val signature: String?,
        val start: Label,
        val end: Label,
        val index: Int,
    ) : MethodEvent()
    data class Maxs(val maxStack: Int, val maxLocals: Int) : MethodEvent()

    fun replay(target: MethodVisitor) {
        when (this) {
            is Insn -> target.visitInsn(opcode)
            is IntInsn -> target.visitIntInsn(opcode, operand)
            is VarInsn -> target.visitVarInsn(opcode, value)
            is TypeInsn -> target.visitTypeInsn(opcode, type)
            is FieldInsn -> target.visitFieldInsn(opcode, owner, name, descriptor)
            is MethodInsn -> target.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
            is Jump -> target.visitJumpInsn(opcode, label)
            is Ldc -> target.visitLdcInsn(value)
            is Iinc -> target.visitIincInsn(value, increment)
            is InvokeDynamic -> target.visitInvokeDynamicInsn(name, descriptor, handle, *args.toTypedArray())
            is MultiANewArray -> target.visitMultiANewArrayInsn(descriptor, numDimensions)
            is TableSwitch -> target.visitTableSwitchInsn(min, max, default, *labels.toTypedArray())
            is LookupSwitch -> target.visitLookupSwitchInsn(default, keys, labels.toTypedArray())
            is LabelEvent -> target.visitLabel(label)
            is TryCatch -> target.visitTryCatchBlock(start, end, handler, type)
            is LineNumber -> target.visitLineNumber(line, start)
            is LocalVariable -> target.visitLocalVariable(name, descriptor, signature, start, end, index)
            is Maxs -> target.visitMaxs(maxStack + 2, maxLocals)
        }
    }
}
