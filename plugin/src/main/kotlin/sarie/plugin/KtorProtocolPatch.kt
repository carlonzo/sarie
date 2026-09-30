package sarie.plugin

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.FrameNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TryCatchBlockNode
import org.objectweb.asm.tree.VarInsnNode

abstract class KtorProtocolPatchFactory : AsmClassVisitorFactory<InstrumentationParameters.None> {
    override fun isInstrumentable(classData: ClassData): Boolean =
        KtorProtocolPatch.isTargetClass(classData.className)

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor,
    ): ClassVisitor = KtorProtocolPatchClassVisitor(nextClassVisitor)
}

internal class KtorProtocolPatchClassVisitor(
    private val nextVisitor: ClassVisitor,
) : ClassNode(Opcodes.ASM9) {
    override fun visitEnd() {
        super.visitEnd()
        KtorProtocolPatch.patchClassNode(this)
        accept(nextVisitor)
    }
}

object KtorProtocolPatch {
    const val TARGET_CLASS_DOT: String = "io.ktor.client.engine.okhttp.OkUtilsKt\$WhenMappings"
    const val TARGET_CLASS_INTERNAL: String = "io/ktor/client/engine/okhttp/OkUtilsKt\$WhenMappings"

    val EXPECTED_STORES: Map<String, Int> = mapOf(
        "HTTP_1_0" to 1,
        "HTTP_1_1" to 2,
        "SPDY_3" to 3,
        "HTTP_2" to 4,
        "H2_PRIOR_KNOWLEDGE" to 5,
        "QUIC" to 6,
    )

    fun isTargetClass(className: String): Boolean = className == TARGET_CLASS_DOT

    fun rewrite(classBytes: ByteArray): ByteArray {
        val reader = ClassReader(classBytes)
        check(reader.className == TARGET_CLASS_INTERNAL) {
            "okhttp-cronet: expected class $TARGET_CLASS_INTERNAL, got ${reader.className}"
        }
        val classNode = ClassNode()
        reader.accept(classNode, ClassReader.SKIP_FRAMES)
        val clinit = findClinit(classNode)
        val stores = extractStores(clinit)
        if (stores.containsKey("HTTP_3")) {
            return classBytes
        }
        verifyStores(classNode.name, stores)
        patchClinit(classNode.name, clinit)
        val writer = CallServerRewriter.framingWriter(reader)
        classNode.accept(writer)
        return writer.toByteArray()
    }

    internal fun patchClassNode(classNode: ClassNode) {
        val clinit = findClinit(classNode)
        val stores = extractStores(clinit)
        if (stores.containsKey("HTTP_3")) {
            return
        }
        verifyStores(classNode.name, stores)
        patchClinit(classNode.name, clinit)
    }

    private fun findClinit(classNode: ClassNode): MethodNode =
        classNode.methods.firstOrNull { it.name == "<clinit>" && it.desc == "()V" }
            ?: error("okhttp-cronet: <clinit>()V not found in ${classNode.name}; refusing to rewrite")

    internal fun extractStores(clinit: MethodNode): Map<String, Int> {
        val stores = linkedMapOf<String, Int>()
        val insns = clinit.instructions.filter { it.opcode >= 0 }
        for (i in 0 until insns.size - 3) {
            val getstatic = insns[i] as? FieldInsnNode ?: continue
            if (getstatic.opcode != Opcodes.GETSTATIC ||
                getstatic.owner != "okhttp3/Protocol" ||
                getstatic.desc != "Lokhttp3/Protocol;"
            ) {
                continue
            }
            val invokevirtual = insns[i + 1] as? MethodInsnNode ?: continue
            if (invokevirtual.opcode != Opcodes.INVOKEVIRTUAL ||
                invokevirtual.owner != "okhttp3/Protocol" ||
                invokevirtual.name != "ordinal" ||
                invokevirtual.desc != "()I"
            ) {
                continue
            }
            val constValue = intConstantValue(insns[i + 2]) ?: continue
            val iastore = insns[i + 3]
            if (iastore.opcode != Opcodes.IASTORE) {
                continue
            }
            stores[getstatic.name] = constValue
        }
        return stores
    }

    private fun intConstantValue(insn: AbstractInsnNode): Int? = when (insn.opcode) {
        Opcodes.ICONST_M1 -> -1
        Opcodes.ICONST_0 -> 0
        Opcodes.ICONST_1 -> 1
        Opcodes.ICONST_2 -> 2
        Opcodes.ICONST_3 -> 3
        Opcodes.ICONST_4 -> 4
        Opcodes.ICONST_5 -> 5
        Opcodes.BIPUSH, Opcodes.SIPUSH -> (insn as? IntInsnNode)?.operand
        Opcodes.LDC -> ((insn as? LdcInsnNode)?.cst as? Int)
        else -> null
    }

    private fun verifyStores(className: String, stores: Map<String, Int>) {
        check(stores == EXPECTED_STORES) {
            "okhttp-cronet: $className stores $stores do not match expected $EXPECTED_STORES; refusing to rewrite"
        }
    }

    private fun patchClinit(className: String, clinit: MethodNode) {
        val iter = clinit.instructions.iterator()
        while (iter.hasNext()) {
            if (iter.next() is FrameNode) {
                iter.remove()
            }
        }

        val putstaticInsn = clinit.instructions.findLast { insn ->
            insn.opcode == Opcodes.PUTSTATIC &&
                insn is FieldInsnNode &&
                insn.name == "\$EnumSwitchMapping\$0" &&
                insn.desc == "[I"
        } ?: error("okhttp-cronet: putstatic \$EnumSwitchMapping$0 not found in $className")

        var target: AbstractInsnNode? = putstaticInsn.previous
        while (target != null && target.opcode < 0) {
            target = target.previous
        }
        check(target != null && target.opcode == Opcodes.ALOAD && (target as VarInsnNode).`var` == 0) {
            "okhttp-cronet: expected ALOAD 0 immediately before putstatic \$EnumSwitchMapping$0 in $className"
        }

        val tryStart = LabelNode()
        val tryEnd = LabelNode()
        val handler = LabelNode()
        val next = LabelNode()

        val patchInsns = InsnList().apply {
            add(tryStart)
            add(VarInsnNode(Opcodes.ALOAD, 0))
            add(FieldInsnNode(Opcodes.GETSTATIC, "okhttp3/Protocol", "HTTP_3", "Lokhttp3/Protocol;"))
            add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "okhttp3/Protocol", "ordinal", "()I", false))
            add(IntInsnNode(Opcodes.BIPUSH, 6))
            add(InsnNode(Opcodes.IASTORE))
            add(tryEnd)
            add(JumpInsnNode(Opcodes.GOTO, next))
            add(handler)
            add(VarInsnNode(Opcodes.ASTORE, 1))
            add(next)
        }

        clinit.tryCatchBlocks.add(
            TryCatchBlockNode(tryStart, tryEnd, handler, "java/lang/NoSuchFieldError")
        )
        clinit.instructions.insertBefore(target, patchInsns)
        clinit.maxStack = maxOf(clinit.maxStack, 3)
        clinit.maxLocals = maxOf(clinit.maxLocals, 2)
    }
}
