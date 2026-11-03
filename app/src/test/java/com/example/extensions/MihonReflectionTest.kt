package com.example.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

/** A fake local extension, not a claim of compatibility with third-party KeiSource APKs. */
class MihonReflectionTest {
    class FakePage(val index: Int, val url: String, val imageUrl: String?)
    class FakeChapter(val name: String, val url: String)
    class FakeManga(val title: String, val url: String, val thumbnail_url: String?)
    class FakePageResult(val mangas: List<FakeManga>)
    class FakeUpdate(val chapters: List<FakeChapter>)
    class FakeSource {
        val name: String = "Test fixture"
        val id: Long = 12L
        suspend fun getPopularManga(page: Int) = FakePageResult(listOf(FakeManga("Hello", "/hello", null)))
        suspend fun getMangaUpdate(manga: FakeManga, previous: List<FakeChapter>, details: Boolean, chapters: Boolean): FakeUpdate =
            FakeUpdate(listOf(FakeChapter("Chapter 1", "/ch1")))
        suspend fun getPageList(chapter: FakeChapter) = listOf(FakePage(0, "", "https://example.org/page1.jpg"))
    }
    class FakeFactory { fun createSources(): List<FakeSource> = listOf(FakeSource()) }

    @Test fun factoryAndMetadata() {
        val sources = MihonReflection.instantiate(
            FakeFactory::class.java.classLoader, listOf(FakeFactory::class.java.name)
        )
        assertEquals(1, sources.size)
        assertEquals("Test fixture", MihonReflection.sourceName(sources.first()))
        assertEquals("12", MihonReflection.sourceId(sources.first()))
    }

    @Test fun coroutineCallsAndPageMapping() = runBlocking {
        val source = FakeSource()
        val list = MihonReflection.popular(source)
        assertEquals("Hello", list.single().title)
        val chapters = MihonReflection.chapters(source, list.single())
        assertEquals("Chapter 1", chapters.single().name)
        val pages = MihonReflection.pages(source, chapters.single())
        assertEquals("https://example.org/page1.jpg", pages.single().imageUrl)
    }

    class FakeSearchSource {
        suspend fun getSearchManga(
            page: Int, query: String,
            filters: eu.kanade.tachiyomi.source.model.FilterList
        ): FakePageResult {
            require(page == 1 && query == "hello" && filters.isEmpty())
            return FakePageResult(listOf(FakeManga("Search works", "/search-result", null)))
        }
    }

    @Test fun emptyMihonFilterListEnablesSearch() = runBlocking {
        val manga = MihonReflection.search(FakeSearchSource(), "hello")
        assertEquals("Search works", manga.single().title)
        assertEquals("/search-result", manga.single().url)
    }

    @Test fun unsupportedMethodReportsError() = runBlocking {
        val source = FakeSource()
        try {
            MihonReflection.search(source, "hello")
            throw AssertionError("Expected missing dependency or method")
        } catch (error: Throwable) {
            assertTrue(error is ClassNotFoundException || error is UnsupportedOperationException)
        }
    }
}
