package com.example.sources

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ArabsBrowseFilterTest {
    @Test fun sortAndStatusStayWithPaginationInAllFourSections() {
        ArabsSection.entries.forEach { section ->
            val filters = ArabsBrowseFilter("new-manga", "end")
            val start = filters.url(section)
            assertTrue(start.contains("orderby=new-manga"))
            assertTrue(start.contains("state=end"))
            if (section.type != null) assertTrue(start.contains("type=${section.type}"))
            val next = filters.nextPage(section, "https://arabshentai.com/manga/page/3/")
            assertNotNull(next)
            assertTrue(next!!.contains("state=end"))
        }
    }

    @Test fun genreUsesSourceSearchRouteAndRejectsUntrustedNavigation() {
        val filters = ArabsBrowseFilter(genre = "دراما", status = "on-going")
        val url = filters.url(ArabsSection.MANHWA)
        assertTrue(url.contains("genre%5B%5D="))
        assertTrue(url.contains("status%5B%5D=on-going"))
        assertTrue(url.contains("type=manhwa"))
        assertNotNull(filters.nextPage(ArabsSection.MANHWA, "https://arabshentai.com/page/2/"))
        assertNull(filters.nextPage(ArabsSection.MANHWA, "https://attacker.example/page/3/"))
        assertNull(filters.nextPage(ArabsSection.MANHWA,
            "https://arabshentai.com/manga/title/chapter-1/"))
    }
}
