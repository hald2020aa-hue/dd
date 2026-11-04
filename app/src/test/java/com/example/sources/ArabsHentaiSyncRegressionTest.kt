package com.example.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Regression tests for resumable manga/manhwa/doujinshi/comic indexing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ArabsHentaiSyncRegressionTest {
    @Test fun chapterPartialScansAppendWithoutDuplicatesAcrossReopen() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("manga_d_arabs_cache_v1.db")
        val chapter = "https://arabshentai.com/manga/example/chapter-1/"
        ArabsHentaiStore(context).use { store ->
            store.savePages(chapter, listOf(
                ArabsPage("https://cdn.example.org/p1.jpg", 0),
                ArabsPage("https://cdn.example.org/p2.jpg", 1),
            ))
            store.savePages(chapter, listOf(
                ArabsPage("https://cdn.example.org/p2.jpg", 1),
                ArabsPage("https://cdn.example.org/p3.jpg", 2),
            ))
            store.saveChapterPage(chapter, chapter, listOf(
                ArabsPage("https://cdn.example.org/p1.jpg", 0),
                ArabsPage("https://cdn.example.org/p2.jpg", 1),
                ArabsPage("https://cdn.example.org/p3.jpg", 2),
            ), null)
        }
        ArabsHentaiStore(context).use { reopened ->
            val pages = reopened.pages(chapter)
            assertEquals(listOf("p1.jpg", "p2.jpg", "p3.jpg"),
                pages.map { it.url.substringAfterLast('/') })
            assertEquals(listOf(0, 1, 2), pages.map { it.position })
            assertEquals(true, reopened.chapterCompleted(chapter))
        }
    }

    @Test fun fullRefreshRechecksNewChaptersButKeepsSavedData() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("manga_d_arabs_cache_v1.db")
        val section = ArabsSection.MANHWA
        val workUrl = "https://arabshentai.com/manga/series-example/"
        val chapterUrl = "$workUrl" + "chapter-1/"
        ArabsHentaiStore(context).use { store ->
            store.saveArchive(section, section.startUrl,
                listOf(ArabsWork(workUrl, "Test series", "")), null, 1)
            store.saveChapters(section, workUrl,
                listOf(ArabsChapter(chapterUrl, "الفصل الأول", 0)))
            assertEquals(1, store.progress(section).detailsCount)
            store.resetCheckpoint(section)
            assertEquals(0, store.progress(section).detailsCount)
            assertNotNull(store.nextUnscannedWork(section))
            assertEquals(1, store.chapters(workUrl).size)
        }
    }

    @Test fun bulkQueueSkipsCompletedChaptersAndKeepsPendingOnes() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("manga_d_arabs_cache_v1.db")
        val section = ArabsSection.COMIC
        val workUrl = "https://arabshentai.com/manga/comic-example/"
        val chapterOne = workUrl + "chapter-1/"
        val chapterTwo = workUrl + "chapter-2/"
        ArabsHentaiStore(context).use { store ->
            store.saveArchive(section, section.startUrl,
                listOf(ArabsWork(workUrl, "Example comic", "")), null, 1)
            store.saveChapters(section, workUrl, listOf(
                ArabsChapter(chapterOne, "Chapter 1", 0),
                ArabsChapter(chapterTwo, "Chapter 2", 1),
            ))
            assertEquals(chapterOne, store.nextUnscannedChapter(section)?.url)
            store.saveChapterPage(chapterOne, chapterOne,
                listOf(ArabsPage("https://cdn.example.org/p1.jpg", 0)), null)
            assertEquals(chapterTwo, store.nextUnscannedChapter(section)?.url)
            store.saveChapterPage(chapterTwo, chapterTwo,
                listOf(ArabsPage("https://cdn.example.org/p2.jpg", 0)), null)
            assertNull(store.nextUnscannedChapter(section))
            assertEquals(2, store.chapters(workUrl).size)
        }
    }

    @Test fun paginationPreservesSectionAndRejectsOtherFilters() {
        val paged = ArabsSection.DOUJINSHI.adaptedArchiveUrl(
            "https://arabshentai.com/manga/page/2/")
        assertNotNull(paged)
        assertNotNull(ArabsSection.DOUJINSHI.archiveUrl(paged!!))
        assertNull(ArabsSection.MANHWA.adaptedArchiveUrl(
            "https://arabshentai.com/manga/page/2/?type=comic"))
        assertNull(ArabsSection.MANGA.adaptedArchiveUrl(
            "https://arabshentai.com/manga/chapter-1"))
    }
}
