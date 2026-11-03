package com.example.sources

import android.content.Context

/** Preferences-based catalog so no migration is needed to existing Room v1 tables. */
class SourceCatalogStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("manga_source_catalog_v1", Context.MODE_PRIVATE)

    @Synchronized
    fun list(): List<SourceDefinition> = try {
        SourceManifestCodec.decodeList(preferences.getString("sources", "[]") ?: "[]")
    } catch (_: Exception) { emptyList() }

    @Synchronized
    fun save(source: SourceDefinition) {
        source.validate()
        val stored = list().filterNot { it.id == source.id }
        require(stored.size < 100) { "Too many source definitions" }
        check(preferences.edit().putString("sources", SourceManifestCodec.encodeList(stored + source)).commit()) {
            "Could not save source catalog"
        }
    }

    @Synchronized
    fun saveAll(installed: List<SourceDefinition>) {
        require(installed.isNotEmpty()) { "The catalog has no sources" }
        installed.forEach { it.validate() }
        val merged = (list() + installed).associateBy { it.id }.values.toList()
        require(merged.size <= 100) { "The combined catalog exceeds 100 sources" }
        check(preferences.edit().putString("sources", SourceManifestCodec.encodeList(merged)).commit()) {
            "Could not save source catalog"
        }
    }

    @Synchronized
    fun remove(sourceId: String) {
        check(preferences.edit().putString(
            "sources", SourceManifestCodec.encodeList(list().filterNot { it.id == sourceId })
        ).commit()) { "Could not remove source" }
    }
}
