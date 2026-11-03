package com.example.extensions

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Metadata only. APK execution requires a separate, compatible extension-host runtime. */
data class ExtensionSourceInfo(val id: String, val name: String, val language: String, val homeUrl: String)
data class ExtensionEntry(
    val name: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val language: String,
    val apkUrl: String,
    val contentRating: Int,
    val sources: List<ExtensionSourceInfo>
)
data class ExtensionStoreSnapshot(
    val name: String,
    val indexUrl: String,
    val signingKeySha256: String?,
    val extensions: List<ExtensionEntry>,
    val format: String
)

object ExtensionStoreCodec {
    /** Parses Keiyoushi/Mihon v2 JSON and legacy Tachiyomi index.min.json arrays. */
    fun decodeJson(raw: String, originalUrl: String): ExtensionStoreSnapshot {
        require(raw.length <= 16_000_000) { "Store index exceeds 16 MB" }
        val trimmed = raw.trimStart('\uFEFF', '\r', '\n', ' ', '\t')
        require(trimmed.isNotBlank()) { "Empty extension index" }
        return when (trimmed.first()) {
            '{' -> parseV2(JSONObject(trimmed), originalUrl)
            '[' -> parseLegacy(JSONArray(trimmed), originalUrl)
            else -> throw IllegalArgumentException("Not a JSON extension index")
        }
    }

    private fun parseV2(root: JSONObject, url: String): ExtensionStoreSnapshot {
        val arr = root.optJSONObject("extensionList")?.optJSONArray("extensions")
            ?: root.optJSONArray("extensions")
            ?: throw IllegalArgumentException("Store JSON has no extensionList.extensions")
        require(arr.length() <= 12_000) { "Store index has too many extensions" }
        val entries = ArrayList<ExtensionEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val pkg = obj.optString("packageName")
            val apk = obj.optJSONObject("resources")?.optString("apkUrl").orEmpty()
            if (!isExtensionPackage(pkg) || !isHttpsUrl(apk)) continue
            val src = obj.optJSONArray("sources") ?: JSONArray()
            val sources = ArrayList<ExtensionSourceInfo>(minOf(src.length(), 100))
            for (k in 0 until minOf(src.length(), 100)) {
                val s = src.optJSONObject(k) ?: continue
                sources += ExtensionSourceInfo(
                    s.optString("id"), s.optString("name"),
                    s.optString("language", "all"), s.optString("homeUrl")
                )
            }
            val rating = when (obj.optString("contentWarning").uppercase()) {
                "CONTENT_WARNING_NSFW", "NSFW" -> 3
                "CONTENT_WARNING_MIXED", "MIXED" -> 2
                else -> 1
            }
            val langs = sources.map { it.language }.distinct()
            entries += ExtensionEntry(
                name = obj.optString("name", pkg), packageName = pkg,
                versionName = obj.optString("versionName"),
                versionCode = obj.optLong("versionCode"),
                language = if (langs.size == 1) langs.first() else "all",
                apkUrl = apk, contentRating = rating, sources = sources
            )
        }
        return ExtensionStoreSnapshot(
            name = root.optString("name", "Extension Store"), indexUrl = url,
            signingKeySha256 = parseFingerprint(root.optString("signingKey")),
            extensions = entries.distinctBy { it.packageName }, format = "Mihon v2 JSON"
        )
    }

    private fun parseLegacy(arr: JSONArray, url: String): ExtensionStoreSnapshot {
        require(arr.length() <= 12_000) { "Too many extensions" }
        val prefix = url.substringBeforeLast('/', "") + "/apk/"
        val entries = ArrayList<ExtensionEntry>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val pkg = obj.optString("pkg")
            val file = obj.optString("apk")
            if (!isExtensionPackage(pkg) || !Regex("[A-Za-z0-9_.-]+\\.apk").matches(file)) continue
            val src = obj.optJSONArray("sources") ?: JSONArray()
            val sources = ArrayList<ExtensionSourceInfo>()
            for (j in 0 until minOf(src.length(), 100)) {
                val s = src.optJSONObject(j) ?: continue
                sources += ExtensionSourceInfo(
                    s.optString("id"), s.optString("name"),
                    s.optString("lang", "all"), s.optString("baseUrl")
                )
            }
            entries += ExtensionEntry(
                name = obj.optString("name", pkg), packageName = pkg,
                versionName = obj.optString("version"), versionCode = obj.optLong("code"),
                language = obj.optString("lang", "all"), apkUrl = prefix + file,
                contentRating = if (obj.optInt("nsfw", 0) != 0) 3 else 1,
                sources = sources
            )
        }
        return ExtensionStoreSnapshot("Legacy extension index", url, null, entries.distinctBy { it.packageName }, "Legacy JSON")
    }

    fun isHttpsUrl(url: String): Boolean = try {
        val u = URI(url)
        u.scheme.equals("https", true) && !u.host.isNullOrBlank() && u.rawUserInfo == null &&
            u.port in listOf(-1, 443) && u.fragment == null
    } catch (_: Exception) { false }

    fun isExtensionPackage(pkg: String): Boolean =
        Regex("[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*){2,}").matches(pkg) &&
            pkg.length <= 200

    fun parseFingerprint(value: String?): String? = value?.lowercase()?.takeIf {
        Regex("[0-9a-f]{64}").matches(it)
    }
}
