package eu.kanade.tachiyomi.source.qari

import android.content.Context
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The stock Mihon source and reader APIs, with a local, resumable browser-fed catalog. */
class QariMangaSource(context: Context, val section: QariSection) : HttpSource() {
    private val store = QariMangaStore(context)

    override val name: String = section.title
    override val lang = "ar"
    override val baseUrl = "https://arabshentai.com"
    override val supportsLatest = true
    override fun getHomeUrl(): String = section.archive()

    override suspend fun getPopularManga(page: Int): MangasPage = readPage(page)
    override suspend fun getLatestUpdates(page: Int): MangasPage = readPage(page)

    override suspend fun getSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage = readPage(page, query)

    private suspend fun readPage(page: Int, query: String = ""): MangasPage = withContext(Dispatchers.IO) {
        val offset = (page.coerceAtLeast(1) - 1) * 30
        val list = store.titles(section, offset, 31, query)
        MangasPage(
            list.take(30).map { item ->
                SManga.create().apply {
                    url = QariUrls.relative(item.url)
                    title = item.title
                    thumbnail_url = item.cover
                    description = item.description
                }
            },
            list.size > 30,
        )
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = withContext(Dispatchers.IO) {
        val fullUrl = QariUrls.absolute(manga.url)
        val details = if (fetchDetails) {
            manga.copy().apply {
                store.title(section, fullUrl)?.let {
                    title = it.title
                    thumbnail_url = it.cover ?: thumbnail_url
                    description = it.description
                    initialized = true
                }
            }
        } else {
            manga
        }
        val fresh = if (fetchChapters) {
            store.chapters(section, fullUrl).map { item ->
                SChapter.create().apply {
                    url = QariUrls.relative(item.url)
                    name = item.name
                    chapter_number = Regex("[0-9]+(?:\\.[0-9]+)?")
                        .find(item.name)?.value?.toFloatOrNull() ?: -1f
                }
            }
        } else {
            chapters
        }
        SMangaUpdate(details, fresh)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        store.pages(section, QariUrls.absolute(chapter.url))
            .mapIndexed { index, entry -> Page(index, imageUrl = entry.url) }
    }

    override fun getMangaUrl(manga: SManga): String = QariUrls.absolute(manga.url)
    override fun getChapterUrl(chapter: SChapter): String = QariUrls.absolute(chapter.url)

    override fun headersBuilder() = super.headersBuilder().apply {
        add("Referer", "$baseUrl/")
    }
}
