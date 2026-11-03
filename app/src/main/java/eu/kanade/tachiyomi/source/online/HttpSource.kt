package eu.kanade.tachiyomi.source.online

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.*
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.injectLazy
import java.net.URI
import java.security.MessageDigest

/** Functional 1.6 host base for KeiSource; older parsing methods remain optional. */
abstract class HttpSource : CatalogueSource {
    protected val network: NetworkHelper by injectLazy()
    abstract val baseUrl: String
    open fun getHomeUrl(): String = baseUrl
    open val versionId: Int = 1
    override val id: Long by lazy { generateId(name, lang, versionId) }
    val headers: Headers by lazy { headersBuilder().build() }
    open val client: OkHttpClient get() = network.client
    protected fun generateId(name: String, lang: String, versionId: Int): Long {
        val key = "${name.lowercase()}/$lang/$versionId"
        val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        var result = 0L
        for (i in 0 until 8) result = result or ((bytes[i].toLong() and 255) shl (8 * (7 - i)))
        return result and Long.MAX_VALUE
    }
    protected open fun headersBuilder(): Headers.Builder = Headers.Builder().apply { add("User-Agent", network.defaultUserAgentProvider()) }
    override fun toString(): String = "$name (${lang.uppercase()})"

    @Deprecated("Use getPopularManga")
    override fun fetchPopularManga(page: Int): Observable<MangasPage> = Observable.defer {
        client.newCall(popularMangaRequest(page)).execute().use { Observable.just(popularMangaParse(it)) }
    }
    @Deprecated("Use getSearchManga")
    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = Observable.defer {
        client.newCall(searchMangaRequest(page, query, filters)).execute().use { Observable.just(searchMangaParse(it)) }
    }
    @Deprecated("Use getLatestUpdates")
    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = Observable.defer {
        client.newCall(latestUpdatesRequest(page)).execute().use { Observable.just(latestUpdatesParse(it)) }
    }
    protected open fun popularMangaRequest(page: Int): Request = throw UnsupportedOperationException()
    protected open fun popularMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()
    protected open fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = throw UnsupportedOperationException()
    protected open fun searchMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()
    protected open fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    protected open fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException()

    @Deprecated("Use getMangaUpdate")
    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = Observable.defer {
        client.newCall(mangaDetailsRequest(manga)).execute().use { Observable.just(mangaDetailsParse(it)) }
    }
    @Deprecated("Use getMangaUpdate")
    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.defer {
        client.newCall(chapterListRequest(manga)).execute().use { Observable.just(chapterListParse(it)) }
    }
    @Deprecated("Use getPageList")
    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable.defer {
        client.newCall(pageListRequest(chapter)).execute().use { Observable.just(pageListParse(it)) }
    }
    open fun mangaDetailsRequest(manga: SManga): Request = Request.Builder().url(getMangaUrl(manga)).headers(headers).build()
    protected open fun mangaDetailsParse(response: Response): SManga = throw UnsupportedOperationException()
    open fun chapterListRequest(manga: SManga): Request = Request.Builder().url(getMangaUrl(manga)).headers(headers).build()
    protected open fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException()
    open fun pageListRequest(chapter: SChapter): Request = Request.Builder().url(getChapterUrl(chapter)).headers(headers).build()
    protected open fun pageListParse(response: Response): List<Page> = throw UnsupportedOperationException()
    @Deprecated("Use getImageUrl")
    open fun fetchImageUrl(page: Page): Observable<String> = Observable.just(page.imageUrl ?: page.url)
    open suspend fun getImageUrl(page: Page): String = page.imageUrl ?: page.url
    open fun imageUrlRequest(page: Page): Request = Request.Builder().url(page.url).headers(headers).build()
    protected open fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()
    suspend fun getImage(page: Page, existingSize: Long = 0L): Response =
        client.newCall(imageRequest(page)).execute()
    protected open fun imageRequest(page: Page): Request =
        Request.Builder().url(page.imageUrl ?: page.url).headers(headers).build()
    fun SChapter.setUrlWithoutDomain(url: String) { this.url = getUrlWithoutDomain(url) }
    fun SManga.setUrlWithoutDomain(url: String) { this.url = getUrlWithoutDomain(url) }
    private fun getUrlWithoutDomain(orig: String): String {
        val uri = URI(orig.replace(" ", "%20"))
        val path = uri.rawPath.orEmpty().ifBlank { "/" }
        return path + (uri.rawQuery?.let { "?$it" } ?: "") + (uri.rawFragment?.let { "#$it" } ?: "")
    }
    open fun getMangaUrl(manga: SManga): String = baseUrl.trimEnd('/') + manga.url.let { if (it.startsWith('/')) it else "/$it" }
    open fun getChapterUrl(chapter: SChapter): String = baseUrl.trimEnd('/') + chapter.url.let { if (it.startsWith('/')) it else "/$it" }
    @Deprecated("Modify before saving")
    open fun prepareNewChapter(chapter: SChapter, manga: SManga) {}
}
