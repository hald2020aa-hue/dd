package com.example.extensions

import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the runtime dependencies needed by KeiSource before invoking browse/search. */
class OkHttpCompressionCompatTest {
    @Test fun compressionRuntimeClassesArePresent() {
        val loader = javaClass.classLoader!!
        val classes = listOf(
            "okhttp3.CompressionInterceptor",
            "okhttp3.Gzip",
            "okhttp3.Brotli",
            "okhttp3.Zstd",
            "okhttp3.brotli.Brotli",
            "okhttp3.zstd.Zstd",
        )
        classes.forEach { name ->
            Class.forName(name, false, loader)
        }
        val algorithm = Class.forName(
            "okhttp3.CompressionInterceptor\$DecompressionAlgorithm", false, loader
        )
        assertTrue(algorithm.isAssignableFrom(Class.forName("okhttp3.Brotli", false, loader)))
        assertTrue(algorithm.isAssignableFrom(Class.forName("okhttp3.Zstd", false, loader)))
    }
}
