package sarie.plugin

import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationContext
import com.android.build.api.instrumentation.InstrumentationParameters
import org.gradle.api.provider.Property
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Factory-level exclusivity check through the real AGP ClassData interface; the visitor
 * behaviour itself is covered by [ConnectInterceptorGuardVisitorTest].
 */
class ConnectInterceptorVisitorFactoryTest {

    @Test
    fun `isInstrumentable is exclusive to the exact ConnectInterceptor class`() {
        val factory = factory()
        assertTrue(factory.isInstrumentable(fakeClassData("okhttp3.internal.connection.ConnectInterceptor")))
        for (other in listOf(
            "okhttp3.internal.connection.RetryAndFollowUpInterceptor",
            "okhttp3.internal.connection.RealCall",
            "okhttp3.internal.http.RealInterceptorChain",
            "okhttp3.OkHttpClient",
            "okhttp3.internal.connection.ConnectInterceptorKt",
            "com.example.Unrelated",
            "",
        )) {
            assertFalse(other, factory.isInstrumentable(fakeClassData(other)))
        }
    }
    @Test
    fun `createClassVisitor creates guard visitor with configured okhttp version parameter`() {
        val project = org.gradle.testfixtures.ProjectBuilder.builder().build()
        val paramsProperty = project.objects.property(OkhttpCronetInstrumentationParams::class.java)
        paramsProperty.set(fakeParams("5.5.0"))

        val factory = object : ConnectInterceptorVisitorFactory() {
            override val parameters: Property<OkhttpCronetInstrumentationParams> = paramsProperty
            override val instrumentationContext: InstrumentationContext
                get() = throw UnsupportedOperationException("not needed for createClassVisitor")
        }

        val classContext = object : com.android.build.api.instrumentation.ClassContext {
            override val currentClassData: ClassData = fakeClassData("okhttp3.internal.connection.ConnectInterceptor")
            override fun loadClassData(className: String): ClassData? = null
        }

        val visitor = factory.createClassVisitor(classContext, org.objectweb.asm.ClassWriter(0))
        assertTrue(visitor is ConnectInterceptorGuardVisitor)
    }

    @Test
    fun `createClassVisitor falls back to family guard when okhttp version is omitted`() {
        val project = org.gradle.testfixtures.ProjectBuilder.builder().build()
        val paramsProperty = project.objects.property(OkhttpCronetInstrumentationParams::class.java)
        paramsProperty.set(fakeParams(null))

        val factory = object : ConnectInterceptorVisitorFactory() {
            override val parameters: Property<OkhttpCronetInstrumentationParams> = paramsProperty
            override val instrumentationContext: InstrumentationContext
                get() = throw UnsupportedOperationException("not needed for createClassVisitor")
        }

        val classContext = object : com.android.build.api.instrumentation.ClassContext {
            override val currentClassData: ClassData = fakeClassData("okhttp3.internal.connection.ConnectInterceptor")
            override fun loadClassData(className: String): ClassData? = null
        }

        val visitor = factory.createClassVisitor(classContext, org.objectweb.asm.ClassWriter(0))
        assertTrue(visitor is ConnectInterceptorGuardVisitor)
    }
}

private fun factory(): ConnectInterceptorVisitorFactory = object : ConnectInterceptorVisitorFactory() {
    override val parameters: Property<OkhttpCronetInstrumentationParams>
        get() = throw UnsupportedOperationException("not needed for isInstrumentable")
    override val instrumentationContext: InstrumentationContext
        get() = throw UnsupportedOperationException("not needed for isInstrumentable")
}

private fun fakeParams(version: String? = null, invalidateToken: Long? = null): OkhttpCronetInstrumentationParams {
    val project = org.gradle.testfixtures.ProjectBuilder.builder().build()
    return object : OkhttpCronetInstrumentationParams {
        override val okhttpVersion: Property<String> = project.objects.property(String::class.java).apply {
            if (version != null) set(version)
        }
        override val invalidateToken: Property<Long> = project.objects.property(Long::class.javaObjectType).apply {
            if (invalidateToken != null) set(invalidateToken)
        }
    }
}

private fun fakeClassData(className: String): ClassData = object : ClassData {
    override val className: String = className
    override val classAnnotations: List<String> = emptyList()
    override val interfaces: List<String> = emptyList()
    override val superClasses: List<String> = emptyList()
}
