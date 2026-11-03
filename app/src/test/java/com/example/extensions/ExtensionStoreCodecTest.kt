package com.example.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionStoreCodecTest {
    @Test fun modernIndexParsesNestedExtensions() {
        val json = """{
          "name":"Test Repo","signingKey":"${"a".repeat(64)}",
          "extensionList":{"extensions":[
            {"name":"Test Manga", "packageName":"eu.kanade.tachiyomi.extension.en.test",
             "resources":{"apkUrl":"https://example.org/releases/test.apk"},
             "extensionLib":"1.6","versionCode":"106002","versionName":"1.6.2",
             "contentWarning":"CONTENT_WARNING_SAFE",
             "sources":[{"id":"123456789","name":"Test Manga","language":"en","homeUrl":"https://example.org"}]}
          ]}
        }"""
        val result = ExtensionStoreCodec.decodeJson(json, "https://example.org/index.json")
        assertEquals("Test Repo", result.name)
        assertEquals("a".repeat(64), result.signingKeySha256)
        assertEquals(1, result.extensions.size)
        assertEquals(106002L, result.extensions.first().versionCode)
        assertEquals(1, result.extensions.first().contentRating)
        assertEquals("en", result.extensions.first().language)
    }

    @Test fun legacyIndexNeedsPinnedSignatureToInstall() {
        val json = """[{"name":"Legacy","pkg":"eu.kanade.tachiyomi.extension.en.legacy",
          "apk":"tachiyomi-en.legacy-v1.4.1.apk","lang":"en","code":1,"version":"1.4.1",
          "nsfw":0,"sources":[]}]"""
        val result = ExtensionStoreCodec.decodeJson(json, "https://example.org/repo/index.min.json")
        assertEquals(1, result.extensions.size)
        assertEquals(null, result.signingKeySha256)
        assertTrue(result.extensions.first().apkUrl.endsWith("/apk/tachiyomi-en.legacy-v1.4.1.apk"))
    }

    @Test fun unsafeUrlsRejected() {
        assertTrue(ExtensionStoreCodec.isHttpsUrl("https://raw.githubusercontent.com/example/store/index.pb"))
        assertEquals(false, ExtensionStoreCodec.isHttpsUrl("http://example.com/file.apk"))
        assertEquals(false, ExtensionStoreCodec.isHttpsUrl("https://name:password@example.com/a.apk"))
    }
}
