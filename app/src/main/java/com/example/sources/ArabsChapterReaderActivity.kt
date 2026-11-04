package com.example.sources

import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.ComponentActivity

/** Single scrolling reader powered by already indexed page URLs.
 * Actual image bytes still require network unless previously cached by Android.
 */
class ArabsChapterReaderActivity : ComponentActivity() {
    private var reader: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val chapterUrl = intent.getStringExtra("chapterUrl").orEmpty()
        if (ArabsSection.safeUrl(chapterUrl) == null) { finish(); return }
        val pages = ArabsHentaiStore(applicationContext).use { store -> store.pages(chapterUrl) }
        if (pages.isEmpty()) { finish(); return }
        reader = WebView(this).apply {
            settings.javaScriptEnabled = false
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            val html = buildString {
                append("<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width, initial-scale=1'>")
                append("<style>body{background:#111;color:#eee;margin:0;font-family:sans-serif}")
                append("img{display:block;width:100%;height:auto;margin:0 auto 8px}")
                append("p{padding:12px}</style></head><body>")
                pages.forEach { page ->
                    val safe = page.url.replace("&", "&amp;").replace("\"", "&quot;")
                        .replace("'", "&#39;").replace("<", "&lt;")
                    append("<img loading='lazy' referrerpolicy='origin' src='").append(safe)
                        .append("' alt='صفحة ").append(page.position + 1).append("'>")
                }
                append("</body></html>")
            }
            loadDataWithBaseURL("https://arabshentai.com/", html, "text/html", "UTF-8", null)
        }
        setContentView(reader)
    }

    override fun onDestroy() {
        reader?.destroy()
        reader = null
        super.onDestroy()
    }
}
