package eu.kanade.tachiyomi.network

import android.content.Context
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.File
import java.util.concurrent.TimeUnit

/** Minimal real HTTP client. Cloudflare challenge solving is not included. */
class NetworkHelper(private val context: Context) {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .cache(okhttp3.Cache(File(context.cacheDir, "mihon_source_network"), 5L * 1024 * 1024))
            .addInterceptor(UncaughtExceptionInterceptor())
            .addInterceptor(UserAgentInterceptor())
            .addInterceptor(CloudflareInterceptor())
            .build()
    }
    @Deprecated("The regular client handles requests")
    val cloudflareClient: OkHttpClient get() = client
    fun defaultUserAgentProvider(): String =
        "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
}

/** Name-compatible interceptors for KeiSource; no challenge bypass and no silent error swallowing. */
class UncaughtExceptionInterceptor: Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())
}
class UserAgentInterceptor: Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        return chain.proceed(if (req.header("User-Agent") == null)
            req.newBuilder().header("User-Agent", "Mozilla/5.0 (Android) MangaD/6.2").build() else req)
    }
}
class CloudflareInterceptor: Interceptor {
    // Deliberately no bypass. Browser challenge integration is a separate task.
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())
}
