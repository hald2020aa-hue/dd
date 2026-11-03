package com.example.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlMangaParserTest {
    private val manifest = SourceDefinition(
        id = "test-source",
        name = "Test Source",
        baseUrl = "https://manga.example.org",
        searchPath = "/search?q={query}&page={page}",
        rules = SourceRules(
            searchItem = ".book",
            searchTitle = "h3",
            searchLink = "a",
            searchCover = "img",
            chapterItem = ".chapters a",
            chapterTitle = "",
            pageImage = ".reader img",
            pageImageAttribute = "data-src"
        )
    )

    @Test fun parsesRealisticSearchCardsAndDeduplicates() {
        val html = """
        <div class="book"><a href="/manga/a"><h3>مانجا ألف</h3><img data-src="/a.jpg"></a></div>
        <div class="book"><a href="/manga/a"><h3>Duplicate</h3></a></div>
        <div class="book"><a href="https://attacker.invalid/manga"><h3>Unsafe</h3></a></div>
        """.trimIndent()
        val list = HtmlMangaParser.parseSearch(manifest, html, manifest.baseUrl + "/search?q=x")
        assertEquals(1, list.size)
        assertEquals("مانجا ألف", list[0].title)
        assertEquals("https://manga.example.org/manga/a", list[0].url)
    }

    @Test fun parsesChaptersAndPageImagesInOrder() {
        val chapterHtml = """
          <div class="chapters"><a href="/c/2">الفصل الثاني</a><a href="/c/1">الفصل الأول</a></div>
        """.trimIndent()
        val chapters = HtmlMangaParser.parseChapters(manifest, chapterHtml, manifest.baseUrl + "/manga/a")
        assertEquals(2, chapters.size)
        assertEquals("الفصل الثاني", chapters.first().title)
        val pages = HtmlMangaParser.parsePages(manifest,
            """<div class="reader"><img data-src="/page1.jpg"><img data-src="https://cdn.example.org/p2.webp"></div>""",
            chapters.first().url
        )
        assertEquals(2, pages.size)
        assertEquals("https://cdn.example.org/p2.webp", pages[1].imageUrl)
    }

    @Test fun blocksPrivateAddressAndCrossDomainNavigation() {
        assertFalse(isSafePublicUrl("http://127.0.0.1/admin"))
        assertFalse(isSafePublicUrl("http://192.168.1.10/"))
        assertNull(resolveSourceUrl(manifest.baseUrl, "https://evil.invalid/path"))
        assertTrue(HtmlMangaParser.searchUrl(manifest, "one piece").contains("one+piece"))
    }
}
