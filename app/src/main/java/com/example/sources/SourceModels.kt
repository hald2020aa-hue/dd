package com.example.sources

import java.net.URI
import java.util.Locale

/** Source packages are declarative HTML extraction rules, never executable code. */
data class SourceRules(
    val searchItem: String,
    val searchTitle: String,
    val searchLink: String,
    val searchCover: String = "img",
    val detailTitle: String = "h1",
    val detailDescription: String = "",
    val detailCover: String = "",
    val chapterItem: String,
    val chapterTitle: String = "",
    val chapterLink: String = "",
    val pageImage: String,
    val coverAttribute: String = "data-src",
    val pageImageAttribute: String = "data-src",
    val searchNextPage: String = ""
)

data class SourceDefinition(
    val id: String,
    val name: String,
    val baseUrl: String,
    val searchPath: String,
    val rules: SourceRules,
    val language: String = "ar"
) {
    fun validate() {
        require(id.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{1,63}"))) { "Invalid source id" }
        require(name.isNotBlank() && name.length <= 80) { "Invalid source name" }
        require(language.length in 2..12) { "Invalid language" }
        require(isSafePublicUrl(baseUrl) && URI(baseUrl).scheme.equals("https", true)) {
            "Source baseUrl must be a public HTTPS URL"
        }
        val base = URI(baseUrl)
        require(base.rawUserInfo == null && base.rawQuery == null && base.rawFragment == null) { "Invalid base URL" }
        require(searchPath.startsWith("/") && searchPath.contains("{query}") && searchPath.length <= 500) {
            "searchPath must start with / and contain {query}"
        }
        require(!searchPath.startsWith("//") && !searchPath.contains("\\")) { "Invalid search path" }
        val selectors = listOf(
            rules.searchItem, rules.searchTitle, rules.searchLink,
            rules.chapterItem, rules.pageImage
        )
        require(selectors.all { it.isNotBlank() && it.length <= 240 }) { "Missing or oversized CSS selector" }
        val optionalSelectors = listOf(
            rules.searchCover, rules.detailTitle, rules.detailDescription, rules.detailCover,
            rules.chapterTitle, rules.chapterLink, rules.searchNextPage
        )
        require(optionalSelectors.all { it.length <= 240 }) { "Oversized CSS selector" }
        require(listOf(rules.coverAttribute, rules.pageImageAttribute).all { it.length <= 50 }) {
            "Invalid image attribute"
        }
    }
}

data class MangaResult(
    val sourceId: String,
    val title: String,
    val url: String,
    val coverUrl: String? = null
)

data class MangaDetails(
    val title: String,
    val url: String,
    val description: String = "",
    val coverUrl: String? = null
)

data class ChapterResult(
    val title: String,
    val url: String,
    val index: Int
)

data class PageResult(
    val index: Int,
    val imageUrl: String,
    val chapterUrl: String
)

/** Avoid allowing imported manifests to make requests to literal private/loopback destinations. */
fun isSafePublicUrl(url: String): Boolean {
    return try {
    val uri = URI(url.trim())
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    val host = uri.host?.lowercase(Locale.ROOT) ?: return false
    if (scheme != "https" && scheme != "http") return false
    if (uri.rawUserInfo != null || host == "localhost" || host.endsWith(".localhost") ||
        host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".test")) return false
    if (!host.contains('.') || host.length > 253) return false
    if (uri.port !in setOf(-1, 80, 443)) return false
    if (host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.") ||
        host.startsWith("169.254.") || host == "0.0.0.0" || host == "::1") return false
    if (host.startsWith("172.")) {
        val second = host.split('.').getOrNull(1)?.toIntOrNull()
        if (second != null && second in 16..31) return false
    }
    // Block numeric IPv4 hosts, including uncommon textual representations.
    if (host.all { it.isDigit() || it == '.' }) return false
    // This also excludes bracketed IPv6 literal destinations.
    if (host.contains(':')) return false
    true
} catch (_: Exception) { false }
}

/** Only HTML navigation within the configured source host is allowed. */
fun resolveSourceUrl(baseUrl: String, href: String): String? {
    return try {
    if (href.trim().isEmpty() || href.trim().startsWith("//")) return null
    val base = URI(baseUrl)
    val resolved = base.resolve(href.trim()).normalize()
    val host = base.host ?: return null
    if (!isSafePublicUrl(resolved.toString())) return null
    if (!resolved.host.equals(host, ignoreCase = true)) return null
    resolved.toString()
} catch (_: Exception) { null }
}

/** Page/cover images are often served by distinct CDNs. URL still must be public HTTP(S). */
fun resolveImageUrl(baseUrl: String, href: String): String? {
    return try {
    if (href.isBlank() || href.trim().startsWith("data:", ignoreCase = true)) return null
    val candidate = URI(baseUrl).resolve(href.trim()).normalize().toString()
    candidate.takeIf { isSafePublicUrl(it) }
} catch (_: Exception) { null }
}
