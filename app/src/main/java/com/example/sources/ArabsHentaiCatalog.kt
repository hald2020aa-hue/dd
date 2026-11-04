package com.example.sources

import android.net.Uri
import java.util.Locale

/** Four independent catalog sections; all navigation remains on the original host. */
enum class ArabsSection(val title: String, val startUrl: String, val type: String?) {
    MANGA("مانجا", "https://arabshentai.com/manga/", null),
    MANHWA("مانهوا", "https://arabshentai.com/manga/?type=manhwa", "manhwa"),
    DOUJINSHI("دوجينشي", "https://arabshentai.com/manga/?type=doujinshi&orderby=&state=", "doujinshi"),
    COMIC("كوميكس", "https://arabshentai.com/manga/?type=comic&orderby=&state=", "comic");

    /** Reject navigation offsite and wrong-category listing links. */
    fun archiveUrl(url: String): String? {
        val uri = safeUrl(url) ?: return null
        val path = uri.path.orEmpty().lowercase(Locale.ROOT)
        if (!(path == "/manga" || path == "/manga/" || path.startsWith("/manga/page/"))) return null
        val actualType = uri.getQueryParameter("type")?.lowercase(Locale.ROOT)
        if (type == null && !actualType.isNullOrEmpty()) return null
        if (type != null && type != actualType) return null
        return uri.toString()
    }

    /** Some themes omit the type filter from pagination links: preserve it. */
    fun adaptedArchiveUrl(url: String): String? {
        val uri = safeUrl(url) ?: return null
        if (!uri.path.orEmpty().startsWith("/manga")) return null
        val urlType = uri.getQueryParameter("type")
        if (type == null && !urlType.isNullOrBlank()) return null
        if (type != null && urlType != null && urlType != type) return null
        return if (type != null && urlType == null) uri.buildUpon()
            .appendQueryParameter("type", type).build().toString() else uri.toString()
    }

    companion object {
        fun safeUrl(url: String): Uri? = try {
            val uri = Uri.parse(url.trim())
            val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.")
            if (uri.scheme != "https" || host != "arabshentai.com" || uri.userInfoCompat()) null else uri
        } catch (_: Exception) { null }

        private fun Uri.userInfoCompat(): Boolean = encodedAuthority.orEmpty().contains('@')
    }
}

data class ArabsWork(val url: String, val title: String, val cover: String)
data class ArabsChapter(val url: String, val title: String, val position: Int)
data class ArabsPage(val url: String, val position: Int)
data class ArabsProgress(
    val pageCount: Int = 0,
    val expectedPages: Int = 0,
    val catalogDone: Boolean = false,
    val nextUrl: String? = null,
    val totalWorks: Int = 0,
    val detailsCount: Int = 0,
    val lastError: String = "",
)
