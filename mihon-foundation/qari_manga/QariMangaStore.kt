package eu.kanade.tachiyomi.source.qari

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Four independent shelves; not four filters of one ambiguous catalog. */
enum class QariSection(val key: String, val title: String, val siteType: String?) {
    MANGA("manga", "ArabsHentai • مانجا", null),
    MANHWA("manhwa", "ArabsHentai • مانهوا", "manhwa"),
    DOUJINSHI("doujinshi", "ArabsHentai • دوجينشي", "doujinshi"),
    COMIC("comic", "ArabsHentai • كوميكس", "comic"),
    ;

    fun archive(): String = if (siteType == null) {
        "https://arabshentai.com/manga/"
    } else {
        "https://arabshentai.com/manga/?type=$siteType&orderby=&state="
    }

    companion object {
        fun from(key: String?): QariSection = entries.firstOrNull { it.key == key } ?: MANGA
    }
}

object QariUrls {
    private const val HOST = "arabshentai.com"

    fun isSite(url: String): Boolean = try {
        val uri = Uri.parse(url)
        uri.scheme == "https" && uri.host?.lowercase()?.removePrefix("www.") == HOST
    } catch (_: Exception) {
        false
    }

    fun canonical(url: String): String? {
        if (!isSite(url)) return null
        return Uri.parse(url).buildUpon().authority(HOST).fragment(null).build().toString().trimEnd('/')
    }

    fun relative(url: String): String {
        val uri = Uri.parse(url)
        return uri.encodedPath.orEmpty() + (uri.encodedQuery?.let { "?$it" } ?: "")
    }

    fun absolute(path: String): String = if (path.startsWith("https://")) path else "https://$HOST$path"

    fun archivePage(url: String, section: QariSection): String? {
        if (!isSite(url)) return null
        val uri = Uri.parse(url)
        val path = uri.path.orEmpty().trimEnd('/').lowercase()
        if (path != "/manga" && !Regex("^/manga/page/[0-9]+$").matches(path)) return null
        val type = uri.getQueryParameter("type")?.lowercase()?.takeIf { it.isNotBlank() }
        if (type != null && type != section.siteType) return null
        if (section.siteType == null && type != null) return null
        return if (section.siteType != null && type == null) {
            uri.buildUpon().appendQueryParameter("type", section.siteType).build().toString()
        } else {
            uri.toString()
        }
    }
}

/** Independently versioned cache, separate from the official Mihon library database. */
class QariMangaStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    "qari_manga_catalog.db",
    null,
    1,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS titles (" +
                "section TEXT NOT NULL, url TEXT NOT NULL, title TEXT NOT NULL, cover TEXT, " +
                "description TEXT, scanned INTEGER NOT NULL DEFAULT 0, " +
                "PRIMARY KEY(section,url))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS chapters (" +
                "section TEXT NOT NULL, title_url TEXT NOT NULL, url TEXT NOT NULL, " +
                "name TEXT NOT NULL, rank INTEGER NOT NULL, scanned INTEGER NOT NULL DEFAULT 0, " +
                "PRIMARY KEY(section,url))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS images (" +
                "section TEXT NOT NULL, chapter_url TEXT NOT NULL, html_page INTEGER NOT NULL, " +
                "position INTEGER NOT NULL, url TEXT NOT NULL, PRIMARY KEY(section,chapter_url,url))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS progress (" +
                "section TEXT PRIMARY KEY, phase TEXT NOT NULL, next_url TEXT NOT NULL, " +
                "visited TEXT NOT NULL DEFAULT '[]', total INTEGER NOT NULL DEFAULT 0)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS chapter_title ON chapters(section,title_url,rank)")
        db.execSQL("CREATE INDEX IF NOT EXISTS image_order ON images(section,chapter_url,html_page,position)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future migrations must add columns/tables. NEVER drop a user's catalog.
        require(newVersion >= oldVersion)
    }

    data class Title(val url: String, val title: String, val cover: String?, val description: String?)
    data class Chapter(val url: String, val name: String, val rank: Int)
    data class Image(val htmlPage: Int, val position: Int, val url: String)
    data class Progress(val phase: String, val nextUrl: String, val visited: Set<String>, val total: Int)

    @Synchronized
    fun titles(section: QariSection, offset: Int, limit: Int, query: String = ""): List<Title> {
        val result = mutableListOf<Title>()
        val where = if (query.isBlank()) "section=?" else "section=? AND title LIKE ?"
        val args = if (query.isBlank()) arrayOf(section.key) else arrayOf(section.key, "%$query%")
        readableDatabase.query(
            "titles", arrayOf("url", "title", "cover", "description"), where, args,
            null, null, "rowid ASC", "$limit OFFSET $offset",
        ).use { c ->
            while (c.moveToNext()) result += Title(c.getString(0), c.getString(1), c.getString(2), c.getString(3))
        }
        return result
    }

    @Synchronized
    fun title(section: QariSection, url: String): Title? {
        val canonical = QariUrls.canonical(url) ?: return null
        return titlesByUrl(section, canonical)
    }

    private fun titlesByUrl(section: QariSection, url: String): Title? {
        readableDatabase.query(
            "titles", arrayOf("url", "title", "cover", "description"), "section=? AND url=?",
            arrayOf(section.key, url), null, null, null, "1",
        ).use { c ->
            return if (c.moveToFirst()) Title(c.getString(0), c.getString(1), c.getString(2), c.getString(3)) else null
        }
    }

    @Synchronized
    fun chapters(section: QariSection, titleUrl: String): List<Chapter> {
        val result = mutableListOf<Chapter>()
        readableDatabase.query(
            "chapters", arrayOf("url", "name", "rank"), "section=? AND title_url=?",
            arrayOf(section.key, QariUrls.canonical(titleUrl) ?: return emptyList()),
            null, null, "rank ASC, rowid ASC",
        ).use { c ->
            while (c.moveToNext()) result += Chapter(c.getString(0), c.getString(1), c.getInt(2))
        }
        return result
    }

    @Synchronized
    fun pages(section: QariSection, chapterUrl: String): List<Image> {
        val result = mutableListOf<Image>()
        readableDatabase.query(
            "images", arrayOf("html_page", "position", "url"), "section=? AND chapter_url=?",
            arrayOf(section.key, QariUrls.canonical(chapterUrl) ?: return emptyList()),
            null, null, "html_page ASC, position ASC, rowid ASC",
        ).use { c ->
            while (c.moveToNext()) result += Image(c.getInt(0), c.getInt(1), c.getString(2))
        }
        return result
    }

    @Synchronized
    fun progress(section: QariSection): Progress {
        readableDatabase.query(
            "progress", arrayOf("phase", "next_url", "visited", "total"),
            "section=?", arrayOf(section.key), null, null, null,
        ).use { c ->
            if (c.moveToFirst()) {
                val visited = mutableSetOf<String>()
                val json = try { JSONArray(c.getString(2)) } catch (_: Exception) { JSONArray() }
                for (i in 0 until json.length()) visited += json.optString(i)
                return Progress(c.getString(0), c.getString(1), visited, c.getInt(3))
            }
        }
        return Progress("archive", section.archive(), emptySet(), 0)
    }

    @Synchronized
    fun saveProgress(section: QariSection, value: Progress) {
        val visited = JSONArray().apply { value.visited.forEach { put(it) } }
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO progress(section,phase,next_url,visited,total) VALUES(?,?,?,?,?)",
            arrayOf<Any?>(section.key, value.phase, value.nextUrl, visited.toString(), value.total),
        )
    }

    @Synchronized
    fun importListing(section: QariSection, entries: JSONArray, page: String, next: String?, total: Int): Int {
        val current = QariUrls.archivePage(page, section) ?: return 0
        val db = writableDatabase
        var imported = 0
        db.beginTransaction()
        try {
            for (i in 0 until minOf(120, entries.length())) {
                val item = entries.optJSONObject(i) ?: continue
                val url = QariUrls.canonical(item.optString("url")) ?: continue
                val title = item.optString("title").trim().take(250)
                if (title.length < 2 || "/manga/" == Uri.parse(url).path) continue
                val cover = item.optString("cover").takeIf { it.startsWith("https://") }
                db.execSQL(
                    "INSERT OR IGNORE INTO titles(section,url,title,cover) VALUES(?,?,?,?)",
                    arrayOf(section.key, url, title, cover),
                )
                db.execSQL(
                    "UPDATE titles SET title=?, cover=COALESCE(?,cover) WHERE section=? AND url=?",
                    arrayOf(title, cover, section.key, url),
                )
                imported++
            }
            val state = progress(section)
            val visited = state.visited + (QariUrls.canonical(current) ?: current)
            val nextArchive = next?.let { QariUrls.archivePage(it, section) }
                ?.let { QariUrls.canonical(it) }
                ?.takeUnless { it in visited }
            val phase = if (nextArchive == null && total <= visited.size) "details" else "archive"
            val checkpoint = nextArchive ?: current
            val stored = JSONArray().apply { visited.forEach { put(it) } }
            db.execSQL(
                "INSERT OR REPLACE INTO progress(section,phase,next_url,visited,total) VALUES(?,?,?,?,?)",
                arrayOf<Any?>(section.key, phase, checkpoint, stored.toString(), maxOf(state.total, total)),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return imported
    }

    @Synchronized
    fun importDetails(section: QariSection, titleUrl: String, description: String, links: JSONArray): Int {
        val url = QariUrls.canonical(titleUrl) ?: return 0
        val db = writableDatabase
        var count = 0
        db.beginTransaction()
        try {
            val known = titlesByUrl(section, url)
            if (known != null) {
                db.execSQL(
                    "UPDATE titles SET description=?,scanned=1 WHERE section=? AND url=?",
                    arrayOf(description.take(4000).ifEmpty { known.description }, section.key, url),
                )
                for (i in 0 until minOf(500, links.length())) {
                    val obj = links.optJSONObject(i) ?: continue
                    val chUrl = QariUrls.canonical(obj.optString("url")) ?: continue
                    val name = obj.optString("title").take(200).ifBlank { "الفصل ${i + 1}" }
                    db.execSQL(
                        "INSERT OR IGNORE INTO chapters(section,title_url,url,name,rank) VALUES(?,?,?,?,?)",
                        arrayOf<Any?>(section.key, url, chUrl, name, i),
                    )
                    db.execSQL(
                        "UPDATE chapters SET name=?,rank=? WHERE section=? AND url=?",
                        arrayOf<Any?>(name, i, section.key, chUrl),
                    )
                    count++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return count
    }

    /** Images are upserted by their stable URL; a late image restores its DOM position. */
    @Synchronized
    fun importImages(section: QariSection, chapterUrl: String, htmlPage: Int, items: JSONArray): Int {
        val chapter = QariUrls.canonical(chapterUrl) ?: return 0
        val db = writableDatabase
        var count = 0
        db.beginTransaction()
        try {
            for (i in 0 until minOf(2000, items.length())) {
                val obj = items.optJSONObject(i) ?: continue
                val url = obj.optString("url")
                if (!url.startsWith("https://") || url.length > 2200) continue
                val position = obj.optInt("position", i).coerceIn(0, 2000)
                db.execSQL(
                    "INSERT OR IGNORE INTO images(section,chapter_url,html_page,position,url) VALUES(?,?,?,?,?)",
                    arrayOf(section.key, chapter, htmlPage, position, url),
                )
                // First HTML page wins; a delayed image may move earlier in DOM order.
                db.execSQL(
                    "UPDATE images SET html_page=?,position=? " +
                        "WHERE section=? AND chapter_url=? AND url=? " +
                        "AND (html_page>? OR (html_page=? AND position>?))",
                    arrayOf(htmlPage, position, section.key, chapter, url, htmlPage, htmlPage, position),
                )
                count++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return count
    }

    /** Live browsing writes only the cards requested, without changing old sync checkpoints. */
    @Synchronized
    fun upsertLiveTitles(section: QariSection, cards: JSONArray) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (i in 0 until minOf(cards.length(), 250)) {
                val obj = cards.optJSONObject(i) ?: continue
                val url = QariUrls.canonical(obj.optString("url")) ?: continue
                val title = obj.optString("title").trim().take(250)
                if (title.length < 2) continue
                val cover = obj.optString("cover").takeIf { it.startsWith("https://") }
                db.execSQL(
                    "INSERT OR IGNORE INTO titles(section,url,title,cover) VALUES(?,?,?,?)",
                    arrayOf<Any?>(section.key, url, title, cover),
                )
                db.execSQL(
                    "UPDATE titles SET title=?,cover=COALESCE(?,cover) WHERE section=? AND url=?",
                    arrayOf<Any?>(title, cover, section.key, url),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun nextUnscannedTitle(section: QariSection): String? = firstUnscanned(
        "titles", "url", "section=? AND scanned=0", section.key,
    )

    @Synchronized
    fun nextUnscannedChapter(section: QariSection): String? = firstUnscanned(
        "chapters", "url", "section=? AND scanned=0", section.key,
    )

    private fun firstUnscanned(table: String, col: String, where: String, section: String): String? {
        readableDatabase.query(table, arrayOf(col), where, arrayOf(section), null, null, "rowid ASC", "1").use { c ->
            return if (c.moveToFirst()) c.getString(0) else null
        }
    }

    @Synchronized
    fun markChapterScanned(section: QariSection, chapterUrl: String) {
        val canonical = QariUrls.canonical(chapterUrl) ?: return
        writableDatabase.execSQL(
            "UPDATE chapters SET scanned=1 WHERE section=? AND url=?",
            arrayOf(section.key, canonical),
        )
    }
}
