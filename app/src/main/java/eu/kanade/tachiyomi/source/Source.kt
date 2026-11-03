package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.*
import rx.Observable

/** Host implementation matching the 1.6 Source interface, not a compile-only stub. */
interface Source {
    val id: Long
    val name: String
    val lang: String get() = ""
    val supportsLatest: Boolean
    fun getFilterList(): FilterList = FilterList()
    suspend fun getPopularManga(page: Int): MangasPage
    suspend fun getLatestUpdates(page: Int): MangasPage
    suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage
    suspend fun getMangaUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean): SMangaUpdate
    suspend fun getPageList(chapter: SChapter): List<Page>
    @Deprecated("Use getMangaUpdate")
    fun fetchMangaDetails(manga: SManga): Observable<SManga> = throw UnsupportedOperationException()
    @Deprecated("Use getMangaUpdate")
    fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = throw UnsupportedOperationException()
    @Deprecated("Use getPageList")
    fun fetchPageList(chapter: SChapter): Observable<List<Page>> = throw UnsupportedOperationException()
}
