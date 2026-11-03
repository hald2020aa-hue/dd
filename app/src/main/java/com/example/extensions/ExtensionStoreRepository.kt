package com.example.extensions

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.TimeUnit

/** Downloads store metadata only; never executes fetched extension code. */
class ExtensionStoreRepository(private val context: Context) {
    companion object {
        const val DEFAULT_STORE = "https://github.com/keiyoushi/extensions/raw/repo/index.pb"
        private const val MAX_INDEX_BYTES = 16 * 1024 * 1024
        private const val MAX_REDIRECTS = 4
    }
    // Official Keiyoushi store signing identity, published in its repo.json.
    private val officialKeiyoushiSigningKey =
        "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2"
    private val prefs = context.getSharedPreferences("manga_d_extension_store_v1", Context.MODE_PRIVATE)
    private val cache = File(context.filesDir, "extension_store_index.cache")
    private val guardedDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            com.example.sources.PublicNetworkGuard.filterPublic(Dns.SYSTEM.lookup(hostname))
    }
    private val client = OkHttpClient.Builder()
        .dns(guardedDns)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    fun savedUrl(): String = prefs.getString("url", DEFAULT_STORE) ?: DEFAULT_STORE

    /** Last fetched index is read from disk, not giant SharedPreferences values. */
    suspend fun cached(): ExtensionStoreSnapshot? = withContext(Dispatchers.IO) {
        try {
            val bytes = cache.takeIf { it.isFile && it.length() in 1..MAX_INDEX_BYTES.toLong() }?.readBytes()
                ?: return@withContext null
            val format = prefs.getString("format", "json")
            val url = prefs.getString("index_url", savedUrl()) ?: savedUrl()
            if (format == "pb") ProtobufStoreReader.decode(bytes, url)
            else ExtensionStoreCodec.decodeJson(bytes.toString(Charsets.UTF_8), url)
        } catch (_: Exception) { null }
    }

    suspend fun load(rawUrl: String): ExtensionStoreSnapshot = withContext(Dispatchers.IO) {
        val entered = rawUrl.trim()
        require(ExtensionStoreCodec.isHttpsUrl(entered)) { "رابط المستودع يجب أن يكون HTTPS عامًا" }
        val actualUrl: String
        val bytes: ByteArray
        val candidate = URI(entered)
        when {
            candidate.path.endsWith("/repo.json", ignoreCase = true) -> {
                val root = JSONObject(fetchBounded(entered).toString(Charsets.UTF_8))
                val index = root.optString("index_v2").ifBlank { root.optString("index") }
                require(ExtensionStoreCodec.isHttpsUrl(index)) { "ملف repo.json لا يحتوي رابط index_v2 صالحًا" }
                actualUrl = index
                bytes = fetchBounded(index)
            }
            candidate.path.endsWith("/index.min.json", ignoreCase = true) -> {
                // Many modern stores keep this legacy path as an 'update your app' marker.
                actualUrl = entered.replace(Regex("index\\.min\\.json$", RegexOption.IGNORE_CASE), "index.json")
                bytes = fetchBounded(actualUrl)
            }
            else -> { actualUrl = entered; bytes = fetchBounded(actualUrl) }
        }
        var decodedFormat = if (actualUrl.endsWith(".pb", true)) "pb" else "json"
        var decodedBytes = bytes
        var parsed = try {
            if (decodedFormat == "pb") ProtobufStoreReader.decode(bytes, actualUrl)
            else ExtensionStoreCodec.decodeJson(bytes.toString(Charsets.UTF_8), actualUrl)
        } catch (error: Exception) {
            if (!actualUrl.endsWith("/index.pb", true)) throw error
            // Some stores also publish a readable companion JSON v2 index.
            val alternate = actualUrl.dropLast("index.pb".length) + "index.json"
            decodedBytes = fetchBounded(alternate)
            decodedFormat = "json"
            ExtensionStoreCodec.decodeJson(decodedBytes.toString(Charsets.UTF_8), alternate)
        }
        if (parsed.extensions.isEmpty()) throw IOException("لم يُعثر على ملحقات صالحة في المستودع")
        if (URI(entered).host.equals("github.com", true) &&
            URI(entered).path.startsWith("/keiyoushi/extensions/raw/repo/", true) &&
            parsed.signingKeySha256 != officialKeiyoushiSigningKey) {
            throw SecurityException("بصمة مستودع Keiyoushi لا تطابق البصمة الرسمية المسجلة")
        }
        val temp = File(cache.parentFile, "${cache.name}.part")
        temp.writeBytes(decodedBytes)
        if (!temp.renameTo(cache)) {
            temp.delete()
            throw IOException("تعذر حفظ فهرس الملحقات")
        }
        prefs.edit().putString("url", entered)
            .putString("index_url", parsed.indexUrl)
            .putString("format", decodedFormat).apply()
        parsed
    }

    /** Bounded HTTPS fetch with SSRF protection on every redirect and every DNS resolution. */
    internal fun fetchBounded(url: String, maxBytes: Int = MAX_INDEX_BYTES): ByteArray {
        var target = url
        repeat(MAX_REDIRECTS + 1) { attempt ->
            require(ExtensionStoreCodec.isHttpsUrl(target) &&
                com.example.sources.isSafePublicUrl(target)) { "رابط غير آمن أو غير صالح" }
            val request = Request.Builder().url(target).header("Accept", "application/json,application/octet-stream").build()
            client.newCall(request).execute().use { response ->
                if (response.code in 300..399) {
                    if (attempt == MAX_REDIRECTS) throw IOException("إعادة توجيه كثيرة")
                    val location = response.header("Location") ?: throw IOException("تحويل دون عنوان")
                    target = URI(target).resolve(location).toString()
                    return@use
                }
                if (!response.isSuccessful) throw IOException("فشل اتصال المستودع HTTP ${response.code}")
                val stream = response.body?.byteStream() ?: throw IOException("الاستجابة فارغة")
                val out = ByteArrayOutputStream()
                stream.use { input ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(buf)
                        if (count < 0) break
                        if (out.size() + count > maxBytes) throw IOException("المستودع يتجاوز حجم التحميل المسموح")
                        out.write(buf, 0, count)
                    }
                }
                return out.toByteArray()
            }
        }
        throw IOException("فشل قراءة المستودع")
    }
}
