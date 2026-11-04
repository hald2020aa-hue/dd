package com.example.sources

import android.annotation.SuppressLint
import android.os.Build
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject

/** Gecko/Chrome session cookies are not copied into the native networking client.
 * This engine only reads the DOM visible in its own WebView after user verification.
 */
class ArabsHentaiBrowserEngine(
    private val context: Context,
    private val store: ArabsHentaiStore,
    private val onChange: (UiState) -> Unit,
) {
    data class UiState(
        val section: ArabsSection = ArabsSection.MANGA,
        val status: String = "اختر القسم ثم افتح الموقع للتحقق، أو استعرض المحفوظات.",
        val syncing: Boolean = false,
        val works: List<ArabsWork> = emptyList(),
        val chapters: List<ArabsChapter> = emptyList(),
        val pages: List<ArabsPage> = emptyList(),
        val selectedWork: ArabsWork? = null,
        val selectedChapter: ArabsChapter? = null,
        val progress: ArabsProgress = ArabsProgress(),
    )

    private val handler = Handler(Looper.getMainLooper())
    private val script by lazy { context.assets.open("arabs_manga_extract.js").bufferedReader().use { it.readText() } }
    private var view: WebView? = null
    private var section = ArabsSection.MANGA
    private var mode = "archive"
    private var syncing = false
    private var selectedWork: ArabsWork? = null
    private var selectedChapter: ArabsChapter? = null
    private var status = "جاري تجهيز المصدر..."
    private var generation = 0
    private var lastProcessed = ""
    private val callbacks = mutableListOf<Runnable>()

    fun state(): UiState = UiState(
        section, status, syncing, store.list(section),
        selectedWork?.let { store.chapters(it.url) }.orEmpty(),
        selectedChapter?.let { store.pages(it.url) }.orEmpty(),
        selectedWork, selectedChapter, store.progress(section),
    )

    private fun announce(text: String) { status = text; onChange(state()) }

    @SuppressLint("SetJavaScriptEnabled")
    fun createWebView(context: Context): WebView {
        return WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) settings.safeBrowsingEnabled = true
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(webView: WebView, url: String) {
                    super.onPageFinished(webView, url)
                    generation += 1
                    lastProcessed = ""
                    scheduleExtract(webView, url, generation, 0)
                }
                override fun onReceivedError(
                    webView: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    super.onReceivedError(webView, request, error)
                    if (request?.isForMainFrame == true) {
                        announce("تعذر تحميل الموقع: ${error?.description ?: "خطأ شبكة"}")
                    }
                }
            }
            view = this
            loadUrl(section.startUrl)
        }
    }

    fun switchSection(next: ArabsSection) {
        pause(announce = false)
        section = next
        mode = "archive"
        selectedWork = null
        selectedChapter = null
        load(next.startUrl)
        announce("${next.title}: ${store.progress(next).totalWorks} عنوان محفوظ")
    }

    fun startSync() {
        if (syncing) return
        val progress = store.progress(section)
        if (progress.catalogDone) {
            val pending = store.nextUnscannedWork(section)
            if (pending == null) {
                announce("كل العناوين التي أمكن استيرادها محفوظة. لتحديث القوائم اضغط إعادة الفهرسة.")
                return
            }
            syncing = true
            mode = "detail"
            selectedWork = pending
            selectedChapter = null
            announce("جار جمع فصول ${pending.title}...")
            load(pending.url)
        } else {
            syncing = true
            mode = "archive"
            selectedWork = null
            selectedChapter = null
            val target = progress.nextUrl ?: section.startUrl
            announce("جار جمع صفحات ${section.title} وحفظها... لا تغلق شاشة المصدر.")
            load(target)
        }
    }

    fun refreshFromFirst() {
        pause(announce = false)
        store.resetCheckpoint(section)
        startSync()
    }

    fun pause(announce: Boolean = true) {
        syncing = false
        callbacks.forEach(handler::removeCallbacks)
        callbacks.clear()
        if (announce) announce("تم الإيقاف؛ الفهرس والفصول والصور المحفوظة لم تُحذف.")
    }

    fun openWork(work: ArabsWork) {
        pause(announce = false)
        if (ArabsSection.safeUrl(work.url) == null) return
        selectedWork = work
        selectedChapter = null
        mode = "detail"
        val alreadySaved = store.chapters(work.url).size
        if (alreadySaved > 0) {
            announce("${work.title}: $alreadySaved فصل محفوظ. لا حاجة لإعادة الاستيراد.")
        } else {
            announce("${work.title} — جار جلب الفصول.")
            load(work.url)
        }
    }

    fun openChapter(chapter: ArabsChapter) {
        pause(announce = false)
        if (ArabsSection.safeUrl(chapter.url) == null) return
        selectedChapter = chapter
        mode = "chapter"
        val existing = store.pages(chapter.url).size
        if (existing > 0 && store.chapterCompleted(chapter.url)) {
            announce("${chapter.title}: $existing صورة محفوظة؛ افتح القارئ مباشرة.")
        } else {
            syncing = true
            announce("${chapter.title} — جار جمع صفحات الفصل تلقائيًا.")
            load(store.chapterRemaining(chapter.url) ?: chapter.url)
        }
    }

    fun backToCatalog() {
        pause(announce = false)
        selectedChapter = null
        selectedWork = null
        mode = "archive"
        announce("المحفوظات تظهر دون إعادة استيراد الصفحات.")
    }

    fun refreshView() {
        when (mode) {
            "detail" -> selectedWork?.url?.let(::load) ?: view?.reload()
            "chapter" -> selectedChapter?.url?.let(::load) ?: view?.reload()
            else -> view?.reload()
        }
    }

    private fun load(url: String) {
        if (ArabsSection.safeUrl(url) == null) {
            pause(announce = false)
            announce("تم إيقاف رابط خارج نطاق المصدر")
            return
        }
        lastProcessed = ""
        generation += 1
        view?.loadUrl(url)
    }

    private fun scheduleExtract(web: WebView, requestedUrl: String, generation: Int, tries: Int) {
        val task = Runnable {
            if (this.generation != generation || view !== web) return@Runnable
            val modeAtRequest = mode
            val code = script.replace("__QARI_MODE__", modeAtRequest)
            web.evaluateJavascript(code) { encoded ->
                if (this.generation != generation || view !== web) return@evaluateJavascript
                try {
                    // evaluateJavascript returns JSON encoding of the JS string result.
                    val wrapped = JSONArray("[$encoded]").optString(0)
                    val snapshot = JSONObject(wrapped)
                    val resolved = snapshot.optString("url")
                    if (ArabsSection.safeUrl(resolved) == null) return@evaluateJavascript
                    if (web.url != resolved) return@evaluateJavascript
                    if (snapshot.optBoolean("blocked", false)) {
                        announce("الموقع يطلب التحقق؛ أكمله داخل الصفحة ثم تستكمل الفهرسة.")
                        if (tries < 3) scheduleExtract(web, requestedUrl, generation, tries + 1)
                        return@evaluateJavascript
                    }
                    val count = when (modeAtRequest) {
                        "archive" -> snapshot.optJSONArray("items")?.length() ?: 0
                        "detail" -> snapshot.optJSONArray("chapters")?.length() ?: 0
                        else -> snapshot.optJSONArray("pages")?.length() ?: 0
                    }
                    if (count == 0 && tries < 2) {
                        scheduleExtract(web, requestedUrl, generation, tries + 1)
                        return@evaluateJavascript
                    }
                    handle(snapshot, modeAtRequest)
                } catch (e: Exception) {
                    announce("لم يمكن تحليل الصفحة الحالية: ${e.message.orEmpty().take(90)}")
                    pause(announce = false)
                }
            }
        }
        callbacks.add(task)
        handler.postDelayed(task, if (tries == 0) 1300L else 2000L)
    }

    private fun handle(response: JSONObject, currentMode: String) {
        val url = response.optString("url")
        if (lastProcessed == "$currentMode|$url") return
        when (currentMode) {
            "archive" -> {
                if (section.archiveUrl(url) == null) return
                val json = response.optJSONArray("items") ?: JSONArray()
                val cards = buildList {
                    for (i in 0 until minOf(json.length(), 160)) {
                        val item = json.optJSONObject(i) ?: continue
                        val link = item.optString("url")
                        if (ArabsSection.safeUrl(link) == null) continue
                        val title = item.optString("title").trim()
                        if (title.length < 2) continue
                        add(ArabsWork(link, title, item.optString("cover")))
                    }
                }
                if (cards.isEmpty()) {
                    announce("الصفحة لا تحتوي بطاقات قابلة للاستخراج. تحقق من عرض الموقع أولًا.")
                    pause(announce = false)
                    return
                }
                lastProcessed = "$currentMode|$url"
                val progress = store.saveArchive(
                    section, url, cards, response.optString("next").takeIf(String::isNotBlank),
                    response.optInt("total", 0),
                )
                announce("${section.title}: حفظ ${progress.totalWorks} عنوان، صفحة ${progress.pageCount}" +
                    if (progress.expectedPages > 0) "/${progress.expectedPages}" else "")
                if (syncing) {
                    if (progress.nextUrl != null) {
                        delayedLoad(progress.nextUrl)
                    } else if (progress.catalogDone) {
                        startDetailsAfterArchive()
                    } else {
                        pause(announce = false)
                        announce("توقفت عند صفحة ${progress.pageCount}: لم أجد رابط التالية. بياناتك محفوظة.")
                    }
                }
            }
            "detail" -> {
                val work = selectedWork ?: return
                if (url != work.url) return
                val json = response.optJSONArray("chapters") ?: JSONArray()
                val entries = buildList {
                    for (i in 0 until minOf(json.length(), 2500)) {
                        val item = json.optJSONObject(i) ?: continue
                        val link = item.optString("url")
                        if (ArabsSection.safeUrl(link) == null) continue
                        add(ArabsChapter(link, item.optString("title").ifBlank { "الفصل ${i + 1}" }, i))
                    }
                }
                if (entries.isEmpty()) {
                    if (syncing) {
                        store.markDetailsFailed(section, work.url)
                        val next = store.nextUnscannedWork(section)
                        if (next != null) {
                            selectedWork = next
                            announce("تعذّر استخراج فصول ${work.title}؛ انتقلت للعنوان التالي.")
                            delayedLoad(next.url)
                        } else {
                            syncing = false
                            announce("انتهت المحاولة؛ بعض العناوين لم تعرض فصولها ويمكن تحديثها لاحقًا.")
                        }
                    } else {
                        announce("لم تظهر روابط فصول هذا العنوان. افتح الصفحة وتحقق من تحميلها.")
                    }
                    return
                }
                store.saveChapters(section, work.url, entries)
                lastProcessed = "$currentMode|$url"
                announce("${work.title}: تم حفظ ${entries.size} فصل.")
                if (syncing) {
                    val next = store.nextUnscannedWork(section)
                    if (next != null) {
                        selectedWork = next
                        delayedLoad(next.url)
                    } else {
                        syncing = false
                        announce("اكتمل جمع العناوين والفصول التي أمكن الوصول إليها لهذا القسم.")
                    }
                }
            }
            "chapter" -> {
                val chapter = selectedChapter ?: return
                if (url != chapter.url) return
                val json = response.optJSONArray("pages") ?: JSONArray()
                val images = buildList {
                    for (i in 0 until minOf(json.length(), 3000)) {
                        val item = json.optJSONObject(i) ?: continue
                        val address = item.optString("url")
                        if (address.startsWith("https://") && isSafePublicUrl(address)) {
                            add(ArabsPage(address, i))
                        }
                    }
                }
                if (images.isEmpty()) {
                    syncing = false
                    announce("لم أجد صور صفحات الفصل؛ قد تكون محملة ديناميكيًا. افتح الفصل ومرر لأسفل ثم حدّث.")
                    return
                }
                val next = store.saveChapterPage(
                    chapter.url, url, images, response.optString("next").takeIf(String::isNotBlank),
                )
                lastProcessed = "$currentMode|$url"
                announce("تم حفظ روابط ${store.pages(chapter.url).size} صورة للفصل.")
                if (syncing) {
                    if (next != null) {
                        delayedLoad(next)
                    } else {
                        syncing = false
                        announce("اكتملت الصور التي أمكن العثور عليها لهذا الفصل.")
                    }
                }
            }
        }
    }

    private fun startDetailsAfterArchive() {
        val next = store.nextUnscannedWork(section)
        if (next == null) {
            syncing = false
            announce("تم حفظ كل العناوين والفصول المتاحة؛ افتح أي فصل لجمع صوره.")
            return
        }
        mode = "detail"
        selectedWork = next
        delayedLoad(next.url)
    }

    private fun delayedLoad(url: String) {
        val task = Runnable { if (syncing) load(url) }
        callbacks.add(task)
        handler.postDelayed(task, 1800L)
    }

    fun dispose() {
        pause(announce = false)
        view?.stopLoading()
        view?.destroy()
        view = null
    }
}
