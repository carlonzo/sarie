package sarie.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

class FingerprintTest {

    @Test
    fun `baked constants equal sha256 of the stock goldens`() {
        for ((version, variant) in allRecipeVariants()) {
            val recipe = RecipeRegistry.forVersion(version)
            assertEquals(
                "$version/$variant",
                recipe.fingerprints.getValue(variant),
                Fingerprint.sha256Hex(stock(version, variant)),
            )
        }
    }

    @Test
    fun `mutated intercept body is detected with expected and actual hashes in the message`() {
        for ((version, variant) in allRecipeVariants()) {
            val expected = RecipeRegistry.forVersion(version).fingerprints.getValue(variant)
            val mutated = mutateInterceptBody(stock(version, variant))
            val mutatedHash = Fingerprint.sha256Hex(mutated)
            assertNotEquals("$version/$variant", expected, mutatedHash)

            // The fingerprint check (as the guard task uses it) must fail with BOTH hashes visible.
            val error = assertThrows(AssertionError::class.java) {
                assertEquals(expected, Fingerprint.sha256Hex(mutated))
            }
            assertTrue(error.message, error.message!!.contains(expected))
            assertTrue(error.message, error.message!!.contains(mutatedHash))
        }
    }
}

/**
 * Flip one instruction inside the intercept method body: the first invoke's interface flag
 * is inverted. Everything else is copied verbatim (ClassWriter(0) keeps original frames).
 */
private fun mutateInterceptBody(bytes: ByteArray): ByteArray {
    val writer = ClassWriter(0)
    ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9, writer) {
        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor {
            val target = super.visitMethod(access, name, descriptor, signature, exceptions)
            if (name != "intercept" || descriptor != TRAMPOLINE_DESC) return target
            var mutated = false
            return object : MethodVisitor(Opcodes.ASM9, target) {
                override fun visitMethodInsn(
                    opcode: Int,
                    owner: String,
                    name: String,
                    descriptor: String,
                    isInterface: Boolean,
                ) {
                    if (!mutated) {
                        mutated = true
                        super.visitMethodInsn(opcode, owner, name, descriptor, !isInterface)
                    } else {
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                    }
                }
            }
        }
    }, 0)
    return writer.toByteArray()
}
