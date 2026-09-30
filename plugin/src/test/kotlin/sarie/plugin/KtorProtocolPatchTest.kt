package sarie.plugin

import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationContext
import com.android.build.api.instrumentation.InstrumentationParameters
import okhttp3.Protocol
import org.gradle.api.provider.Property
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode

class KtorProtocolPatchTest {

    @Test
    fun `rewrite patches 2_3_13 and 3_2_4 to map HTTP_3 to 6`() {
        for (version in listOf("2.3.13", "3.2.4")) {
            val originalBytes = ktorGolden(version)
            val rewrittenBytes = KtorProtocolPatch.rewrite(originalBytes)
            val loader = ByteArrayClassLoader(
                KtorProtocolPatch.TARGET_CLASS_DOT,
                rewrittenBytes,
                javaClass.classLoader,
            )
            val clazz = loader.loadClass(KtorProtocolPatch.TARGET_CLASS_DOT)
            val field = clazz.getField("\$EnumSwitchMapping\$0")
            val mapping = field.get(null) as IntArray

            assertEquals("HTTP_3 must map to 6 for Ktor $version", 6, mapping[Protocol.HTTP_3.ordinal])
            assertEquals(1, mapping[Protocol.HTTP_1_0.ordinal])
            assertEquals(2, mapping[Protocol.HTTP_1_1.ordinal])
            assertEquals(3, mapping[Protocol.SPDY_3.ordinal])
            assertEquals(4, mapping[Protocol.HTTP_2.ordinal])
            assertEquals(5, mapping[Protocol.H2_PRIOR_KNOWLEDGE.ordinal])
            assertEquals(6, mapping[Protocol.QUIC.ordinal])
        }
    }

    @Test
    fun `rewrite passes 3_3_0 through unchanged`() {
        val originalBytes = ktorGolden("3.3.0")
        val rewrittenBytes = KtorProtocolPatch.rewrite(originalBytes)
        assertArrayEquals(originalBytes, rewrittenBytes)
    }

    @Test
    fun `tampered shape fails with IllegalStateException`() {
        val originalBytes = ktorGolden("2.3.13")
        val reader = ClassReader(originalBytes)
        val classNode = ClassNode()
        reader.accept(classNode, 0)
        val clinit = classNode.methods.first { it.name == "<clinit>" }
        val quicInsn = clinit.instructions.first { insn ->
            insn is FieldInsnNode && insn.name == "QUIC"
        } as FieldInsnNode
        quicInsn.name = "TAMPERED_DROP_QUIC"
        val writer = ClassWriter(0)
        classNode.accept(writer)
        val tamperedBytes = writer.toByteArray()

        val exception = assertThrows(IllegalStateException::class.java) {
            KtorProtocolPatch.rewrite(tamperedBytes)
        }
        assertTrue(
            "Exception message should mention class name",
            exception.message!!.contains("OkUtilsKt\$WhenMappings"),
        )
        assertTrue(
            "Exception message should mention stores",
            exception.message!!.contains("stores"),
        )
    }

    @Test
    fun `isInstrumentable true only for exact class name`() {
        val factory = object : KtorProtocolPatchFactory() {
            override val parameters: Property<InstrumentationParameters.None>
                get() = throw UnsupportedOperationException()
            override val instrumentationContext: InstrumentationContext
                get() = throw UnsupportedOperationException()
        }
        assertTrue(factory.isInstrumentable(fakeClassData("io.ktor.client.engine.okhttp.OkUtilsKt\$WhenMappings")))
        for (other in listOf(
            "io.ktor.client.engine.okhttp.OkUtilsKt",
            "io.ktor.client.engine.okhttp.OkHttp",
            "okhttp3.internal.connection.ConnectInterceptor",
            "okhttp3.Cache\$Entry",
            "com.example.WhenMappings",
            "",
        )) {
            assertFalse(other, factory.isInstrumentable(fakeClassData(other)))
        }
    }
}

private fun ktorGolden(version: String): ByteArray =
    checkNotNull(
        KtorProtocolPatchTest::class.java.getResourceAsStream(
            "/ktor/$version/OkUtilsKt\$WhenMappings.class",
        ),
    ) { "missing golden resource /ktor/$version/OkUtilsKt\$WhenMappings.class" }.readBytes()

private class ByteArrayClassLoader(
    private val targetClassName: String,
    private val classBytes: ByteArray,
    parent: ClassLoader,
) : ClassLoader(parent) {
    override fun findClass(name: String): Class<*> {
        if (name == targetClassName) {
            return defineClass(name, classBytes, 0, classBytes.size)
        }
        return super.findClass(name)
    }
}

private fun fakeClassData(className: String): ClassData = object : ClassData {
    override val className: String = className
    override val classAnnotations: List<String> = emptyList()
    override val interfaces: List<String> = emptyList()
    override val superClasses: List<String> = emptyList()
}
