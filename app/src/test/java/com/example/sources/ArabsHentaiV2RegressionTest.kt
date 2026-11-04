package com.example.sources

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ArabsHentaiV2RegressionTest {
    @Test fun lateImagesKeepDomOrderAndSubpagesKeepTheirOwnOrder() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("manga_d_arabs_cache_v1.db")
        val chapter = "https://arabshentai.com/manga/test/chapter-1/"
        val second = chapter + "?page=2"
        ArabsHentaiStore(context).use { store ->
            store.savePages(chapter, listOf(ArabsPage("https://cdn.example.org/b.jpg", 1)))
            store.savePages(chapter, listOf(
                ArabsPage("https://cdn.example.org/a.jpg", 0),
                ArabsPage("https://cdn.example.org/b.jpg", 1),
            ))
            store.savePages(chapter, listOf(ArabsPage("https://cdn.example.org/d.jpg", 1)), second)
            store.savePages(chapter, listOf(
                ArabsPage("https://cdn.example.org/c.jpg", 0),
                ArabsPage("https://cdn.example.org/d.jpg", 1),
            ), second)
        }
        ArabsHentaiStore(context).use { store ->
            assertEquals(listOf("a.jpg", "b.jpg", "c.jpg", "d.jpg"),
                store.pages(chapter).map { it.url.substringAfterLast('/') })
            assertEquals(listOf(0, 1, 2, 3), store.pages(chapter).map { it.position })
        }
    }

    @Test fun migrationPreservesOldCachedPages() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("manga_d_arabs_cache_v1.db")
        val db = context.openOrCreateDatabase("manga_d_arabs_cache_v1.db", Context.MODE_PRIVATE, null)
        db.execSQL("CREATE TABLE pages(chapter_url TEXT NOT NULL, url TEXT NOT NULL, " +
            "position INTEGER NOT NULL, PRIMARY KEY(chapter_url,url))")
        db.execSQL("INSERT INTO pages VALUES('https://arabshentai.com/c/', " +
            "'https://cdn.example.org/old.jpg', 0)")
        db.version = 1
        db.close()
        ArabsHentaiStore(context).use { store ->
            assertEquals("old.jpg", store.pages("https://arabshentai.com/c/").first().url.substringAfterLast('/'))
            store.savePages("https://arabshentai.com/c/", listOf(
                ArabsPage("https://cdn.example.org/new.jpg", 1),
            ))
            assertEquals(2, store.pages("https://arabshentai.com/c/").size)
        }
    }

    @Test fun typePaginationIsCategorySafe() {
        val good = ArabsSection.MANHWA.adaptedArchiveUrl(
            "https://arabshentai.com/manga/page/4/")
        assertNotNull(good)
        assertNotNull(ArabsSection.MANHWA.archiveUrl(good!!))
        assertNull(ArabsSection.COMIC.adaptedArchiveUrl(
            "https://arabshentai.com/manga/page/4/?type=manhwa"))
        assertNull(ArabsSection.MANGA.adaptedArchiveUrl(
            "https://arabshentai.com/manga/abc/chapter-1/"))
    }
}
