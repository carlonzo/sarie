package dev.okhttpcronet.plugin

import java.util.IdentityHashMap
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

internal const val TRAMPOLINE_DESC: String = "(Lokhttp3/Interceptor\$Chain;)Lokhttp3/Response;"

internal fun stock(variant: String): ByteArray =
    checkNotNull(ConnectInterceptorRewriterTest::class.java.getResourceAsStream("/stock/$variant/ConnectInterceptor.class")) {
        "missing golden resource /stock/$variant/ConnectInterceptor.class"
    }.readBytes()

internal val expectedTrampoline = listOf(
    "ALOAD 1",
    "INVOKESTATIC dev/okhttpcronet/bridge/CronetBridge.intercept $TRAMPOLINE_DESC itf=false",
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
 * excluded: COMPUTE_FRAMES legitimately regenerates those.
 */
internal fun memberDump(bytes: ByteArray): List<String> {
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
