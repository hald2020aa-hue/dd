package eu.kanade.tachiyomi.source.qari

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URI

/** Parses actual HTML returned from the site's public pages, not challenge responses. */
object QariLiveParser {
    data class Card(val url: String, val title: String, val cover: String)
    data class Catalog(val cards: List<Card>, val hasNext: Boolean)
    data class Chapter(val url: String, val title: String)
    data class Image(val url: String, val position: Int)
    data class Images(val items: List<Image>, val nextHtml: String?)

    fun document(html: String, url: String): Document {
        val doc = Jsoup.parse(html, url)
        val challenge = doc.title().contains("Just a moment", true) ||
            doc.title().contains("Checking your browser", true) ||
            doc.selectFirst("#challenge-form, #cf-challenge-running, #challenge-stage") != null
        if (challenge) throw IOException("الموقع يطلب تحقق المتصفح. افتح زر التحقق ثم ارجع إلى المصدر.")
        return doc
    }

    private fun httpsLink(raw: String?, base: String): String? {
        if (raw.isNullOrBlank() || raw.startsWith("data:") || raw.startsWith("blob:")) return null
        return try {
            val uri = URI(base).resolve(raw.trim().replace(" ", "%20"))
            if (uri.scheme?.lowercase() != "https") null else uri.toString().substringBefore('#')
        } catch (_: Exception) {
            null
        }
    }

    private fun siteLink(raw: String?, base: String): String? {
        val url = httpsLink(raw, base) ?: return null
        return QariUrls.canonical(url)
    }

    private fun imageUrl(img: Element?, base: String): String? {
        if (img == null) return null
        val srcset = img.attr("data-srcset").ifBlank { img.attr("srcset") }
            .substringBefore(',').trim().substringBefore(' ')
        val raw = listOf(
            img.attr("data-src"), img.attr("data-lazy-src"), img.attr("data-original"),
            img.attr("data-url"), img.attr("data-cfsrc"), srcset, img.attr("src"),
        ).firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
        return httpsLink(raw, base)
    }

    private fun sectionMatches(url: String, section: QariSection): Boolean {
        val uri = try { URI(url) } catch (_: Exception) { return false }
        val path = uri.path.orEmpty().trimEnd('/')
        if (path != "/manga" && !Regex("^/manga/page/[0-9]+$").matches(path)) return false
        val type = Regex("(?:^|&)type=([^&]*)").find(uri.rawQuery.orEmpty())?.groupValues?.get(1)
        return type == null || type.isBlank() || type == section.siteType
    }

    /** Preserves source type when the site's pagination links omit query parameters. */
    fun archiveUrl(section: QariSection, page: Int): String {
        val root = if (page <= 1) "/manga/" else "/manga/page/$page/"
        return "https://arabshentai.com$root" +
            (section.siteType?.let { "?type=$it&orderby=&state=" } ?: "")
    }

    fun catalog(doc: Document, section: QariSection, requestedPage: Int): Catalog {
        val base = doc.baseUri()
        val cards = doc.select(
            ".page-item-detail, .c-tabs-item__content, .manga-card, .manga-item, " +
                ".post-on, .item-thumb, .manga-list .item, .main-col article, article",
        ).ifEmpty { doc.select("a[href]:has(img)") }
        val found = linkedMapOf<String, Card>()
        for (card in cards) {
            val anchor = if (card.`is`("a[href]")) card else card.selectFirst(
                "a[href][title], h3 a[href], h2 a[href], .post-title a[href], a[href]",
            ) ?: continue
            val target = siteLink(anchor.attr("href"), base) ?: continue
            val path = URI(target).path.orEmpty()
            if (path.trimEnd('/') == "/manga" || Regex("^/manga/page/[0-9]+/?$").matches(path)) continue
            if (path.contains("/category/") || path.contains("/tag/") || path.contains("/login")) continue
            val category = card.selectFirst(".genres, .manga-type, .type, .post-content_item")
                ?.text().orEmpty().lowercase()
            if (section.siteType == null && Regex("manhwa|manhua|doujinshi|comic|مانهوا|دوجينشي|كوميكس")
                    .containsMatchIn(category)) continue
            val title = (card.selectFirst("h3,h2,h4,.post-title,.manga-title,.title")?.text()
                ?: anchor.attr("title").ifBlank { card.selectFirst("img")?.attr("alt").orEmpty() }
                    .ifBlank { anchor.text() }).trim().take(250)
            val cover = imageUrl(card.selectFirst("img") ?: anchor.selectFirst("img"), base) ?: continue
            if (title.length < 2) continue
            found[target] = Card(target, title, cover)
            if (found.size >= 150) break
        }
        var next = false
        for (a in doc.select(".pagination a[href], .nav-links a[href], .wp-pagenavi a[href], a[href]")) {
            val href = siteLink(a.attr("href"), base) ?: continue
            if (!sectionMatches(href, section)) continue
            val pathPage = Regex("/page/(\\d+)").find(URI(href).path.orEmpty())
                ?.groupValues?.get(1)?.toIntOrNull()
            val queryPage = Regex("(?:^|&)(?:paged|page)=(\\d+)")
                .find(URI(href).rawQuery.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            val number = pathPage ?: queryPage ?: 1
            if (number > requestedPage && number <= 5000) { next = true; break }
        }
        return Catalog(found.values.toList(), next)
    }

    fun chapters(doc: Document): List<Chapter> {
        val result = linkedMapOf<String, Chapter>()
        val base = doc.baseUri()
        val selectors = ".wp-manga-chapter a[href],.listing-chapters_wrap a[href],.chapter-list a[href]," +
            ".chapters a[href],.main.version-chap a[href],a[href*='chapter-'],a[href*='/chapter/']"
        val primary = doc.select(selectors)
        val anchors = if (primary.isNotEmpty()) primary else doc.select("a[href]")
        for (a in anchors) {
            val url = siteLink(a.attr("href"), base) ?: continue
            if (url == QariUrls.canonical(base)) continue
            val title = a.text().trim().ifEmpty { a.attr("title") }.take(200)
            if (title.isBlank()) continue
            val candidate = "$title $url"
            if (primary.isEmpty() && !Regex("chapter|chap-|ch\\.|الفصل|فصل|part|episode", RegexOption.IGNORE_CASE)
                    .containsMatchIn(candidate)) continue
            result[url] = Chapter(url, title)
            if (result.size >= 2000) break
        }
        return result.values.toList()
    }

    fun images(doc: Document): Images {
        val base = doc.baseUri()
        val elements = doc.select(
            ".reading-content img, .page-break img, #readerarea img, .chapter-content img, " +
                ".reader img, .rdminimal img, .chapter-page img, #chapter_images img",
        ).ifEmpty { doc.select("img[data-src],img[data-lazy-src]") }
        val result = linkedMapOf<String, Image>()
        for ((index, img) in elements.withIndex()) {
            if (img.parents().any { it.`is`("header,footer,nav,.sidebar,.ads,.advertisement") }) continue
            val image = imageUrl(img, base) ?: continue
            if (Regex("logo|avatar|icon|banner|thumb|emoji|sprite|ads", RegexOption.IGNORE_CASE)
                    .containsMatchIn(URI(image).path.orEmpty())) continue
            result.putIfAbsent(image, Image(image, index))
            if (result.size >= 3000) break
        }
        var next: String? = null
        val current = URI(base)
        val currentPage = Regex("(?:^|&)(?:page|paged)=(\\d+)")
            .find(current.rawQuery.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: 1
        for (anchor in doc.select(".pagination a[href],.page-links a[href],a[rel=next][href],a.next-page[href]")) {
            val href = siteLink(anchor.attr("href"), base) ?: continue
            val candidate = URI(href)
            if (candidate.path.trimEnd('/') != current.path.trimEnd('/')) continue
            val number = Regex("(?:^|&)(?:page|paged)=(\\d+)")
                .find(candidate.rawQuery.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (number > currentPage && number < 3000) { next = href; break }
        }
        return Images(result.values.toList(), next)
    }
}
