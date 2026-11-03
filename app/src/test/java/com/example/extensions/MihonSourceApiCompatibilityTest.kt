package com.example.extensions

import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MihonSourceApiCompatibilityTest {
    private class DemoSource: Source {
        override val id = 17L
        override val name = "Test 1.6"
        override val lang = "all"
        override val supportsLatest = false
        override suspend fun getPopularManga(page: Int): MangasPage = MangasPage(listOf(SManga.create().apply { title = "Demo"; url = "/demo" }), false)
        override suspend fun getLatestUpdates(page: Int): MangasPage = MangasPage(emptyList(), false)
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = getPopularManga(page)
        override suspend fun getMangaUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean): SMangaUpdate = SMangaUpdate(manga, chapters)
        override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, imageUrl = "https://example.org/p.jpg"))
    }
    private class DemoFactory: SourceFactory {
        override fun createSources(): List<Source> = listOf(DemoSource())
    }
    @Test fun sourceApiIsInstantiableAndTyped() {
        val sources = DemoFactory().createSources()
        assertEquals(1, sources.size)
        assertEquals(17L, sources.single().id)
        assertEquals("all", sources.single().lang)
        assertTrue(SourceFactory::class.java.isAssignableFrom(DemoFactory::class.java))
        val manga = SManga.create().apply { url = "/demo"; title = "Demo" }
        assertEquals("Demo", manga.copy().title)
        val chapter = SChapter.create().apply { name = "Chapter"; url = "/chapter" }
        assertEquals("/chapter", chapter.url)
        assertEquals(0, FilterList().size)
    }
}
