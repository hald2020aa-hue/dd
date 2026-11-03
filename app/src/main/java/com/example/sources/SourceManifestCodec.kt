package com.example.sources

import org.json.JSONArray
import org.json.JSONObject

/** Persistent schema v1. A manifest cannot provide Kotlin/JS code or execution permissions. */
object SourceManifestCodec {
    fun decode(json: String): SourceDefinition {
        require(json.length <= 24_000) { "Source manifest is too large" }
        val root = JSONObject(json)
        require(root.optInt("schemaVersion", 1) == 1) { "Unsupported source manifest schema" }
        val css = root.getJSONObject("selectors")
        fun selector(name: String, default: String = "") = css.optString(name, default)
        return SourceDefinition(
            id = root.getString("id"),
            name = root.getString("name"),
            baseUrl = root.getString("baseUrl").trimEnd('/'),
            searchPath = root.getString("searchPath"),
            language = root.optString("language", "ar"),
            rules = SourceRules(
                searchItem = selector("searchItem"),
                searchTitle = selector("searchTitle"),
                searchLink = selector("searchLink"),
                searchCover = selector("searchCover", "img"),
                detailTitle = selector("detailTitle", "h1"),
                detailDescription = selector("detailDescription"),
                detailCover = selector("detailCover"),
                chapterItem = selector("chapterItem"),
                chapterTitle = selector("chapterTitle"),
                chapterLink = selector("chapterLink"),
                pageImage = selector("pageImage"),
                coverAttribute = selector("coverAttribute", "data-src"),
                pageImageAttribute = selector("pageImageAttribute", "data-src"),
                searchNextPage = selector("searchNextPage")
            )
        ).also { it.validate() }
    }

    fun encode(source: SourceDefinition): String {
        source.validate()
        val r = source.rules
        val css = JSONObject()
            .put("searchItem", r.searchItem)
            .put("searchTitle", r.searchTitle)
            .put("searchLink", r.searchLink)
            .put("searchCover", r.searchCover)
            .put("detailTitle", r.detailTitle)
            .put("detailDescription", r.detailDescription)
            .put("detailCover", r.detailCover)
            .put("chapterItem", r.chapterItem)
            .put("chapterTitle", r.chapterTitle)
            .put("chapterLink", r.chapterLink)
            .put("pageImage", r.pageImage)
            .put("coverAttribute", r.coverAttribute)
            .put("pageImageAttribute", r.pageImageAttribute)
            .put("searchNextPage", r.searchNextPage)
        return JSONObject().put("schemaVersion", 1)
            .put("id", source.id)
            .put("name", source.name)
            .put("language", source.language)
            .put("baseUrl", source.baseUrl)
            .put("searchPath", source.searchPath)
            .put("selectors", css).toString()
    }

    fun encodeList(definitions: List<SourceDefinition>): String {
        val json = JSONArray()
        definitions.forEach { json.put(JSONObject(encode(it))) }
        return json.toString()
    }

    fun decodeList(raw: String): List<SourceDefinition> {
        val array = JSONArray(raw)
        require(array.length() <= 100) { "Too many sources" }
        return (0 until array.length()).map { decode(array.getJSONObject(it).toString()) }
    }
}
