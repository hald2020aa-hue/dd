package eu.kanade.tachiyomi.source.qari

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Real Mihon native source: fetch one catalog page on demand; never sync the whole site. */
class QariMangaSource(context: Context, val section: QariSection) : HttpSource() {
    private val store = QariMangaStore(context)
    private val pages = QariLiveCache(context)
    private val userAgent = WebSettings.getDefaultUserAgent(context.applicationContext)
    private val liveState = context.applicationContext.getSharedPreferences("qari_live_state", 0)

    fun pendingPage(): Int = liveState.getInt("blocked_${section.key}", 1).coerceAtLeast(1)
    private fun blocked(page: Int) {
        liveState.edit().putInt("blocked_${section.key}", page).apply()
    }

    override val name: String = section.title
    override val lang: String = "ar"
    override val baseUrl: String = "https://arabshentai.com"
    override val supportsLatest: Boolean = false // No verified latest-updates endpoint.
    override fun getHomeUrl(): String = section.archive()

    override fun headersBuilder() = super.headersBuilder().apply {
        set("User-Agent", userAgent)
        set("Referer", "$baseUrl/")
    }

    private fun fetch(url: String): org.jsoup.nodes.Document {
        if (!QariUrls.isSite(url)) throw IOException("رابط المصدر غير آمن")
        val builder = Request.Builder().url(url)
            .header("User-Agent", userAgent)
            .header("Referer", "$baseUrl/")
            .header("Accept", "text/html,application/xhtml+xml")
        // Normal, user-established WebView session. Does not forge/bypass a challenge.
        val cookie = try { CookieManager.getInstance().getCookie(url) } catch (_: Exception) { null }
        if (!cookie.isNullOrBlank()) builder.header("Cookie", cookie)
        client.newCall(builder.get().build()).execute().use { response ->
            if (response.code == 403 || response.code == 503) {
                throw IOException("الموقع يطلب تحقق المتصفح. افتح زر التحقق ثم ارجع للمصدر")
            }
            if (!response.isSuccessful) throw IOException("فشل اتصال الموقع: HTTP ${response.code}")
            val html = response.body.string()
            return QariLiveParser.document(html, response.request.url.toString())
        }
    }

    private fun toManga(item: JSONObject): SManga = SManga.create().apply {
        url = QariUrls.relative(item.getString("url"))
        title = item.optString("title")
        thumbnail_url = item.optString("cover").takeIf { it.startsWith("https://") }
        description = item.optString("description")
    }

    private fun catalogPage(array: JSONArray, hasNext: Boolean): MangasPage {
        val titles = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            .filter { QariUrls.isSite(it.optString("url")) }
            .map(::toManga)
        return MangasPage(titles, hasNext)
    }

    override suspend fun getPopularManga(page: Int): MangasPage = withContext(Dispatchers.IO) {
        val number = page.coerceIn(1, 5000)
        val cached = pages.get(section, number)
        // Immediate cache hit avoids reloading a page whenever a reader navigates back.
        if (cached != null && cached.items.length() > 0 &&
            System.currentTimeMillis() - cached.savedAt < 5 * 60 * 1000L) {
            return@withContext catalogPage(cached.items, cached.hasNext)
        }
        try {
            val doc = fetch(QariLiveParser.archiveUrl(section, number))
            val catalog = QariLiveParser.catalog(doc, section, number)
            if (catalog.cards.isEmpty()) throw IOException("الموقع لم يُظهر بطاقات. افتح زر التحقق")
            val array = JSONArray()
            for (item in catalog.cards) {
                array.put(JSONObject().put("url", item.url).put("title", item.title).put("cover", item.cover))
            }
            pages.put(section, number, array, catalog.hasNext)
            store.upsertLiveTitles(section, array)
            catalogPage(array, catalog.hasNext)
        } catch (e: Exception) {
            if (cached != null && cached.items.length() > 0) {
                catalogPage(cached.items, cached.hasNext)
            } else if (number == 1) {
                // Old saved content stays available if the site is temporarily unavailable.
                val prior = store.titles(section, 0, 31)
                if (prior.isNotEmpty()) {
                    val arr = JSONArray()
                    prior.take(30).forEach {
                        arr.put(JSONObject().put("url", it.url).put("title", it.title).put("cover", it.cover))
                    }
                    catalogPage(arr, prior.size > 30)
                } else {
                    blocked(number)
                    throw IOException("تعذر العرض المباشر. افتح التحقق من مصدر ${section.title}: ${e.message}", e)
                }
            } else {
                blocked(number)
                throw IOException("تعذر تحميل الصفحة $number، افتح التحقق من جديد: ${e.message}", e)
            }
        }
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    override suspend fun getSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage = if (query.isBlank()) getPopularManga(page) else withContext(Dispatchers.IO) {
        // Conservative local search; do not guess an unsupported site-wide search URL.
        val offset = (page.coerceAtLeast(1) - 1) * 30
        val matches = store.titles(section, offset, 31, query)
        MangasPage(
            matches.take(30).map {
                SManga.create().apply {
                    url = QariUrls.relative(it.url)
                    title = it.title
                    thumbnail_url = it.cover
                    description = it.description
                }
            },
            matches.size > 30,
        )
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = withContext(Dispatchers.IO) {
        val full = QariUrls.absolute(manga.url)
        if (fetchChapters || fetchDetails) {
            try {
                val document = fetch(full)
                val liveChapters = QariLiveParser.chapters(document)
                if (fetchChapters && liveChapters.isEmpty()) throw IOException("لم تظهر قائمة الفصول")
                val links = JSONArray()
                liveChapters.forEach { links.put(JSONObject().put("url", it.url).put("title", it.title)) }
                store.importDetails(section, full, document.selectFirst(
                    ".summary,.description,.entry-content p,.post-content p",
                )?.text().orEmpty(), links)
            } catch (e: Exception) {
                if (store.chapters(section, full).isEmpty()) {
                    throw IOException("تعذّر تحميل الفصول، افتح الموقع للتحقق: ${e.message}", e)
                }
            }
        }
        val details = if (fetchDetails) {
            manga.copy().apply {
                store.title(section, full)?.let { saved ->
                    title = saved.title
                    thumbnail_url = saved.cover ?: thumbnail_url
                    description = saved.description
                }
                initialized = true
            }
        } else manga
        val fresh = if (fetchChapters) {
            store.chapters(section, full).map { saved ->
                SChapter.create().apply {
                    url = QariUrls.relative(saved.url)
                    name = saved.name
                    chapter_number = Regex("[0-9]+(?:\\.[0-9]+)?")
                        .find(saved.name)?.value?.toFloatOrNull() ?: -1f
                }
            }
        } else chapters
        SMangaUpdate(details, fresh)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        val full = QariUrls.absolute(chapter.url)
        try {
            var current = full
            val visited = mutableSetOf<String>()
            var htmlPage = 0
            while (visited.add(current) && htmlPage < 200) {
                val parsed = QariLiveParser.images(fetch(current))
                if (parsed.items.isEmpty()) throw IOException("لم تظهر صور الفصل")
                val json = JSONArray()
                parsed.items.forEach {
                    json.put(JSONObject().put("url", it.url).put("position", it.position))
                }
                store.importImages(section, full, htmlPage, json)
                val next = parsed.nextHtml?.takeIf { QariUrls.isSite(it) }
                if (next == null) break
                current = next
                htmlPage++
            }
            store.markChapterScanned(section, full)
        } catch (e: Exception) {
            if (store.pages(section, full).isEmpty()) {
                throw IOException("تعذّر استخراج الصور، افتح الموقع في المتصفح: ${e.message}", e)
            }
        }
        store.pages(section, full)
            .mapIndexed { index, image -> Page(index, imageUrl = image.url) }
    }

    override fun getMangaUrl(manga: SManga): String = QariUrls.absolute(manga.url)
    override fun getChapterUrl(chapter: SChapter): String = QariUrls.absolute(chapter.url)
}
