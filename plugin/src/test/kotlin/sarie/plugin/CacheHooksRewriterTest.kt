package sarie.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Cache.Entry source-constructor and CacheStrategy.Factory.computeCandidate substitutions,
 * over every pinned recipe. writeTo's HttpUrl.isHttps stays. A second isHttps in a targeted
 * method, a local 6 that is not Okio.buffer, or a moved handshake sequence fails closed.
 */
class CacheHooksRewriterTest {

    @Test
    fun `entry constructor replaces the one HttpUrl isHttps for every recipe variant`() {
        for ((version, variant) in allRecipeVariants()) {
            val golden = cacheEntryStock(version, variant)
            val rewritten = CacheHooksRewriter.rewriteEntry(golden)
            assertSubstitution(
                "$version/$variant",
                methodInsns(golden, InstrumentationTargets.CACHE_ENTRY_INIT, InstrumentationTargets.CACHE_ENTRY_INIT_DESC),
                methodInsns(rewritten, InstrumentationTargets.CACHE_ENTRY_INIT, InstrumentationTargets.CACHE_ENTRY_INIT_DESC),
                CacheEntryGuard.HTTPS,
                listOf(
                    "ALOAD ${InstrumentationTargets.CACHE_SOURCE_LOCAL}",
                    "INVOKESTATIC ${InstrumentationTargets.CACHE_HOOKS_OWNER}.${InstrumentationTargets.EXPECT_TLS_BLOCK} " +
                        InstrumentationTargets.EXPECT_TLS_BLOCK_DESC,
                ),
            )
            assertEquals(
                "$version/$variant",
                emptyList<String>(),
                CacheEntryGuard.verify(
                    methodInsns(golden, InstrumentationTargets.CACHE_ENTRY_INIT, InstrumentationTargets.CACHE_ENTRY_INIT_DESC),
                ),
            )
        }
    }

    @Test
    fun `writeTo HttpUrl isHttps is untouched for every recipe variant`() {
        for ((version, variant) in allRecipeVariants()) {
            val golden = cacheEntryStock(version, variant)
            val rewritten = CacheHooksRewriter.rewriteEntry(golden)
            val (writeName, writeDesc) = methodNamed(golden, "writeTo")
            val stockWrite = methodInsns(golden, writeName, writeDesc)
            val outWrite = methodInsns(rewritten, writeName, writeDesc)
            assertEquals("$version/$variant", stockWrite, outWrite)
            assertEquals("$version/$variant", 1, outWrite.count { it == CacheEntryGuard.HTTPS })
            assertTrue("$version/$variant", outWrite.none { it.contains("CacheHooks") })
            assertOtherMethodsUntouched(
                golden,
                rewritten,
                InstrumentationTargets.CACHE_ENTRY_INIT,
                InstrumentationTargets.CACHE_ENTRY_INIT_DESC,
            )
        }
    }

    @Test
    fun `a second isHttps added only in writeTo does not fail the entry rewrite`() {
        val golden = cacheEntryStock("5.5.0", Variant.JVM)
        val (_, writeDesc) = methodNamed(golden, "writeTo")
        val tampered = tamperMethod(golden, "writeTo", writeDesc) { delegate ->
            object : MethodVisitor(Opcodes.ASM9, delegate) {
                override fun visitMethodInsn(
                    opcode: Int,
                    owner: String,
                    name: String,
                    descriptor: String,
                    isInterface: Boolean,
                ) {
                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                    if (opcode == Opcodes.INVOKEVIRTUAL && owner == "okhttp3/HttpUrl" && name == "isHttps") {
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                    }
                }
            }
        }
        val rewritten = CacheHooksRewriter.rewriteEntry(tampered)
        val ctor = methodInsns(
            rewritten,
            InstrumentationTargets.CACHE_ENTRY_INIT,
            InstrumentationTargets.CACHE_ENTRY_INIT_DESC,
        )
        assertEquals(0, ctor.count { it == CacheEntryGuard.HTTPS })
        assertEquals(1, ctor.count { it.contains("expectTlsBlock") })
        val write = methodInsns(rewritten, "writeTo", writeDesc)
        assertEquals(2, write.count { it == CacheEntryGuard.HTTPS })
    }

    @Test
    fun `computeCandidate replaces Request isHttps and keeps the handshake sequence`() {
        for ((version, variant) in allRecipeVariants()) {
            val golden = cacheStrategyStock(version, variant)
            val rewritten = CacheHooksRewriter.rewriteStrategy(golden)
            val stock = methodInsns(
                golden,
                InstrumentationTargets.COMPUTE_CANDIDATE,
                InstrumentationTargets.COMPUTE_CANDIDATE_DESC,
            )
            val out = methodInsns(
                rewritten,
                InstrumentationTargets.COMPUTE_CANDIDATE,
                InstrumentationTargets.COMPUTE_CANDIDATE_DESC,
            )
            assertSubstitution(
                "$version/$variant",
                stock,
                out,
                CacheStrategyGuard.HTTPS,
                listOf(
                    "INVOKESTATIC ${InstrumentationTargets.CACHE_HOOKS_OWNER}.${InstrumentationTargets.REQUIRE_HANDSHAKE} " +
                        InstrumentationTargets.REQUIRE_HANDSHAKE_DESC,
                ),
            )
            assertTrue("$version/$variant", out.contains(CacheStrategyGuard.HANDSHAKE))
            assertTrue("$version/$variant", out.any { it.startsWith("IFNONNULL ") })
            assertEquals("$version/$variant", emptyList<String>(), CacheStrategyGuard.verify(stock))
            assertOtherMethodsUntouched(
                golden,
                rewritten,
                InstrumentationTargets.COMPUTE_CANDIDATE,
                InstrumentationTargets.COMPUTE_CANDIDATE_DESC,
            )
        }
    }

    @Test
    fun `guard visitor emits the same substitution for every recipe variant`() {
        for ((version, variant) in allRecipeVariants()) {
            val entry = cacheEntryStock(version, variant)
            val viaVisitor = guarded(version, entry)
            assertEquals(
                "$version/$variant entry",
                methodInsns(CacheHooksRewriter.rewriteEntry(entry), InstrumentationTargets.CACHE_ENTRY_INIT, InstrumentationTargets.CACHE_ENTRY_INIT_DESC),
                methodInsns(viaVisitor, InstrumentationTargets.CACHE_ENTRY_INIT, InstrumentationTargets.CACHE_ENTRY_INIT_DESC),
            )
            val strategy = cacheStrategyStock(version, variant)
            val strategyViaVisitor = guarded(version, strategy)
            assertEquals(
                "$version/$variant strategy",
                methodInsns(
                    CacheHooksRewriter.rewriteStrategy(strategy),
                    InstrumentationTargets.COMPUTE_CANDIDATE,
                    InstrumentationTargets.COMPUTE_CANDIDATE_DESC,
                ),
                methodInsns(
                    strategyViaVisitor,
                    InstrumentationTargets.COMPUTE_CANDIDATE,
                    InstrumentationTargets.COMPUTE_CANDIDATE_DESC,
                ),
            )
        }
    }

    @Test
    fun `rewritten classes re-parse for every recipe variant`() {
        for ((version, variant) in allRecipeVariants()) {
            val entry = CacheHooksRewriter.rewriteEntry(cacheEntryStock(version, variant))
            ClassReader(entry).accept(object : ClassVisitor(Opcodes.ASM9) {}, ClassReader.EXPAND_FRAMES)
            assertEquals(InstrumentTarget.CACHE_ENTRY.internalName, ClassReader(entry).className)
            val strategy = CacheHooksRewriter.rewriteStrategy(cacheStrategyStock(version, variant))
            ClassReader(strategy).accept(object : ClassVisitor(Opcodes.ASM9) {}, ClassReader.EXPAND_FRAMES)
            assertEquals(InstrumentTarget.CACHE_STRATEGY_FACTORY.internalName, ClassReader(strategy).className)
        }
    }

    @Test
    fun `entry guard fails closed on a second isHttps in the constructor`() {
        val tampered = tamperMethod(
            cacheEntryStock("5.4.0", Variant.ANDROID),
            InstrumentationTargets.CACHE_ENTRY_INIT,
            InstrumentationTargets.CACHE_ENTRY_INIT_DESC,
        ) { delegate ->
            object : MethodVisitor(Opcodes.ASM9, delegate) {
                override fun visitMethodInsn(
                    opcode: Int,
                    owner: String,
                    name: String,
                    descriptor: String,
                    isInterface: Boolean,
                ) {
                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                    if (opcode == Opcodes.INVOKEVIRTUAL && owner == "okhttp3/HttpUrl" && name == "isHttps") {
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                    }
                }
            }
        }
        val problems = CacheEntryGuard.verify(
            methodInsns(tampered, InstrumentationTargets.CACHE_ENTRY_INIT, InstrumentationTargets.CACHE_ENTRY_INIT_DESC),
        )
        assertTrue(problems.toString(), problems.any { it.contains("exactly one HttpUrl.isHttps") })
        val error = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            guarded("5.4.0", tampered)
        }
        assertTrue(error.message, error.message!!.contains("okhttp-cronet"))
        assertTrue(error.message, error.message!!.contains("exactly one HttpUrl.isHttps"))
    }

    @Test
    fun `entry guard fails closed when local 6 is not the Okio buffer result`() {
        val tampered = tamperMethod(
            cacheEntryStock("5.5.0", Variant.JVM),
            InstrumentationTargets.CACHE_ENTRY_INIT,
            InstrumentationTargets.CACHE_ENTRY_INIT_DESC,
        ) { delegate ->
            object : MethodVisitor(Opcodes.ASM9, delegate) {
                override fun visitVarInsn(opcode: Int, value: Int) {
                    if (opcode == Opcodes.ASTORE && value == InstrumentationTargets.CACHE_SOURCE_LOCAL) {
                        super.visitVarInsn(opcode, value + 1)
                    } else {
                        super.visitVarInsn(opcode, value)
                    }
                }
            }
        }
        val problems = CacheEntryGuard.verify(
            methodInsns(tampered, InstrumentationTargets.CACHE_ENTRY_INIT, InstrumentationTargets.CACHE_ENTRY_INIT_DESC),
        )
        assertTrue(problems.toString(), problems.any { it.contains("not the Okio.buffer result") })
        org.junit.Assert.assertThrows(IllegalStateException::class.java) { guarded("5.5.0", tampered) }
    }

    @Test
    fun `strategy guard fails closed on a second Request isHttps`() {
        val tampered = tamperMethod(
            cacheStrategyStock("5.5.0", Variant.ANDROID),
            InstrumentationTargets.COMPUTE_CANDIDATE,
            InstrumentationTargets.COMPUTE_CANDIDATE_DESC,
        ) { delegate ->
            object : MethodVisitor(Opcodes.ASM9, delegate) {
                override fun visitMethodInsn(
                    opcode: Int,
                    owner: String,
                    name: String,
                    descriptor: String,
                    isInterface: Boolean,
                ) {
                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                    if (opcode == Opcodes.INVOKEVIRTUAL && owner == "okhttp3/Request" && name == "isHttps") {
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                    }
                }
            }
        }
        val problems = CacheStrategyGuard.verify(
            methodInsns(tampered, InstrumentationTargets.COMPUTE_CANDIDATE, InstrumentationTargets.COMPUTE_CANDIDATE_DESC),
        )
        assertTrue(problems.toString(), problems.any { it.contains("exactly one Request.isHttps") })
        org.junit.Assert.assertThrows(IllegalStateException::class.java) { guarded("5.5.0", tampered) }
    }

    @Test
    fun `strategy guard fails closed when the handshake sequence moves`() {
        val tampered = tamperMethod(
            cacheStrategyStock("5.4.0", Variant.JVM),
            InstrumentationTargets.COMPUTE_CANDIDATE,
            InstrumentationTargets.COMPUTE_CANDIDATE_DESC,
        ) { delegate ->
            object : MethodVisitor(Opcodes.ASM9, delegate) {
                override fun visitMethodInsn(
                    opcode: Int,
                    owner: String,
                    name: String,
                    descriptor: String,
                    isInterface: Boolean,
                ) {
                    val renamed = if (owner == "okhttp3/Response" && name == "handshake") "handshakeMoved" else name
                    super.visitMethodInsn(opcode, owner, renamed, descriptor, isInterface)
                }
            }
        }
        val problems = CacheStrategyGuard.verify(
            methodInsns(tampered, InstrumentationTargets.COMPUTE_CANDIDATE, InstrumentationTargets.COMPUTE_CANDIDATE_DESC),
        )
        assertTrue(problems.toString(), problems.any { it.contains("handshake(); ifnonnull") })
        val error = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            guarded("5.4.0", tampered)
        }
        assertTrue(error.message, error.message!!.contains("handshakeMoved"))
    }

    @Test
    fun `a non-target class is not rewritten`() {
        val other = minimalClass("com/example/NotCache")
        val error = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            CacheHooksRewriter.rewriteEntry(other)
        }
        assertTrue(error.message, error.message!!.contains("okhttp-cronet"))
    }

    @Test
    fun `guard fails closed when the cache site method is missing`() {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, InstrumentTarget.CACHE_ENTRY.internalName, null, "java/lang/Object", null)
        writer.visitEnd()
        org.junit.Assert.assertThrows(IllegalStateException::class.java) { guarded("5.5.0", writer.toByteArray()) }
    }

    private fun guarded(version: String, classBytes: ByteArray): ByteArray {
        val reader = ClassReader(classBytes)
        val writer = CallServerRewriter.framingWriter(reader)
        val guard = RecipeRegistry.forVersion(version).guard
        reader.accept(ConnectInterceptorGuardVisitor(writer, guard), ClassReader.SKIP_FRAMES)
        return writer.toByteArray()
    }

    private fun minimalClass(internalName: String): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        writer.visitEnd()
        return writer.toByteArray()
    }
}

private fun cacheEntryStock(version: String, variant: Variant): ByteArray =
    stock(version, variant, InstrumentTarget.CACHE_ENTRY.fileName)

private fun cacheStrategyStock(version: String, variant: Variant): ByteArray =
    stock(version, variant, InstrumentTarget.CACHE_STRATEGY_FACTORY.fileName)

private fun methodInsns(classBytes: ByteArray, methodName: String, methodDesc: String): List<String> {
    var captured: List<String> = emptyList()
    ClassReader(classBytes).accept(object : ClassVisitor(Opcodes.ASM9) {
        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor? {
            if (name != methodName || descriptor != methodDesc) return null
            return RecordingMethodVisitor(object : MethodVisitor(Opcodes.ASM9) {}) { captured = it }
        }
    }, 0)
    return captured
}

private fun methodNamed(classBytes: ByteArray, methodName: String): Pair<String, String> {
    var found: Pair<String, String>? = null
    ClassReader(classBytes).accept(object : ClassVisitor(Opcodes.ASM9) {
        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor? {
            if (name == methodName && found == null) found = name to descriptor
            return null
        }
    }, 0)
    return checkNotNull(found) { "no method $methodName" }
}

private fun methodKeys(classBytes: ByteArray): List<Pair<String, String>> {
    val keys = mutableListOf<Pair<String, String>>()
    ClassReader(classBytes).accept(object : ClassVisitor(Opcodes.ASM9) {
        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor? {
            keys += name to descriptor
            return null
        }
    }, 0)
    return keys
}

private fun assertOtherMethodsUntouched(
    golden: ByteArray,
    rewritten: ByteArray,
    rewrittenName: String,
    rewrittenDesc: String,
) {
    assertEquals(methodKeys(golden), methodKeys(rewritten))
    for ((name, desc) in methodKeys(golden)) {
        if (name == rewrittenName && desc == rewrittenDesc) continue
        assertEquals("$name$desc", methodInsns(golden, name, desc), methodInsns(rewritten, name, desc))
    }
}

private fun assertSubstitution(
    label: String,
    stockInsns: List<String>,
    rewrittenInsns: List<String>,
    replaced: String,
    replacement: List<String>,
) {
    val at = stockInsns.indexOf(replaced)
    assertTrue("$label missing $replaced", at >= 0)
    assertEquals(label, 1, stockInsns.count { it == replaced })
    assertEquals("$label prefix", stockInsns.take(at), rewrittenInsns.take(at))
    assertEquals("$label replacement", replacement, rewrittenInsns.subList(at, at + replacement.size))
    assertEquals("$label tail", stockInsns.drop(at + 1), rewrittenInsns.drop(at + replacement.size))
}

private fun tamperMethod(
    classBytes: ByteArray,
    methodName: String,
    methodDesc: String,
    mutate: (MethodVisitor) -> MethodVisitor,
): ByteArray {
    val writer = ClassWriter(0)
    ClassReader(classBytes).accept(object : ClassVisitor(Opcodes.ASM9, writer) {
        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor {
            val passthrough = super.visitMethod(access, name, descriptor, signature, exceptions)
            return if (name == methodName && descriptor == methodDesc) mutate(passthrough) else passthrough
        }
    }, 0)
    return writer.toByteArray()
}
