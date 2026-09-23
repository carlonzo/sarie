package sarie.plugin

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Replaces one `isHttps` invoke in each cache target. Every other method, including
 * `Cache.Entry.writeTo` (its own `HttpUrl.isHttps`) and `<clinit>`, is copied through.
 * Frames are dropped; the caller computes them. The extra `aload 6` in the entry
 * constructor is balanced by [CacheHooks.expectTlsBlock], so the following `ifeq` is unchanged.
 */
internal object CacheHooksRewriter {
    fun rewriteEntry(classBytes: ByteArray): ByteArray =
        rewrite(classBytes, InstrumentTarget.CACHE_ENTRY)

    fun rewriteStrategy(classBytes: ByteArray): ByteArray =
        rewrite(classBytes, InstrumentTarget.CACHE_STRATEGY_FACTORY)

    private fun rewrite(classBytes: ByteArray, target: InstrumentTarget): ByteArray {
        val reader = ClassReader(classBytes)
        check(reader.className == target.internalName) {
            "okhttp-cronet: expected class ${target.internalName}, got ${reader.className}"
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
                if (!isCacheSite(target, name, descriptor)) return delegate
                found = true
                return substituteVisitor(target, delegate)
            }
        }, ClassReader.SKIP_FRAMES)
        check(found) {
            "okhttp-cronet: cache site ${siteLabel(target)} not found in ${target.internalName}"
        }
        return writer.toByteArray()
    }
}

internal fun isCacheSite(target: InstrumentTarget, name: String, descriptor: String): Boolean =
    when (target) {
        InstrumentTarget.CACHE_ENTRY ->
            name == InstrumentationTargets.CACHE_ENTRY_INIT &&
                descriptor == InstrumentationTargets.CACHE_ENTRY_INIT_DESC
        InstrumentTarget.CACHE_STRATEGY_FACTORY ->
            name == InstrumentationTargets.COMPUTE_CANDIDATE &&
                descriptor == InstrumentationTargets.COMPUTE_CANDIDATE_DESC
        else -> false
    }

internal fun substituteVisitor(target: InstrumentTarget, delegate: MethodVisitor): MethodVisitor =
    when (target) {
        InstrumentTarget.CACHE_ENTRY -> CacheSubstituteVisitor(
            delegate = delegate,
            site = "Cache.Entry.<init>(Source)",
            verify = CacheEntryGuard::verify,
            matches = { opcode, owner, name, descriptor ->
                opcode == Opcodes.INVOKEVIRTUAL &&
                    owner == "okhttp3/HttpUrl" &&
                    name == "isHttps" &&
                    descriptor == "()Z"
            },
            emit = { mv ->
                mv.visitVarInsn(Opcodes.ALOAD, InstrumentationTargets.CACHE_SOURCE_LOCAL)
                mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    InstrumentationTargets.CACHE_HOOKS_OWNER,
                    InstrumentationTargets.EXPECT_TLS_BLOCK,
                    InstrumentationTargets.EXPECT_TLS_BLOCK_DESC,
                    false,
                )
            },
            maxStackDelta = 1,
        )
        InstrumentTarget.CACHE_STRATEGY_FACTORY -> CacheSubstituteVisitor(
            delegate = delegate,
            site = "CacheStrategy.Factory.computeCandidate",
            verify = CacheStrategyGuard::verify,
            matches = { opcode, owner, name, descriptor ->
                opcode == Opcodes.INVOKEVIRTUAL &&
                    owner == "okhttp3/Request" &&
                    name == "isHttps" &&
                    descriptor == "()Z"
            },
            emit = { mv ->
                mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    InstrumentationTargets.CACHE_HOOKS_OWNER,
                    InstrumentationTargets.REQUIRE_HANDSHAKE,
                    InstrumentationTargets.REQUIRE_HANDSHAKE_DESC,
                    false,
                )
            },
            maxStackDelta = 0,
        )
        else -> error("okhttp-cronet: $target is not a cache site")
    }

private fun siteLabel(target: InstrumentTarget): String = when (target) {
    InstrumentTarget.CACHE_ENTRY ->
        "${InstrumentationTargets.CACHE_ENTRY_INIT}${InstrumentationTargets.CACHE_ENTRY_INIT_DESC}"
    InstrumentTarget.CACHE_STRATEGY_FACTORY ->
        "${InstrumentationTargets.COMPUTE_CANDIDATE}${InstrumentationTargets.COMPUTE_CANDIDATE_DESC}"
    else -> target.internalName
}

/**
 * Buffers one cache method, fails closed on [verify], then replays it with the single
 * `isHttps` invoke replaced. The handshake(); ifnonnull instructions are replayed as-is.
 */
internal class CacheSubstituteVisitor(
    private val delegate: MethodVisitor,
    private val site: String,
    private val verify: (List<String>) -> List<String>,
    private val matches: (Int, String, String, String) -> Boolean,
    private val emit: (MethodVisitor) -> Unit,
    private val maxStackDelta: Int,
) : MethodVisitor(Opcodes.ASM9) {
    private val recorder = InstructionRecorder()
    private val events = mutableListOf<CacheMethodEvent>()
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
        events += CacheMethodEvent.Insn(opcode)
    }

    override fun visitIntInsn(opcode: Int, operand: Int) {
        recorder.intInsn(opcode, operand)
        events += CacheMethodEvent.IntInsn(opcode, operand)
    }

    override fun visitVarInsn(opcode: Int, value: Int) {
        recorder.varInsn(opcode, value)
        events += CacheMethodEvent.VarInsn(opcode, value)
    }

    override fun visitTypeInsn(opcode: Int, type: String) {
        recorder.typeInsn(opcode, type)
        events += CacheMethodEvent.TypeInsn(opcode, type)
    }

    override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
        recorder.fieldInsn(opcode, owner, name, descriptor)
        events += CacheMethodEvent.FieldInsn(opcode, owner, name, descriptor)
    }

    override fun visitMethodInsn(
        opcode: Int,
        owner: String,
        name: String,
        descriptor: String,
        isInterface: Boolean,
    ) {
        recorder.methodInsn(opcode, owner, name, descriptor)
        events += CacheMethodEvent.MethodInsn(opcode, owner, name, descriptor, isInterface)
    }

    override fun visitJumpInsn(opcode: Int, label: Label) {
        recorder.jumpInsn(opcode, label)
        events += CacheMethodEvent.Jump(opcode, label)
    }

    override fun visitLabel(label: Label) {
        events += CacheMethodEvent.LabelEvent(label)
    }

    override fun visitLdcInsn(value: Any) {
        recorder.ldc(value)
        events += CacheMethodEvent.Ldc(value)
    }

    override fun visitIincInsn(value: Int, increment: Int) {
        recorder.iinc(value, increment)
        events += CacheMethodEvent.Iinc(value, increment)
    }

    override fun visitInvokeDynamicInsn(
        name: String,
        descriptor: String,
        bootstrapMethodHandle: Handle,
        vararg bootstrapMethodArguments: Any,
    ) {
        recorder.invokeDynamic(name, descriptor)
        events += CacheMethodEvent.InvokeDynamic(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments.toList())
    }

    override fun visitMultiANewArrayInsn(descriptor: String, numDimensions: Int) {
        recorder.multiANewArray(descriptor, numDimensions)
        events += CacheMethodEvent.MultiANewArray(descriptor, numDimensions)
    }

    override fun visitTableSwitchInsn(min: Int, max: Int, dflt: Label, vararg labels: Label) {
        recorder.tableSwitch(min, max)
        events += CacheMethodEvent.TableSwitch(min, max, dflt, labels.toList())
    }

    override fun visitLookupSwitchInsn(dflt: Label, keys: IntArray, labels: Array<out Label>) {
        recorder.lookupSwitch(keys)
        events += CacheMethodEvent.LookupSwitch(dflt, keys, labels.toList())
    }

    override fun visitTryCatchBlock(start: Label, end: Label, handler: Label, type: String?) {
        events += CacheMethodEvent.TryCatch(start, end, handler, type)
    }

    override fun visitLineNumber(line: Int, start: Label) {
        events += CacheMethodEvent.LineNumber(line, start)
    }

    override fun visitLocalVariable(
        name: String,
        descriptor: String,
        signature: String?,
        start: Label,
        end: Label,
        index: Int,
    ) {
        events += CacheMethodEvent.LocalVariable(name, descriptor, signature, start, end, index)
    }

    override fun visitMaxs(maxStack: Int, maxLocals: Int) {
        events += CacheMethodEvent.Maxs(maxStack, maxLocals)
    }

    override fun visitEnd() {
        check(codeStarted) {
            "okhttp-cronet: $site has no code; refusing to rewrite"
        }
        val insns = recorder.insns
        val problems = verify(insns)
        check(problems.isEmpty()) {
            "okhttp-cronet: $site does not match the pinned stock shape; refusing to rewrite.\n" +
                problems.joinToString("\n") { "- $it" } +
                "\nCaptured instructions:\n" +
                insns.joinToString("\n") { "  $it" }
        }
        delegate.visitCode()
        var replaced = 0
        for (event in events) {
            if (event is CacheMethodEvent.MethodInsn && matches(event.opcode, event.owner, event.name, event.descriptor)) {
                emit(delegate)
                replaced++
            } else {
                event.replay(delegate, maxStackDelta)
            }
        }
        check(replaced == 1) {
            "okhttp-cronet: expected to replace exactly one isHttps in $site, replaced $replaced"
        }
        delegate.visitEnd()
    }
}

private sealed class CacheMethodEvent {
    data class Insn(val opcode: Int) : CacheMethodEvent()
    data class IntInsn(val opcode: Int, val operand: Int) : CacheMethodEvent()
    data class VarInsn(val opcode: Int, val value: Int) : CacheMethodEvent()
    data class TypeInsn(val opcode: Int, val type: String) : CacheMethodEvent()
    data class FieldInsn(val opcode: Int, val owner: String, val name: String, val descriptor: String) : CacheMethodEvent()
    data class MethodInsn(
        val opcode: Int,
        val owner: String,
        val name: String,
        val descriptor: String,
        val isInterface: Boolean,
    ) : CacheMethodEvent()
    data class Jump(val opcode: Int, val label: Label) : CacheMethodEvent()
    data class Ldc(val value: Any) : CacheMethodEvent()
    data class Iinc(val value: Int, val increment: Int) : CacheMethodEvent()
    data class InvokeDynamic(
        val name: String,
        val descriptor: String,
        val handle: Handle,
        val args: List<Any>,
    ) : CacheMethodEvent()
    data class MultiANewArray(val descriptor: String, val numDimensions: Int) : CacheMethodEvent()
    data class TableSwitch(val min: Int, val max: Int, val default: Label, val labels: List<Label>) : CacheMethodEvent()
    data class LookupSwitch(val default: Label, val keys: IntArray, val labels: List<Label>) : CacheMethodEvent()
    data class LabelEvent(val label: Label) : CacheMethodEvent()
    data class TryCatch(val start: Label, val end: Label, val handler: Label, val type: String?) : CacheMethodEvent()
    data class LineNumber(val line: Int, val start: Label) : CacheMethodEvent()
    data class LocalVariable(
        val name: String,
        val descriptor: String,
        val signature: String?,
        val start: Label,
        val end: Label,
        val index: Int,
    ) : CacheMethodEvent()
    data class Maxs(val maxStack: Int, val maxLocals: Int) : CacheMethodEvent()

    fun replay(target: MethodVisitor, maxStackDelta: Int) {
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
            is Maxs -> target.visitMaxs(maxStack + maxStackDelta, maxLocals)
        }
    }
}
