package eu.kanade.tachiyomi.source.qari

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/** In-app, visible, resumable sync using pages legitimately opened in WebView. */
class QariMangaSyncActivity : Activity() {
    private lateinit var section: QariSection
    private lateinit var store: QariMangaStore
    private lateinit var web: WebView
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val scheduled = mutableListOf<Runnable>()
    private var enabled = false
    private var phase = "archive"
    private var chapterUrl = ""
    private var htmlPage = 0
    private var pageRounds = 0
    private var bottomRounds = 0
    private var expected = ""
    private lateinit var extractor: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        section = QariSection.from(intent.getStringExtra(EXTRA_SECTION))
        store = QariMangaStore(this)
        extractor = assets.open("qari_manga_scan.js").bufferedReader().use { it.readText() }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(21, 22, 27))
        }
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(16, 14, 16, 14)
            text = "${section.title}: افتح الموقع بصورة طبيعية ثم اضغط مزامنة"
        }
        column.addView(status)
        val controls = LinearLayout(this)
        fun control(label: String, onClick: () -> Unit) {
            controls.addView(Button(this).apply {
                text = label
                textSize = 11f
                setOnClickListener { onClick() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        control("مزامنة") { start(fresh = false) }
        control("تحديث من الأول") { start(fresh = true) }
        control("إيقاف") { pause("تم الإيقاف، البيانات والتقدم محفوظان") }
        control("إغلاق") { finish() }
        column.addView(controls)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (enabled) {
                        schedule(1600L) { scan() }
                    } else if (!QariUrls.isSite(url)) {
                        message("خارج موقع المصدر؛ ارجع إلى الموقع قبل المزامنة")
                    }
                }
                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    if (request.isForMainFrame) pause("تعذّر فتح الصفحة: ${error.description}")
                }
            }
        }
        column.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(column)
        web.loadUrl(section.archive())
    }

    private fun message(value: String) { status.text = value }

    private fun schedule(delay: Long, action: () -> Unit) {
        val job = Runnable(action)
        scheduled += job
        handler.postDelayed(job, delay)
    }

    private fun clearJobs() {
        scheduled.forEach(handler::removeCallbacks)
        scheduled.clear()
    }

    private fun pause(text: String) {
        enabled = false
        clearJobs()
        message(text)
    }

    private fun start(fresh: Boolean) {
        if (enabled) return
        if (fresh) {
            store.saveProgress(section, QariMangaStore.Progress("archive", section.archive(), emptySet(), 0))
        }
        val p = store.progress(section)
        phase = if (p.phase == "done") "archive" else p.phase
        if (p.phase == "done") {
            store.saveProgress(section, QariMangaStore.Progress("archive", section.archive(), emptySet(), 0))
        }
        enabled = true
        val url = when (phase) {
            "archive" -> p.nextUrl.takeIf { QariUrls.archivePage(it, section) != null } ?: section.archive()
            "details" -> store.nextUnscannedTitle(section)
            "chapters" -> store.nextUnscannedChapter(section)
            else -> null
        }
        if (url == null) {
            finishSync()
        } else {
            visit(url)
        }
    }

    private fun visit(url: String) {
        if (!enabled) return
        clearJobs()
        if (!QariUrls.isSite(url)) {
            pause("توقفت المزامنة: رابط خارج الموقع")
            return
        }
        expected = QariUrls.canonical(url).orEmpty()
        pageRounds = 0
        bottomRounds = 0
        message("${section.title}: $phase • ${QariUrls.relative(url)}")
        web.loadUrl(url)
    }

    private fun scan() {
        if (!enabled || pageRounds >= 65) {
            if (enabled) pause("توقفت لتجنب حلقة لا تنتهي. المحفوظات باقية؛ استأنف لاحقًا")
            return
        }
        pageRounds++
        web.evaluateJavascript(extractor) { raw ->
            if (!enabled) return@evaluateJavascript
            try {
                // Android wraps a returned JavaScript string in JSON quotes.
                val decoded = JSONArray("[$raw]").getString(0)
                val result = JSONObject(decoded)
                val url = result.optString("url")
                if (QariUrls.canonical(url) != expected) {
                    if (QariUrls.isSite(url)) pause("انتقلت الصفحة؛ راجع الرابط ثم استأنف")
                    return@evaluateJavascript
                }
                if (result.optString("kind") == "challenge") {
                    message("أكمل تحقق الموقع يدويًا؛ سيتم الاستمرار بعد فتح الصفحة")
                    schedule(3000L) { scan() }
                    return@evaluateJavascript
                }
                when (phase) {
                    "archive" -> onArchive(url, result)
                    "details" -> onDetails(url, result)
                    "chapters" -> onChapter(url, result)
                }
            } catch (e: Exception) {
                pause("لم يمكن قراءة محتوى الصفحة: ${e.message}")
            }
        }
    }

    private fun onArchive(url: String, data: JSONObject) {
        val items = data.optJSONArray("items") ?: JSONArray()
        if (data.optString("kind") != "archive" || items.length() == 0) {
            if (pageRounds < 8) schedule(1800L) { scan() }
            else pause("لم تظهر بطاقات مانجا بعد التحقق. لا يوجد ما يمكن استيراده")
            return
        }
        val pageUrl = QariUrls.archivePage(url, section) ?: run {
            pause("رابط الترقيم لا يطابق تصنيف القسم")
            return
        }
        val total = data.optInt("total", 0)
        val count = store.importListing(section, items, pageUrl, data.optString("nextUrl"), total)
        val p = store.progress(section)
        message("حُفظ $count عنوانًا، الصفحات ${p.visited.size}/${p.total} — ${section.title}")
        when (p.phase) {
            "archive" -> {
                if (p.nextUrl == url || p.nextUrl == expected) {
                    pause("هناك صفحات أخرى لكن الموقع لم يُظهر رابط الصفحة التالية. البيانات محفوظة")
                } else {
                    schedule(1300L) { visit(p.nextUrl) }
                }
            }
            else -> {
                phase = "details"
                schedule(800L) { nextTitle() }
            }
        }
    }

    private fun nextTitle() {
        if (!enabled) return
        val next = store.nextUnscannedTitle(section)
        if (next != null) visit(next) else {
            phase = "chapters"
            store.saveProgress(section, store.progress(section).copy(phase = "chapters", nextUrl = ""))
            nextChapter()
        }
    }

    private fun onDetails(url: String, data: JSONObject) {
        val chapters = data.optJSONArray("chapters") ?: JSONArray()
        if (chapters.length() == 0) {
            if (pageRounds < 7) schedule(1600L) { scan() }
            else pause("لم يظهر فهرس فصول لهذا العنوان؛ لم أعلّمه كمكتمل")
            return
        }
        val count = store.importDetails(section, url, data.optString("description"), chapters)
        message("حُفظ $count فصلًا من ${QariUrls.relative(url)}")
        schedule(1000L) { nextTitle() }
    }

    private fun nextChapter() {
        if (!enabled) return
        val next = store.nextUnscannedChapter(section)
        if (next == null) finishSync() else {
            chapterUrl = next
            htmlPage = 0
            visit(next)
        }
    }

    private fun onChapter(url: String, data: JSONObject) {
        val imgs = data.optJSONArray("images") ?: JSONArray()
        if (imgs.length() > 0) {
            store.importImages(section, chapterUrl, htmlPage, imgs)
        }
        val atBottom = data.optBoolean("atBottom", false)
        bottomRounds = if (atBottom) bottomRounds + 1 else 0
        if (bottomRounds >= 3 && imgs.length() > 0) {
            val nextHtml = data.optString("nextHtml").takeIf { it.isNotBlank() && QariUrls.isSite(it) }
            if (nextHtml != null && QariUrls.canonical(nextHtml) != expected) {
                htmlPage++
                schedule(900L) { visit(nextHtml) }
            } else {
                store.markChapterScanned(section, chapterUrl)
                message("حُفظت صور الفصل: ${store.pages(section, chapterUrl).size}")
                schedule(1000L) { nextChapter() }
            }
        } else {
            // Lazy images must be discovered as the reader scrolls.
            web.evaluateJavascript("window.scrollBy(0, Math.max(480, innerHeight * 0.8))", null)
            schedule(1350L) { scan() }
        }
    }

    private fun finishSync() {
        enabled = false
        clearJobs()
        store.saveProgress(section, store.progress(section).copy(phase = "done", nextUrl = ""))
        message("اكتملت مزامنة ${section.title}. ارجع إلى قائمة المصدر وحدّثها")
    }

    override fun onDestroy() {
        enabled = false
        clearJobs()
        web.stopLoading()
        web.destroy()
        store.close()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_SECTION = "qari_section"
        fun newIntent(context: Context, section: QariSection): Intent =
            Intent(context, QariMangaSyncActivity::class.java).putExtra(EXTRA_SECTION, section.key)
    }
}
