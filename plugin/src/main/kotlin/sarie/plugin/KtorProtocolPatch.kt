package sarie.plugin

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.VarInsnNode

/**
 * Ktor's OkHttp engine before 3.3.0 maps `okhttp3.Protocol` with an exhaustive `when` that has no
 * `HTTP_3` branch, so every h3 response the bridge builds throws `NoWhenBranchMatchedException`.
 * The `when` reads `OkUtilsKt$WhenMappings.$EnumSwitchMapping$0`; this patch adds one store,
 * `HTTP_3 -> 6`, so h3 takes the existing `QUIC` case (ktor-http < 3.3 has no `HTTP_3_0`).
 * Ktor >= 3.3 already stores `HTTP_3 -> 7` and is left alone. Any other shape fails the build.
 */
abstract class KtorProtocolPatchFactory : AsmClassVisitorFactory<InstrumentationParameters.None> {
    override fun isInstrumentable(classData: ClassData): Boolean =
        classData.className == KtorProtocolPatch.TARGET_CLASS_DOT

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor,
    ): ClassVisitor = KtorProtocolPatchClassVisitor(nextClassVisitor)
}

/** Buffers the class, patches `<clinit>`, then replays it into [next]. */
internal class KtorProtocolPatchClassVisitor(private val next: ClassVisitor) : ClassNode(Opcodes.ASM9) {
    override fun visitEnd() {
        super.visitEnd()
        KtorProtocolPatch.patch(this)
        accept(next)
    }
}

internal object KtorProtocolPatch {
    const val TARGET_CLASS_DOT: String = "io.ktor.client.engine.okhttp.OkUtilsKt\$WhenMappings"
    private const val MAPPING_FIELD = "\$EnumSwitchMapping\$0"
    private const val PROTOCOL = "okhttp3/Protocol"

    /** Stores in `<clinit>` order for Ktor 2.0.0 through 3.2.4. */
    val LEGACY_STORES: List<Pair<String, Int>> = listOf(
        "HTTP_1_0" to 1,
        "HTTP_1_1" to 2,
        "SPDY_3" to 3,
        "HTTP_2" to 4,
        "H2_PRIOR_KNOWLEDGE" to 5,
        "QUIC" to 6,
    )

    /** Ktor >= 3.3.0: already maps HTTP_3. */
    val CURRENT_STORES: List<Pair<String, Int>> = LEGACY_STORES + ("HTTP_3" to 7)

    /** Value of the `QUIC` case in the legacy `fromOkHttp` tableswitch. */
    private const val QUIC_CASE = 6

    fun patch(classNode: ClassNode) {
        val clinit = classNode.methods.firstOrNull { it.name == "<clinit>" && it.desc == "()V" }
            ?: error("okhttp-cronet: ${classNode.name} has no <clinit>; refusing to rewrite")
        val insns = clinit.instructions.filter { it.opcode >= 0 }
        val stores = verifiedStores(classNode.name, insns)
        if (stores == CURRENT_STORES) return
        check(stores == LEGACY_STORES) {
            "okhttp-cronet: ${classNode.name} stores $stores match neither Ktor < 3.3 ($LEGACY_STORES) " +
                "nor Ktor >= 3.3 ($CURRENT_STORES); refusing to rewrite"
        }
        // Insert before the trailing `ALOAD 0; PUTSTATIC; RETURN`: every legacy store (and its
        // NoSuchFieldError handler, where Kotlin emitted one) has completed by then.
        val store = InsnList().apply {
            add(VarInsnNode(Opcodes.ALOAD, 0))
            add(FieldInsnNode(Opcodes.GETSTATIC, PROTOCOL, "HTTP_3", "L$PROTOCOL;"))
            add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, PROTOCOL, "ordinal", "()I", false))
            add(IntInsnNode(Opcodes.BIPUSH, QUIC_CASE))
            add(InsnNode(Opcodes.IASTORE))
        }
        clinit.instructions.insertBefore(insns[insns.size - 3], store)
        clinit.maxStack = maxOf(clinit.maxStack, 3)
    }

    /**
     * Returns the ordered `ALOAD 0; GETSTATIC Protocol.X; ordinal(); const; IASTORE` stores after
     * checking that they are the only array stores, that local 0 is the array, and that the method
     * ends with the single `ALOAD 0; PUTSTATIC $EnumSwitchMapping$0; RETURN`.
     */
    private fun verifiedStores(className: String, insns: List<AbstractInsnNode>): List<Pair<String, Int>> {
        val problems = mutableListOf<String>()
        val stores = mutableListOf<Pair<String, Int>>()
        for (i in 4 until insns.size) {
            if (insns[i].opcode != Opcodes.IASTORE) continue
            val load = insns[i - 4] as? VarInsnNode
            val field = insns[i - 3] as? FieldInsnNode
            val ordinal = insns[i - 2] as? MethodInsnNode
            val value = intConstant(insns[i - 1])
            if (load?.opcode != Opcodes.ALOAD || load.`var` != 0 ||
                field?.opcode != Opcodes.GETSTATIC || field.owner != PROTOCOL ||
                ordinal?.owner != PROTOCOL || ordinal.name != "ordinal" || value == null
            ) {
                problems += "IASTORE at $i is not an ALOAD 0 / Protocol.X.ordinal() / const store"
                continue
            }
            stores += field.name to value
        }
        val putStatics = insns.filter { it.opcode == Opcodes.PUTSTATIC }
        if (putStatics.size != 1) problems += "expected exactly one PUTSTATIC, found ${putStatics.size}"
        val tail = insns.takeLast(3)
        val put = tail.getOrNull(1) as? FieldInsnNode
        if (tail.size != 3 ||
            (tail[0] as? VarInsnNode)?.let { it.opcode == Opcodes.ALOAD && it.`var` == 0 } != true ||
            put?.opcode != Opcodes.PUTSTATIC || put.owner != className || put.name != MAPPING_FIELD ||
            tail[2].opcode != Opcodes.RETURN
        ) {
            problems += "<clinit> does not end with ALOAD 0 / PUTSTATIC $MAPPING_FIELD / RETURN"
        }
        check(problems.isEmpty()) {
            "okhttp-cronet: $className does not match the Ktor WhenMappings shape; refusing to rewrite:\n" +
                problems.joinToString("\n") { " - $it" }
        }
        return stores
    }

    private fun intConstant(insn: AbstractInsnNode): Int? = when (insn.opcode) {
        in Opcodes.ICONST_M1..Opcodes.ICONST_5 -> insn.opcode - Opcodes.ICONST_0
        Opcodes.BIPUSH, Opcodes.SIPUSH -> (insn as IntInsnNode).operand
        else -> null
    }
}
