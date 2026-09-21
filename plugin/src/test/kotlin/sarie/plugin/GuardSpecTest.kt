package sarie.plugin

import org.junit.Assert.assertEquals
import org.junit.Test
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * GuardSpec.verify against the committed golden classes: the declarative spec must accept the
 * stock instruction stream and reject tampered shapes with the exact messages the original
 * hardcoded verifyStockShape produced.
 */
class GuardSpecTest {

    @Test
    fun `guard spec accepts the stock golden instructions for every recipe`() {
        for ((version, variant) in allRecipeVariants()) {
            val insns = recordedInsns(stock(version, variant))
            assertEquals(
                "$version/$variant",
                emptyList<String>(),
                RecipeRegistry.forVersion(version).guard.verify(insns),
            )
        }
    }

    @Test
    fun `guard spec rejects tampered proceed with the legacy message`() {
        for ((version, variant) in allRecipeVariants()) {
            val insns = recordedInsns(tamper(stock(version, variant)) { insn ->
                object : MethodVisitor(Opcodes.ASM9, insn) {
                    override fun visitMethodInsn(
                        opcode: Int,
                        owner: String,
                        name: String,
                        descriptor: String,
                        isInterface: Boolean,
                    ) {
                        val renamed = if (name == "proceed") "proceedTampered" else name
                        super.visitMethodInsn(opcode, owner, renamed, descriptor, isInterface)
                    }
                }
            })
            assertEquals(
                "$version/$variant",
                listOf("expected exactly one RealInterceptorChain.proceed call"),
                RecipeRegistry.forVersion(version).guard.verify(insns),
            )
        }
    }

    @Test
    fun `guard spec rejects missing checkcast with the legacy messages`() {
        for ((version, variant) in allRecipeVariants()) {
            val insns = recordedInsns(tamper(stock(version, variant)) { insn ->
                object : MethodVisitor(Opcodes.ASM9, insn) {
                    override fun visitTypeInsn(opcode: Int, type: String) {
                        if (opcode != Opcodes.CHECKCAST) super.visitTypeInsn(opcode, type)
                    }
                }
            })
            assertEquals(
                "$version/$variant",
                listOf(
                    "expected CHECKCAST okhttp3/internal/http/RealInterceptorChain within the first 5 instructions",
                    "expected exactly one CHECKCAST okhttp3/internal/http/RealInterceptorChain",
                ),
                RecipeRegistry.forVersion(version).guard.verify(insns),
            )
        }
    }
}
