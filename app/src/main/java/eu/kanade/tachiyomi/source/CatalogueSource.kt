package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.*
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import rx.Observable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/** Compatibility for legacy Rx-based sources as well as native suspend API 1.6 sources. */
interface CatalogueSource: Source {
    override val lang: String
    @Suppress("DEPRECATION")
    override suspend fun getPopularManga(page: Int): MangasPage = fetchPopularManga(page).awaitMangaD()
    @Suppress("DEPRECATION")
    override suspend fun getLatestUpdates(page: Int): MangasPage = fetchLatestUpdates(page).awaitMangaD()
    @Suppress("DEPRECATION")
    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
        fetchSearchManga(page, query, filters).awaitMangaD()
    @Suppress("DEPRECATION")
    override suspend fun getMangaUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean): SMangaUpdate = supervisorScope {
        val details = if (fetchDetails) async { fetchMangaDetails(manga).awaitMangaD() } else null
        val chapterList = if (fetchChapters) async { fetchChapterList(manga).awaitMangaD() } else null
        SMangaUpdate(details?.await() ?: manga, chapterList?.await() ?: chapters)
    }
    @Suppress("DEPRECATION")
    override suspend fun getPageList(chapter: SChapter): List<Page> = fetchPageList(chapter).awaitMangaD()
    @Deprecated("Use getPopularManga")
    fun fetchPopularManga(page: Int): Observable<MangasPage> = throw UnsupportedOperationException()
    @Deprecated("Use getSearchManga")
    fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = throw UnsupportedOperationException()
    @Deprecated("Use getLatestUpdates")
    fun fetchLatestUpdates(page: Int): Observable<MangasPage> = throw UnsupportedOperationException()
}

internal suspend fun <T> Observable<T>.awaitMangaD(): T = suspendCoroutine { continuation ->
    var settled = false
    this.take(1).subscribe({ value ->
        if (!settled) { settled = true; continuation.resume(value) }
    }, { error ->
        if (!settled) { settled = true; continuation.resumeWithException(error) }
    }, {
        if (!settled) { settled = true; continuation.resumeWithException(NoSuchElementException("Observable returned no result")) }
    })
}
