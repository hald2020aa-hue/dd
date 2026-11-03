package com.example.sources

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/** Deterministic parsing; a source provides CSS selectors, not executable plug-in code. */
object HtmlMangaParser {
    private fun Element.selected(css: String): Element? = when {
        css.isBlank() || css == ":scope" -> this
        tagName().equals(css, ignoreCase = true) -> this
        else -> selectFirst(css)
    }

    private fun Element.linkFor(css: String): String {
        val candidate = selected(css) ?: return ""
        val a = when {
            candidate.hasAttr("href") -> candidate
            candidate.tagName().equals("a", ignoreCase = true) -> candidate
            else -> candidate.selectFirst("a[href]")
        }
        return a?.attr("href").orEmpty()
    }

    private fun Element.imageFor(css: String, preferred: String): String {
        val image = selected(css) ?: return ""
        val names = listOf(preferred, "data-src", "data-original", "data-lazy-src", "src", "srcset")
        for (name in names.distinct()) {
            val v = image.attr(name).trim()
            if (v.isNotEmpty()) {
                // srcset might provide a list of responsive URLs.
                return if (name == "srcset") v.substringBefore(',').trim().substringBefore(' ') else v
            }
        }
        return ""
    }

    fun searchUrl(source: SourceDefinition, query: String, page: Int = 1): String {
        source.validate()
        require(query.isNotBlank() && query.length <= 200) { "Enter a shorter manga search query" }
        require(page in 1..100) { "Page must be between 1 and 100" }
        val path = source.searchPath
            .replace("{query}", URLEncoder.encode(query, "UTF-8"))
            .replace("{page}", page.toString())
        return resolveSourceUrl(source.baseUrl + "/", path)
            ?: throw IllegalArgumentException("Search route leaves the selected source domain")
    }

    fun parseSearch(source: SourceDefinition, html: String, documentUrl: String): List<MangaResult> {
        val doc = Jsoup.parse(html, documentUrl)
        val seen = mutableSetOf<String>()
        return doc.select(source.rules.searchItem).take(300).mapNotNull { node ->
            val title = node.selected(source.rules.searchTitle)?.text()?.trim().orEmpty()
            val link = resolveSourceUrl(documentUrl, node.linkFor(source.rules.searchLink))
                ?: return@mapNotNull null
            if (title.isEmpty() || !seen.add(link)) return@mapNotNull null
            val cover = resolveImageUrl(documentUrl, node.imageFor(source.rules.searchCover, source.rules.coverAttribute))
            MangaResult(source.id, title, link, cover)
        }
    }

    fun parseDetails(source: SourceDefinition, html: String, documentUrl: String): MangaDetails {
        val doc: Document = Jsoup.parse(html, documentUrl)
        val rules = source.rules
        val title = doc.selectFirst(rules.detailTitle)?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: doc.title().ifEmpty { documentUrl }
        val description = rules.detailDescription.takeIf { it.isNotBlank() }
            ?.let { doc.selectFirst(it)?.text()?.trim() }.orEmpty()
        val cover = rules.detailCover.takeIf { it.isNotBlank() }
            ?.let { doc.selectFirst(it)?.imageFor(":scope", rules.coverAttribute) }
            ?.let { resolveImageUrl(documentUrl, it) }
        return MangaDetails(title, documentUrl, description, cover)
    }

    fun parseChapters(source: SourceDefinition, html: String, documentUrl: String): List<ChapterResult> {
        val doc = Jsoup.parse(html, documentUrl)
        val seen = mutableSetOf<String>()
        return doc.select(source.rules.chapterItem).take(5000).mapNotNull { node ->
            val href = if (source.rules.chapterLink.isBlank()) {
                node.linkFor(":scope")
            } else node.linkFor(source.rules.chapterLink)
            val url = resolveSourceUrl(documentUrl, href) ?: return@mapNotNull null
            if (!seen.add(url)) return@mapNotNull null
            val title = node.selected(source.rules.chapterTitle)?.text()?.trim()
                .orEmpty().ifBlank { "الفصل ${seen.size}" }
            ChapterResult(title, url, seen.size - 1)
        }
    }

    fun parsePages(source: SourceDefinition, html: String, chapterUrl: String): List<PageResult> {
        val doc = Jsoup.parse(html, chapterUrl)
        val seen = mutableSetOf<String>()
        return doc.select(source.rules.pageImage).take(5000).mapNotNull { node ->
            val src = node.imageFor(":scope", source.rules.pageImageAttribute)
            val url = resolveImageUrl(chapterUrl, src) ?: return@mapNotNull null
            if (!seen.add(url)) return@mapNotNull null
            PageResult(seen.size - 1, url, chapterUrl)
        }
    }
}
