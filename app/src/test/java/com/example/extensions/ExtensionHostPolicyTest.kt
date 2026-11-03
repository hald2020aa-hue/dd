package com.example.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionHostPolicyTest {
    private val sha = "a".repeat(64)
    @Test fun trustedExtensionIsVerifiedButNotExecutableYet() {
        val result = ExtensionHostPolicy.evaluate(true, true, listOf("demo.Source"), "1.6", setOf(sha), sha)
        assertEquals(ExtensionHostPolicy.State.VERIFIED, result.state)
        assertTrue(result.canInspect)
        assertFalse(result.canExecute)
    }
    @Test fun rejectSignatureMismatch() {
        val result = ExtensionHostPolicy.evaluate(true, true, listOf("demo.Source"), "1.6", setOf("b".repeat(64)), sha)
        assertEquals(ExtensionHostPolicy.State.SIGNATURE_MISMATCH, result.state)
    }
    @Test fun unsupportedLibraryRejected() {
        val result = ExtensionHostPolicy.evaluate(true, true, listOf("demo.Source"), "2.0", setOf(sha), sha)
        assertEquals(ExtensionHostPolicy.State.UNSUPPORTED_LIB, result.state)
    }
    @Test fun unlistedPackageRejected() {
        val result = ExtensionHostPolicy.evaluate(false, true, listOf("demo.Source"), "1.6", setOf(sha), sha)
        assertEquals(ExtensionHostPolicy.State.UNLISTED, result.state)
    }
    @Test fun relativeNamesExpandedSafely() {
        assertEquals(listOf("test.extension.Source", "other.Factory"),
            ExtensionHostPolicy.absoluteSourceClasses("test.extension", ".Source;other.Factory;bad/entry"))
    }
    @Test fun parseLegacyAndModernLibraryVersions() {
        assertEquals("1.6", ExtensionHostPolicy.normalizedLibVersion(1.6f, "1.2.2"))
        assertEquals("1.4", ExtensionHostPolicy.normalizedLibVersion(null, "1.4.10"))
    }
}
