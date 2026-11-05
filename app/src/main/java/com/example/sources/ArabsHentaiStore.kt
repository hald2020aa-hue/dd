package com.example.sources

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Independent store: never migrates/drops existing Manga D Room tables. */
class ArabsHentaiStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext, "manga_d_arabs_cache_v1.db", null, 2,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE works(
            section TEXT NOT NULL, url TEXT NOT NULL, title TEXT NOT NULL,
            cover TEXT NOT NULL DEFAULT '', updated_at INTEGER NOT NULL,
            details_done INTEGER NOT NULL DEFAULT 0,
            detail_attempts INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(section,url))""")
        db.execSQL("""CREATE TABLE chapters(
            work_url TEXT NOT NULL, url TEXT NOT NULL, title TEXT NOT NULL,
            position INTEGER NOT NULL, PRIMARY KEY(work_url,url))""")
        db.execSQL("""CREATE TABLE pages(
            chapter_url TEXT NOT NULL, url TEXT NOT NULL, position INTEGER NOT NULL,
            segment INTEGER NOT NULL DEFAULT 1,
            PRIMARY KEY(chapter_url,url))""")
        db.execSQL("""CREATE TABLE archive_progress(
            section TEXT PRIMARY KEY, next_url TEXT, page_count INTEGER NOT NULL DEFAULT 0,
            expected_pages INTEGER NOT NULL DEFAULT 0,
            done INTEGER NOT NULL DEFAULT 0,
            last_error TEXT NOT NULL DEFAULT '')""")
        db.execSQL("""CREATE TABLE visited_archive(
            section TEXT NOT NULL, url TEXT NOT NULL,
            PRIMARY KEY(section,url))""")
        db.execSQL("""CREATE TABLE chapter_progress(
            chapter_url TEXT PRIMARY KEY, next_url TEXT,
            done INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("""CREATE TABLE chapter_visited(
            chapter_url TEXT NOT NULL, page_url TEXT NOT NULL,
            PRIMARY KEY(chapter_url,page_url))""")
        db.execSQL("CREATE INDEX work_section_title ON works(section,title)")
        db.execSQL("CREATE INDEX chapters_work ON chapters(work_url,position)")
        db.execSQL("CREATE INDEX pages_chapter ON pages(chapter_url,position)")
        db.execSQL("CREATE INDEX pages_chapter_segments ON pages(chapter_url,segment,position)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion == 1 && newVersion == 2) {
            db.execSQL("ALTER TABLE pages ADD COLUMN segment INTEGER NOT NULL DEFAULT 1")
            db.execSQL("CREATE INDEX pages_chapter_segments ON pages(chapter_url,segment,position)")
            return
        }
        throw IllegalStateException("No safe migration for cache $oldVersion -> $newVersion")
    }

    fun list(section: ArabsSection, query: String = ""): List<ArabsWork> {
        val clauses = if (query.isBlank()) "section=?" else "section=? AND title LIKE ? ESCAPE '\\'"
        val args = if (query.isBlank()) arrayOf(section.name) else arrayOf(
            section.name, "%" + query.replace("\\", "\\\\").replace("%", "\\%")
                .replace("_", "\\_") + "%",
        )
        return readableDatabase.query("works", arrayOf("url", "title", "cover"),
            clauses, args, null, null, "updated_at DESC, title ASC").use { c ->
            buildList {
                while (c.moveToNext()) add(ArabsWork(c.getString(0), c.getString(1), c.getString(2)))
            }
        }
    }

    /** Filter previews are cached, but may never change unfiltered page checkpoints. */
    fun saveFilteredWorks(section: ArabsSection, cards: List<ArabsWork>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            cards.distinctBy { it.url }.forEach { work ->
                if (ArabsSection.safeUrl(work.url) == null || work.title.isBlank()) return@forEach
                db.insertWithOnConflict("works", null, ContentValues().apply {
                    put("section", section.name)
                    put("url", work.url)
                    put("title", work.title.take(250))
                    put("cover", work.cover.take(1500))
                    put("updated_at", System.currentTimeMillis())
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun chapters(workUrl: String): List<ArabsChapter> = readableDatabase.query(
        "chapters", arrayOf("url", "title", "position"), "work_url=?",
        arrayOf(workUrl), null, null, "position ASC",
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(ArabsChapter(c.getString(0), c.getString(1), c.getInt(2)))
        }
    }

    fun pages(chapterUrl: String): List<ArabsPage> = readableDatabase.query(
        "pages", arrayOf("url", "position"), "chapter_url=?",
        arrayOf(chapterUrl), null, null, "segment ASC,position ASC,url ASC",
    ).use { c ->
        buildList {
            var index = 0
            while (c.moveToNext()) add(ArabsPage(c.getString(0), index++))
        }
    }

    private fun storedNext(db: SQLiteDatabase, section: ArabsSection): String? = db.rawQuery(
        "SELECT next_url FROM archive_progress WHERE section=?", arrayOf(section.name),
    ).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun progress(section: ArabsSection): ArabsProgress {
        var count = 0
        var expected = 0
        var done = false
        var next: String? = null
        var error = ""
        readableDatabase.rawQuery(
            "SELECT next_url,page_count,expected_pages,done,last_error FROM archive_progress WHERE section=?",
            arrayOf(section.name),
        ).use { c -> if (c.moveToFirst()) {
            next = c.getString(0)
            count = c.getInt(1)
            expected = c.getInt(2)
            done = c.getInt(3) == 1
            error = c.getString(4)
        } }
        val works = readableDatabase.rawQuery(
            "SELECT COUNT(*),COALESCE(SUM(details_done),0) FROM works WHERE section=?",
            arrayOf(section.name),
        ).use { c -> if (c.moveToFirst()) Pair(c.getInt(0), c.getInt(1)) else Pair(0, 0) }
        return ArabsProgress(count, expected, done, next, works.first, works.second, error)
    }

    /** One transaction per archive page, checkpoint written only after card rows. */
    fun saveArchive(
        section: ArabsSection,
        currentUrl: String,
        cards: List<ArabsWork>,
        proposedNext: String?,
        expectedTotal: Int,
    ): ArabsProgress {
        require(section.archiveUrl(currentUrl) != null) { "Invalid archive URL" }
        require(cards.isNotEmpty()) { "Empty archives cannot advance the checkpoint" }
        val next = proposedNext?.let(section::adaptedArchiveUrl)
        val db = writableDatabase
        db.beginTransaction()
        try {
            cards.distinctBy { it.url }.forEach { item ->
                if (ArabsSection.safeUrl(item.url) == null || item.title.isBlank()) return@forEach
                val values = ContentValues().apply {
                    put("section", section.name)
                    put("url", item.url)
                    put("title", item.title.take(250))
                    put("cover", item.cover.take(1500))
                    put("updated_at", System.currentTimeMillis())
                }
                db.insertWithOnConflict("works", null, values, SQLiteDatabase.CONFLICT_IGNORE)
                // Metadata refreshed without wiping the 'details_done' flag.
                db.execSQL(
                    "UPDATE works SET title=?,cover=CASE WHEN ?='' THEN cover ELSE ? END WHERE section=? AND url=?",
                    arrayOf(item.title.take(250), item.cover, item.cover, section.name, item.url),
                )
            }
            val currentWasNew = db.insertWithOnConflict(
                "visited_archive", null,
                ContentValues().apply { put("section", section.name); put("url", currentUrl) },
                SQLiteDatabase.CONFLICT_IGNORE,
            ) != -1L
            val wasNextVisited = next != null && db.rawQuery(
                "SELECT 1 FROM visited_archive WHERE section=? AND url=?", arrayOf(section.name, next),
            ).use { it.moveToFirst() }
            val old = db.rawQuery(
                "SELECT page_count,expected_pages FROM archive_progress WHERE section=?",
                arrayOf(section.name),
            ).use { c -> if (c.moveToFirst()) Pair(c.getInt(0), c.getInt(1)) else Pair(0, 0) }
            val newCount = old.first + if (currentWasNew) 1 else 0
            val finalNext = if (wasNextVisited) null else next
            val done = finalNext == null && maxOf(expectedTotal, old.second) <= newCount
            db.insertWithOnConflict("archive_progress", null, ContentValues().apply {
                put("section", section.name)
                put("next_url", finalNext)
                put("page_count", newCount)
                put("expected_pages", maxOf(expectedTotal, old.second))
                put("done", if (done) 1.toInt() else 0.toInt())
                put("last_error", if (done || finalNext != null) "" else "لم يُعثر على رابط الصفحة التالية")
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return progress(section)
    }

    fun resetCheckpoint(section: ArabsSection) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("visited_archive", "section=?", arrayOf(section.name))
            db.delete("archive_progress", "section=?", arrayOf(section.name))
            // Refresh every existing title for NEW chapters, without removing cache.
            db.execSQL("UPDATE works SET detail_attempts=0,details_done=0 WHERE section=?",
                arrayOf(section.name))
            // IMPORTANT: do not delete the cached manga titles, chapters or pages.
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun setError(section: ArabsSection, reason: String) {
        writableDatabase.execSQL(
            "INSERT OR IGNORE INTO archive_progress(section) VALUES(?)", arrayOf(section.name),
        )
        writableDatabase.execSQL(
            "UPDATE archive_progress SET last_error=? WHERE section=?",
            arrayOf(reason.take(300), section.name),
        )
    }

    fun saveChapters(section: ArabsSection, workUrl: String, entries: List<ArabsChapter>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            entries.distinctBy { it.url }.forEach { entry ->
                if (ArabsSection.safeUrl(entry.url) == null) return@forEach
                db.insertWithOnConflict("chapters", null, ContentValues().apply {
                    put("work_url", workUrl); put("url", entry.url)
                    put("title", entry.title.take(220)); put("position", entry.position)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            if (entries.isNotEmpty()) db.execSQL(
                "UPDATE works SET details_done=1 WHERE section=? AND url=?",
                arrayOf(section.name, workUrl),
            )
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun nextUnscannedWork(section: ArabsSection): ArabsWork? {
        return readableDatabase.rawQuery(
            "SELECT url,title,cover FROM works WHERE section=? AND details_done=0 AND detail_attempts=0 " +
            "ORDER BY updated_at ASC LIMIT 1",
            arrayOf(section.name),
        ).use { c -> if (c.moveToFirst()) ArabsWork(c.getString(0), c.getString(1), c.getString(2)) else null }
    }

    fun nextUnscannedChapter(
        section: ArabsSection,
        excluded: Set<String> = emptySet(),
    ): ArabsChapter? {
        val sql = "SELECT c.url,c.title,c.position FROM chapters c " +
            "JOIN works w ON w.url=c.work_url WHERE w.section=? " +
            "AND NOT EXISTS (SELECT 1 FROM chapter_progress p " +
            "WHERE p.chapter_url=c.url AND p.done=1) " +
            "ORDER BY w.updated_at ASC,c.position ASC"
        return readableDatabase.rawQuery(sql, arrayOf(section.name)).use { c ->
            while (c.moveToNext()) {
                val url = c.getString(0)
                if (url !in excluded) return@use ArabsChapter(url, c.getString(1), c.getInt(2))
            }
            null
        }
    }

    fun chapterRemaining(chapterUrl: String): String? = readableDatabase.rawQuery(
        "SELECT next_url FROM chapter_progress WHERE chapter_url=? AND done=0",
        arrayOf(chapterUrl),
    ).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun chapterCompleted(chapterUrl: String): Boolean = readableDatabase.rawQuery(
        "SELECT done FROM chapter_progress WHERE chapter_url=?",
        arrayOf(chapterUrl),
    ).use { c -> c.moveToFirst() && c.getInt(0) == 1 }

    /** A chapter may be spread across ?page=1, ?page=2 etc. */
    private fun pageSegment(pageUrl: String): Int {
        val uri = android.net.Uri.parse(pageUrl)
        val raw = uri.getQueryParameter("page") ?: uri.getQueryParameter("paged")
        return raw?.toIntOrNull()?.coerceIn(1, 3000) ?: 1
    }

    /** Preserve position inside each HTML page while keeping the first origin
     * for duplicate image URLs. Late-loaded images may update their DOM slot.
     */
    private fun orderedChapterPages(
        db: SQLiteDatabase,
        chapterUrl: String,
        htmlPageUrl: String,
        images: List<ArabsPage>,
    ) {
        val segment = pageSegment(htmlPageUrl)
        val known = mutableMapOf<String, Pair<Int, Int>>()
        db.rawQuery(
            "SELECT url,segment,position FROM pages WHERE chapter_url=?",
            arrayOf(chapterUrl),
        ).use { c ->
            while (c.moveToNext()) known[c.getString(0)] = c.getInt(1) to c.getInt(2)
        }
        images.distinctBy { it.url }.forEach { image ->
            if (!image.url.startsWith("https://") || !isSafePublicUrl(image.url)) return@forEach
            val prior = known[image.url]
            if (prior == null) {
                db.insertWithOnConflict("pages", null, ContentValues().apply {
                    put("chapter_url", chapterUrl)
                    put("url", image.url)
                    put("position", image.position)
                    put("segment", segment)
                }, SQLiteDatabase.CONFLICT_IGNORE)
                known[image.url] = segment to image.position
            } else if (segment < prior.first ||
                (segment == prior.first && image.position != prior.second)
            ) {
                db.execSQL(
                    "UPDATE pages SET segment=?,position=? WHERE chapter_url=? AND url=?",
                    arrayOf(segment, image.position, chapterUrl, image.url),
                )
                known[image.url] = segment to image.position
            }
        }
    }

    /** Insert each page and persist the cursor atomically before navigation. */
    fun saveChapterPage(
        chapterUrl: String,
        pageUrl: String,
        images: List<ArabsPage>,
        candidateNext: String?,
    ): String? {
        require(ArabsSection.safeUrl(chapterUrl) != null)
        require(ArabsSection.safeUrl(pageUrl) != null)
        require(images.isNotEmpty())
        val current = android.net.Uri.parse(pageUrl)
        val next = candidateNext?.takeIf { proposed ->
            val nextUri = ArabsSection.safeUrl(proposed) ?: return@takeIf false
            nextUri.path.orEmpty().trimEnd('/') == current.path.orEmpty().trimEnd('/')
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            orderedChapterPages(db, chapterUrl, pageUrl, images)
            db.insertWithOnConflict("chapter_visited", null, ContentValues().apply {
                put("chapter_url", chapterUrl); put("page_url", pageUrl)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            val wasVisited = next != null && db.rawQuery(
                "SELECT 1 FROM chapter_visited WHERE chapter_url=? AND page_url=?",
                arrayOf(chapterUrl, next),
            ).use { it.moveToFirst() }
            val remaining = next.takeUnless { wasVisited }
            db.insertWithOnConflict("chapter_progress", null, ContentValues().apply {
                put("chapter_url", chapterUrl); put("next_url", remaining)
                put("done", if (remaining == null) 1.toInt() else 0.toInt())
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
            return remaining
        } finally { db.endTransaction() }
    }

    fun markDetailsFailed(section: ArabsSection, workUrl: String) {
        writableDatabase.execSQL(
            "UPDATE works SET detail_attempts=detail_attempts+1 WHERE section=? AND url=?",
            arrayOf(section.name, workUrl),
        )
    }

    fun savePages(
        chapterUrl: String,
        items: List<ArabsPage>,
        htmlPageUrl: String = chapterUrl,
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            orderedChapterPages(db, chapterUrl, htmlPageUrl, items)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
