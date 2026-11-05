package eu.kanade.tachiyomi.source.qari

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/** Visible verification fallback. Imports the ONE requested catalog page, not the whole site. */
class QariMangaSyncActivity : Activity() {
    private lateinit var section: QariSection
    private lateinit var store: QariMangaStore
    private lateinit var cache: QariLiveCache
    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var extractor: String
    private val handler = Handler(Looper.getMainLooper())
    private val jobs = mutableListOf<Runnable>()
    private var page = 1
    private var enabled = false
    private var scanning = false
    private var rounds = 0
    private var challenges = 0
    private var bottomRounds = 0
    private var lastCount = -1
    private val cards = linkedMapOf<String, JSONObject>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        section = QariSection.from(intent.getStringExtra(EXTRA_SECTION))
        page = intent.getIntExtra(EXTRA_PAGE, 1).coerceIn(1, 5000)
        store = QariMangaStore(this)
        cache = QariLiveCache(this)
        extractor = assets.open("qari_manga_scan.js").bufferedReader().use { it.readText() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(20, 21, 26))
        }
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(20, 16, 20, 16)
            text = "${section.title}: الصفحة $page — إن ظهر التحقق أكمله ثم سيظهر المحتوى تلقائيًا"
        }
        root.addView(status)
        val buttons = LinearLayout(this)
        fun button(label: String, action: () -> Unit) {
            buttons.addView(Button(this).apply {
                text = label
                textSize = 11f
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        button("إعادة المحاولة") { visit() }
        button("عرض المحفوظ") { finish() }
        button("إغلاق") { finish() }
        root.addView(buttons)
        CookieManager.getInstance().setAcceptCookie(true)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.userAgentString = WebSettings.getDefaultUserAgent(this@QariMangaSyncActivity)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (enabled) schedule(900) { scan() }
                }
                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    if (request.isForMainFrame) {
                        message("فشل التحميل: ${error.description}. اضغط إعادة المحاولة")
                    }
                }
            }
        }
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        visit()
    }

    private fun message(value: String) { status.text = value }

    private fun schedule(ms: Long, block: () -> Unit) {
        val job = object : Runnable {
            override fun run() {
                jobs.remove(this)
                block()
            }
        }
        jobs += job
        handler.postDelayed(job, ms)
    }

    private fun cancelJobs() {
        jobs.forEach(handler::removeCallbacks)
        jobs.clear()
    }

    private fun visit() {
        cancelJobs()
        enabled = true
        scanning = false
        rounds = 0
        challenges = 0
        bottomRounds = 0
        lastCount = -1
        cards.clear()
        val url = QariLiveParser.archiveUrl(section, page)
        message("فتح ${section.title} — صفحة $page. سيبدأ العرض بعد التحقق")
        web.loadUrl(url)
        schedule(2500) { scan() }
    }

    private fun scan() {
        if (!enabled || scanning || isFinishing || isDestroyed) return
        if (++rounds > 65) {
            message("تعذر إكمال قراءة الصفحة. تستطيع عرض المحفوظ أو إعادة المحاولة")
            enabled = false
            return
        }
        scanning = true
        web.evaluateJavascript(extractor) { raw ->
            scanning = false
            if (!enabled || isFinishing || isDestroyed) return@evaluateJavascript
            try {
                val decoded = JSONArray("[$raw]").getString(0)
                val result = JSONObject(decoded)
                if (result.optString("kind") == "challenge") {
                    rounds = 0
                    challenges++
                    if (challenges <= 180) {
                        message("أكمل تحقق الموقع، ثم يبدأ عرض الصفحة $page تلقائيًا")
                        schedule(3000) { scan() }
                    } else {
                        message("التحقق لم يكتمل. تستطيع إعادة المحاولة")
                        enabled = false
                    }
                    return@evaluateJavascript
                }
                val current = result.optString("url")
                if (!sameSectionPage(current) || result.optString("kind") != "archive") {
                    message("الموقع غيّر القسم أو لم يعرض الأرشيف. افتح رابط القسم الصحيح")
                    enabled = false
                    return@evaluateJavascript
                }
                val items = result.optJSONArray("items") ?: JSONArray()
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val key = QariUrls.canonical(item.optString("url")) ?: continue
                    if (item.optString("title").isNotBlank()) cards[key] = item
                }
                if (cards.isNotEmpty()) {
                    val collected = JSONArray()
                    cards.values.forEach { collected.put(it) }
                    val hasNext = result.optString("nextUrl").isNotBlank() ||
                        result.optInt("total", 1) > page
                    cache.merge(section, page, collected, hasNext)
                    store.upsertLiveTitles(section, collected)
                    message("ظهرت ${cards.size} عنوانًا — ${section.title}، صفحة $page")
                }
                val atBottom = result.optBoolean("atBottom", false)
                bottomRounds = if (atBottom && cards.size == lastCount) bottomRounds + 1 else 0
                lastCount = cards.size
                if (cards.isNotEmpty() && bottomRounds >= 2) {
                    CookieManager.getInstance().flush()
                    enabled = false
                    cancelJobs()
                    setResult(RESULT_OK)
                    finish()
                } else if (rounds < 65) {
                    web.evaluateJavascript("window.scrollBy(0, Math.max(400, innerHeight * 0.75))", null)
                    schedule(950) { scan() }
                } else {
                    message("توقّف الفحص لحماية التطبيق. العناوين التي ظهرت محفوظة")
                    enabled = false
                }
            } catch (e: Exception) {
                message("تعذر قراءة الصفحة: ${e.message}")
                enabled = false
            }
        }
    }

    private fun sameSectionPage(url: String): Boolean {
        val parsed = QariUrls.archivePage(url, section) ?: return false
        val uri = android.net.Uri.parse(parsed)
        val path = Regex("/manga/page/(\\d+)").find(uri.path.orEmpty())
            ?.groupValues?.get(1)?.toIntOrNull()
        val currentPage = path ?: uri.getQueryParameter("paged")?.toIntOrNull()
            ?: uri.getQueryParameter("page")?.toIntOrNull() ?: 1
        // A missing 'type' after a redirect is NOT considered verified for a filtered shelf.
        if (section.siteType != null && uri.getQueryParameter("type") != section.siteType) {
            // archivePage may normalize missing 'type', so check the actual URL too.
            if (android.net.Uri.parse(url).getQueryParameter("type") != section.siteType) return false
        }
        return QariUrls.isSite(url) && currentPage == page
    }

    override fun onDestroy() {
        enabled = false
        cancelJobs()
        web.stopLoading()
        web.destroy()
        store.close()
        cache.close()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_SECTION = "qari_section"
        private const val EXTRA_PAGE = "qari_page"
        fun newIntent(context: Context, section: QariSection, page: Int = 1): Intent =
            Intent(context, QariMangaSyncActivity::class.java)
                .putExtra(EXTRA_SECTION, section.key)
                .putExtra(EXTRA_PAGE, page)
    }
}
