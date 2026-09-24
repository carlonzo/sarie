package sarie.plugin

import java.util.IdentityHashMap
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes

/**
 * Javap-style instruction stream. Labels are numbered in first-encounter order so the
 * recorded shape is stable across visits of the same method. Line numbers, frames and
 * try/catch blocks are not instructions and are not recorded.
 */
internal class InstructionRecorder {
    val insns: List<String>
        field = mutableListOf<String>()
    private val labelIds = IdentityHashMap<Label, Int>()
    private var labelSeq = 0

    fun label(label: Label): String {
        val id = labelIds.getOrPut(label) { labelSeq++ }
        return "L$id"
    }

    fun insn(opcode: Int) {
        insns += opcodeName(opcode)
    }

    fun intInsn(opcode: Int, operand: Int) {
        insns += "${opcodeName(opcode)} $operand"
    }

    fun varInsn(opcode: Int, value: Int) {
        insns += "${opcodeName(opcode)} $value"
    }

    fun typeInsn(opcode: Int, type: String) {
        insns += "${opcodeName(opcode)} $type"
    }

    fun fieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
        insns += "${opcodeName(opcode)} $owner.$name $descriptor"
    }

    fun methodInsn(opcode: Int, owner: String, name: String, descriptor: String) {
        insns += "${opcodeName(opcode)} $owner.$name $descriptor"
    }

    fun jumpInsn(opcode: Int, label: Label) {
        insns += "${opcodeName(opcode)} ${label(label)}"
    }

    fun ldc(value: Any) {
        insns += "LDC $value"
    }

    fun iinc(value: Int, increment: Int) {
        insns += "IINC $value $increment"
    }

    fun invokeDynamic(name: String, descriptor: String) {
        insns += "INVOKEDYNAMIC $name $descriptor"
    }

    fun multiANewArray(descriptor: String, numDimensions: Int) {
        insns += "MULTIANEWARRAY $descriptor $numDimensions"
    }

    fun tableSwitch(min: Int, max: Int) {
        insns += "TABLESWITCH $min $max"
    }

    fun lookupSwitch(keys: IntArray) {
        insns += "LOOKUPSWITCH ${keys.joinToString(",")}"
    }

    companion object {
        // Readable names; asm-util's Printer.OPCODES is not on the plugin classpath.
        private val OPCODE_NAMES: Map<Int, String> = mapOf(
            Opcodes.NOP to "NOP",
            Opcodes.ACONST_NULL to "ACONST_NULL",
            Opcodes.ICONST_M1 to "ICONST_M1",
            Opcodes.ICONST_0 to "ICONST_0",
            Opcodes.ICONST_1 to "ICONST_1",
            Opcodes.ICONST_2 to "ICONST_2",
            Opcodes.ICONST_3 to "ICONST_3",
            Opcodes.ICONST_4 to "ICONST_4",
            Opcodes.ICONST_5 to "ICONST_5",
            Opcodes.LCONST_0 to "LCONST_0",
            Opcodes.LCONST_1 to "LCONST_1",
            Opcodes.FCONST_0 to "FCONST_0",
            Opcodes.FCONST_1 to "FCONST_1",
            Opcodes.FCONST_2 to "FCONST_2",
            Opcodes.DCONST_0 to "DCONST_0",
            Opcodes.DCONST_1 to "DCONST_1",
            Opcodes.BIPUSH to "BIPUSH",
            Opcodes.SIPUSH to "SIPUSH",
            Opcodes.ILOAD to "ILOAD",
            Opcodes.LLOAD to "LLOAD",
            Opcodes.FLOAD to "FLOAD",
            Opcodes.DLOAD to "DLOAD",
            Opcodes.ALOAD to "ALOAD",
            Opcodes.IALOAD to "IALOAD",
            Opcodes.LALOAD to "LALOAD",
            Opcodes.AALOAD to "AALOAD",
            Opcodes.BALOAD to "BALOAD",
            Opcodes.ISTORE to "ISTORE",
            Opcodes.LSTORE to "LSTORE",
            Opcodes.FSTORE to "FSTORE",
            Opcodes.DSTORE to "DSTORE",
            Opcodes.ASTORE to "ASTORE",
            Opcodes.IASTORE to "IASTORE",
            Opcodes.AASTORE to "AASTORE",
            Opcodes.POP to "POP",
            Opcodes.POP2 to "POP2",
            Opcodes.DUP to "DUP",
            Opcodes.DUP_X1 to "DUP_X1",
            Opcodes.DUP_X2 to "DUP_X2",
            Opcodes.DUP2 to "DUP2",
            Opcodes.SWAP to "SWAP",
            Opcodes.IADD to "IADD",
            Opcodes.LADD to "LADD",
            Opcodes.ISUB to "ISUB",
            Opcodes.IMUL to "IMUL",
            Opcodes.IDIV to "IDIV",
            Opcodes.IREM to "IREM",
            Opcodes.INEG to "INEG",
            Opcodes.ISHL to "ISHL",
            Opcodes.ISHR to "ISHR",
            Opcodes.IUSHR to "IUSHR",
            Opcodes.IAND to "IAND",
            Opcodes.IOR to "IOR",
            Opcodes.IXOR to "IXOR",
            Opcodes.LCMP to "LCMP",
            Opcodes.I2L to "I2L",
            Opcodes.I2F to "I2F",
            Opcodes.L2I to "L2I",
            Opcodes.I2B to "I2B",
            Opcodes.I2C to "I2C",
            Opcodes.I2S to "I2S",
            Opcodes.IFEQ to "IFEQ",
            Opcodes.IFNE to "IFNE",
            Opcodes.IFLT to "IFLT",
            Opcodes.IFGE to "IFGE",
            Opcodes.IFGT to "IFGT",
            Opcodes.IFLE to "IFLE",
            Opcodes.IF_ICMPEQ to "IF_ICMPEQ",
            Opcodes.IF_ICMPNE to "IF_ICMPNE",
            Opcodes.IF_ICMPLT to "IF_ICMPLT",
            Opcodes.IF_ICMPGE to "IF_ICMPGE",
            Opcodes.IF_ICMPGT to "IF_ICMPGT",
            Opcodes.IF_ICMPLE to "IF_ICMPLE",
            Opcodes.IF_ACMPEQ to "IF_ACMPEQ",
            Opcodes.IF_ACMPNE to "IF_ACMPNE",
            Opcodes.GOTO to "GOTO",
            Opcodes.IFNULL to "IFNULL",
            Opcodes.IFNONNULL to "IFNONNULL",
            Opcodes.IRETURN to "IRETURN",
            Opcodes.LRETURN to "LRETURN",
            Opcodes.FRETURN to "FRETURN",
            Opcodes.DRETURN to "DRETURN",
            Opcodes.ARETURN to "ARETURN",
            Opcodes.RETURN to "RETURN",
            Opcodes.GETSTATIC to "GETSTATIC",
            Opcodes.PUTSTATIC to "PUTSTATIC",
            Opcodes.GETFIELD to "GETFIELD",
            Opcodes.PUTFIELD to "PUTFIELD",
            Opcodes.INVOKEVIRTUAL to "INVOKEVIRTUAL",
            Opcodes.INVOKESPECIAL to "INVOKESPECIAL",
            Opcodes.INVOKESTATIC to "INVOKESTATIC",
            Opcodes.INVOKEINTERFACE to "INVOKEINTERFACE",
            Opcodes.NEW to "NEW",
            Opcodes.NEWARRAY to "NEWARRAY",
            Opcodes.ANEWARRAY to "ANEWARRAY",
            Opcodes.ARRAYLENGTH to "ARRAYLENGTH",
            Opcodes.ATHROW to "ATHROW",
            Opcodes.CHECKCAST to "CHECKCAST",
            Opcodes.INSTANCEOF to "INSTANCEOF",
            Opcodes.MONITORENTER to "MONITORENTER",
            Opcodes.MONITOREXIT to "MONITOREXIT",
        )

        fun opcodeName(opcode: Int): String = OPCODE_NAMES[opcode] ?: "0x%02x".format(opcode)
    }
}
