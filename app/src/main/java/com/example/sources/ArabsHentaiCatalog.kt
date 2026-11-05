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
        val path = uri.path.orEmpty().lowercase(Locale.ROOT)
        val validPath = path == "/manga" || path == "/manga/" ||
            Regex("^/manga/page/[0-9]+/?$").matches(path)
        if (!validPath) return null
        val urlType = uri.getQueryParameter("type")?.lowercase(Locale.ROOT)
        if (type == null && !urlType.isNullOrBlank()) return null
        if (type != null && urlType != null && urlType != type) return null
        val adapted = if (type != null && urlType == null) {
            uri.buildUpon().appendQueryParameter("type", type).build().toString()
        } else {
            uri.toString()
        }
        // Don't leak a manga listing into the other three filtered sections.
        return adapted.takeIf { archiveUrl(it) != null }
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

/** Filter parameters used by ArabsHentai extensions: orderby, state, genre[]. */
data class ArabsBrowseFilter(
    val order: String = "new_chapter",
    val status: String = "",
    val genre: String = "",
) {
    init {
        require(order in setOf("new_chapter", "new-manga", "alphabet"))
        require(status in setOf("", "on-going", "end", "canceled", "on-hold"))
        require(genre.length <= 80 && !genre.contains('\n'))
    }

    /** Genre filtering uses the site's search endpoint; normal listing uses /manga/. */
    fun url(section: ArabsSection): String {
        val term = genre.trim()
        val builder = android.net.Uri.parse(
            if (term.isEmpty()) "https://arabshentai.com/manga/" else "https://arabshentai.com/"
        ).buildUpon()
        if (term.isNotEmpty()) {
            builder.appendQueryParameter("s", "")
            builder.appendQueryParameter("genre[]", term)
        }
        section.type?.let { builder.appendQueryParameter("type", it) }
        builder.appendQueryParameter("orderby", order)
        if (status.isNotBlank()) builder.appendQueryParameter(
            if (term.isNotEmpty()) "status[]" else "state", status
        )
        return builder.build().toString()
    }

    /** Reapply every filter to pagination links that omit the selected parameters. */
    fun nextPage(section: ArabsSection, url: String): String? {
        val incoming = ArabsSection.safeUrl(url) ?: return null
        val path = incoming.path.orEmpty()
        val valid = if (genre.isBlank()) {
            Regex("^/manga/(?:page/[0-9]+/?)?$").matches(path)
        } else {
            path == "/" || Regex("^/page/[0-9]+/?$").matches(path)
        }
        if (!valid) return null
        val base = android.net.Uri.parse(this.url(section))
        val builder = incoming.buildUpon().clearQuery()
        base.queryParameterNames.forEach { key ->
            base.getQueryParameters(key).forEach { value ->
                builder.appendQueryParameter(key, value)
            }
        }
        for (param in listOf("page", "paged")) {
            incoming.getQueryParameter(param)?.toIntOrNull()?.takeIf { it in 2..1000 }
                ?.let { builder.appendQueryParameter(param, it.toString()) }
        }
        return builder.build().toString()
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
