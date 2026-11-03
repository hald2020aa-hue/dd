package com.example.extensions

import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.coroutines.Continuation

/** Checks the JVM methods that compiled Mihon extensions link to, not only Kotlin names. */
class MihonNetworkFacadeTest {
    @Test fun extensionLinkageHasNetworkMethods() {
        val fileFacade = Class.forName("eu.kanade.tachiyomi.network.OkHttpExtensionsKt")
        assertNotNull(fileFacade.getDeclaredMethod("await", Call::class.java, Continuation::class.java))
        assertNotNull(fileFacade.getDeclaredMethod("awaitSuccess", Call::class.java, Continuation::class.java))
        assertNotNull(fileFacade.getDeclaredMethod("asObservableSuccess", Call::class.java))
        val requests = Class.forName("eu.kanade.tachiyomi.network.RequestsKt")
        assertNotNull(requests.getDeclaredMethod("GET", String::class.java, okhttp3.Headers::class.java, okhttp3.CacheControl::class.java))
    }

    @Test fun failingHttpResponseIsReportedInsteadOfReturnedAsSuccess() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(403).message("Forbidden").build()
        }.build()
        val request = Request.Builder().url("https://example.org/test").build()
        try {
            client.newCall(request).awaitSuccess().close()
            throw AssertionError("Expected HttpException")
        } catch (e: HttpException) {
            assertEquals(403, e.code)
        }
    }
}
