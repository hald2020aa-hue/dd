package eu.kanade.tachiyomi.source.qari

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/** Small per-page cache for direct browsing. No full-catalog checkpoint is required. */
class QariLiveCache(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    "qari_live_page_cache.db",
    null,
    1,
) {
    data class Snapshot(val items: JSONArray, val hasNext: Boolean, val savedAt: Long)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS pages (" +
                "section TEXT NOT NULL, page INTEGER NOT NULL, payload TEXT NOT NULL," +
                "has_next INTEGER NOT NULL, saved_at INTEGER NOT NULL," +
                "PRIMARY KEY(section,page))",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Non-destructive migrations only; preserve existing cached pages.
    }

    @Synchronized
    fun get(section: QariSection, page: Int): Snapshot? {
        readableDatabase.query(
            "pages", arrayOf("payload", "has_next", "saved_at"),
            "section=? AND page=?", arrayOf(section.key, page.toString()),
            null, null, null, "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return try {
                Snapshot(JSONArray(cursor.getString(0)), cursor.getInt(1) != 0, cursor.getLong(2))
            } catch (_: Exception) {
                null
            }
        }
    }

    @Synchronized
    fun put(section: QariSection, page: Int, items: JSONArray, hasNext: Boolean) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO pages(section,page,payload,has_next,saved_at) VALUES(?,?,?,?,?)",
            arrayOf<Any?>(section.key, page, items.toString(), if (hasNext) 1 else 0, System.currentTimeMillis()),
        )
    }

    /** Save cards extracted after ordinary WebView verification, without pre-scanning the archive. */
    @Synchronized
    fun merge(section: QariSection, page: Int, items: JSONArray, hasNext: Boolean) {
        val existing = get(section, page)
        val byUrl = linkedMapOf<String, JSONObject>()
        for (array in listOf(existing?.items, items)) {
            if (array == null) continue
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val url = QariUrls.canonical(item.optString("url")) ?: continue
                if (item.optString("title").isBlank()) continue
                byUrl[url] = item
            }
        }
        val result = JSONArray()
        byUrl.values.forEach { result.put(it) }
        put(section, page, result, hasNext || (existing?.hasNext == true))
    }
}
