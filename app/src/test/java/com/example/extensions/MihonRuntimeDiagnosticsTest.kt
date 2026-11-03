package com.example.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MihonRuntimeDiagnosticsTest {
    @Test fun androidDescriptorIsDecoded() {
        val err = NoClassDefFoundError("Failed resolution of: Leu/kanade/tachiyomi/source/online/HttpSource;")
        assertEquals("eu.kanade.tachiyomi.source.online.HttpSource", MihonRuntimeDiagnostics.missingClass(err))
        assertTrue(MihonRuntimeDiagnostics.explain(err).contains("Source API"))
    }

    @Test fun javaClassMissingIsDecoded() {
        val err = ClassNotFoundException("rx.Observable")
        assertEquals("rx.Observable", MihonRuntimeDiagnostics.missingClass(err))
        assertTrue(MihonRuntimeDiagnostics.explain(err).contains("RxJava"))
    }

    @Test fun androidClassLoaderDescriptionIsDecoded() {
        val err = ClassNotFoundException("Didn't find class \"uy.kohesive.injekt.Injekt\" on path: DexPathList[]")
        assertEquals("uy.kohesive.injekt.Injekt", MihonRuntimeDiagnostics.missingClass(err))
    }
}
