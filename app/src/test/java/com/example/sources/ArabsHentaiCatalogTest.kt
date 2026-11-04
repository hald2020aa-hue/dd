package com.example.sources

import org.robolectric.RuntimeEnvironment
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ArabsHentaiCatalogTest {
    @Test fun validatesTheFourSectionsAndRejectsOtherHosts() {
        ArabsSection.entries.forEach { section ->
            assertNotNull(section.archiveUrl(section.startUrl))
            assertNull(section.archiveUrl("https://attacker.invalid/manga/"))
        }
        assertNull(ArabsSection.MANGA.archiveUrl(ArabsSection.MANHWA.startUrl))
        assertNull(ArabsSection.MANHWA.archiveUrl(ArabsSection.COMIC.startUrl))
        assertNotNull(ArabsSection.DOUJINSHI.archiveUrl(ArabsSection.DOUJINSHI.startUrl))
        val paged = ArabsSection.MANHWA.adaptedArchiveUrl("https://arabshentai.com/manga/page/2/")
        assertTrue(paged.orEmpty().contains("type=manhwa"))
        assertNull(ArabsSection.safeUrl("http://localhost/manga/"))
    }

    @Test fun keepsProgressAndChaptersAndImagesAcrossDatabaseReopen() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("manga_d_arabs_cache_v1.db")
        val section = ArabsSection.MANHWA
        val work = ArabsWork("https://arabshentai.com/manga/example-title/", "Example", "https://site.example/cover.jpg")
        ArabsHentaiStore(context).use { store ->
            val progress = store.saveArchive(
                section, section.startUrl, listOf(work),
                "https://arabshentai.com/manga/page/2/?type=manhwa", 42,
            )
            assertEquals(1, progress.pageCount)
            assertEquals(1, progress.totalWorks)
            assertFalse(progress.catalogDone)
            store.saveChapters(section, work.url, listOf(
                ArabsChapter("https://arabshentai.com/manga/example-title/chapter-1/", "Chapter 1", 0),
            ))
        }
        ArabsHentaiStore(context).use { reopened ->
            assertEquals(1, reopened.list(section).size)
            assertEquals(1, reopened.chapters(work.url).size)
            assertEquals(1, reopened.progress(section).pageCount)
            val chapterUrl = reopened.chapters(work.url).first().url
            reopened.savePages(chapterUrl, listOf(
                ArabsPage("https://cdn.example.com/page1.jpg", 0),
                ArabsPage("https://cdn.example.com/page1.jpg", 1),
                ArabsPage("https://cdn.example.com/page2.jpg", 2),
            ))
            assertEquals(2, reopened.pages(chapterUrl).size)
            reopened.resetCheckpoint(section)
            assertEquals(1, reopened.list(section).size)
            assertEquals(1, reopened.chapters(work.url).size)
            assertEquals(0, reopened.progress(section).pageCount)
        }
    }
}
