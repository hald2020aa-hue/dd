package com.example.sources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Dns
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * HTTP and HTML source engine. Does not bypass Cloudflare, copyright restrictions or logins.
 * Only explicitly installed, trusted source definitions are queried.
 */
class SourceManager(private val catalog: SourceCatalogStore) {
    private val guardedDns = object : Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> {
            // Imported rules may be untrusted; never connect to local/private IP addresses.
            val resolved = Dns.SYSTEM.lookup(hostname)
            if (resolved.isEmpty() || resolved.any { it.isAnyLocalAddress || it.isLoopbackAddress ||
                it.isLinkLocalAddress || it.isSiteLocalAddress || it.isMulticastAddress }) {
                throw IOException("Source DNS resolved to an unsafe network address")
            }
            resolved
        }
    }

    private val client = OkHttpClient.Builder()
        .dns(guardedDns)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    fun availableSources(): List<SourceDefinition> = catalog.list()

    fun addManifest(json: String): SourceDefinition {
        val source = SourceManifestCodec.decode(json)
        catalog.save(source)
        return source
    }

    /** Install a declarative HTTPS JSON catalog (NOT executable APK/JS extensions). */
    suspend fun installCatalog(url: String): Int = withContext(Dispatchers.IO) {
        require(isSafePublicUrl(url) && java.net.URI(url).scheme.equals("https", true)) {
            "Catalog address must be a public HTTPS URL"
        }
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        val bodyText = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Catalog HTTP error ${response.code}")
            val contentType = response.header("Content-Type").orEmpty().lowercase()
            if (contentType.isNotEmpty() && !contentType.contains("json")) {
                throw IOException("Catalog must return JSON")
            }
            val responseBody = response.body ?: throw IOException("Catalog response is empty")
            val output = ByteArrayOutputStream()
            responseBody.byteStream().use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 256 * 1024) throw IOException("Catalog larger than 256 KiB")
                    output.write(buffer, 0, count)
                }
            }
            output.toString("UTF-8")
        }
        val parsed = SourceManifestCodec.decodeList(bodyText)
        catalog.saveAll(parsed)
        parsed.size
    }

    fun deleteSource(id: String) = catalog.remove(id)

    private fun requireInstalled(id: String): SourceDefinition =
        catalog.list().firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("This manga source is not installed")

    private fun safeDestination(source: SourceDefinition, uri: String): String {
        val normalized = resolveSourceUrl(source.baseUrl + "/", uri)
            ?: throw SecurityException("URL is outside the selected manga source")
        return normalized
    }

    private fun getHtml(source: SourceDefinition, url: String): String {
        val safeUrl = safeDestination(source, url)
        val request = Request.Builder().url(safeUrl)
            .header("User-Agent", "MangaD/0.1 (Android; HTML reader)")
            .header("Accept", "text/html,application/xhtml+xml")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Source HTTP error ${response.code}")
            val ct = response.header("Content-Type", "")?.lowercase().orEmpty()
            if (ct.isNotEmpty() && !ct.contains("text/html") && !ct.contains("application/xhtml+xml")) {
                throw IOException("Source returned non-HTML content")
            }
            val body = response.body ?: throw IOException("Empty source response")
            val limit = 4 * 1024 * 1024
            val output = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (output.size() + n > limit) throw IOException("HTML response exceeds 4 MiB")
                    output.write(buffer, 0, n)
                }
            }
            output.toString(body.contentType()?.charset(StandardCharsets.UTF_8)?.name() ?: "UTF-8")
        }
    }

    suspend fun search(id: String, query: String, page: Int = 1): List<MangaResult> = withContext(Dispatchers.IO) {
        val source = requireInstalled(id)
        val url = HtmlMangaParser.searchUrl(source, query, page)
        HtmlMangaParser.parseSearch(source, getHtml(source, url), url)
    }

    suspend fun details(id: String, mangaUrl: String): MangaDetails = withContext(Dispatchers.IO) {
        val source = requireInstalled(id)
        val url = safeDestination(source, mangaUrl)
        HtmlMangaParser.parseDetails(source, getHtml(source, url), url)
    }

    suspend fun chapters(id: String, mangaUrl: String): List<ChapterResult> = withContext(Dispatchers.IO) {
        val source = requireInstalled(id)
        val url = safeDestination(source, mangaUrl)
        HtmlMangaParser.parseChapters(source, getHtml(source, url), url)
    }

    suspend fun pages(id: String, chapterUrl: String): List<PageResult> = withContext(Dispatchers.IO) {
        val source = requireInstalled(id)
        val url = safeDestination(source, chapterUrl)
        HtmlMangaParser.parsePages(source, getHtml(source, url), url)
    }
}
