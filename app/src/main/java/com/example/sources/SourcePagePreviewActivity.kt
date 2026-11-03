package com.example.sources

import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity

/** Temporary image-only page preview until the dedicated chapter reader milestone. */
class SourcePagePreviewActivity : ComponentActivity() {
    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra("imageUrl").orEmpty()
        if (!isSafePublicUrl(url)) { finish(); return }
        webView = WebView(this).apply {
            settings.javaScriptEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.domStorageEnabled = false
            settings.blockNetworkImage = false
            loadUrl(url)
        }
        setContentView(webView)
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}
