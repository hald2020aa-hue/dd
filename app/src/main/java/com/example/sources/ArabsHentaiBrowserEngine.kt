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
    initialSection: ArabsSection = ArabsSection.MANGA,
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
        val filterActive: Boolean = false,
        val genreOptions: List<String> = emptyList(),
    )

    private val handler = Handler(Looper.getMainLooper())
    private val script by lazy { context.assets.open("arabs_manga_extract.js").bufferedReader().use { it.readText() } }
    private var view: WebView? = null
    private var section = initialSection
    private var mode = "archive"
    private var syncing = false
    private var selectedWork: ArabsWork? = null
    private var selectedChapter: ArabsChapter? = null
    private var bulkChapterSync = false
    // Automatic details harvesting is silent: display the catalog, not each visited work.
    private var indexingDetails = false
    private var verifiedSession = false
    private var lastAttemptedCategoryRedirect = ""
    private val failedBulkChapters = mutableSetOf<String>()
    private var status = "جاري تجهيز المصدر..."
    private var generation = 0
    private var lastProcessed = ""
    private val callbacks = mutableListOf<Runnable>()
    private var activeFilter: ArabsBrowseFilter? = null
    private val filteredWorks = linkedMapOf<String, ArabsWork>()
    private val filteredVisited = linkedSetOf<String>()
    private var genreOptions: List<String> = emptyList()

    fun state(): UiState = UiState(
        section, status, syncing, if (activeFilter == null) store.list(section) else filteredWorks.values.toList(),
        selectedWork?.let { store.chapters(it.url) }.orEmpty(),
        selectedChapter?.let { store.pages(it.url) }.orEmpty(),
        if (indexingDetails || bulkChapterSync) null else selectedWork,
        if (bulkChapterSync) null else selectedChapter,
        store.progress(section), activeFilter != null, genreOptions,
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
                    if (ArabsSection.safeUrl(url) == null) {
                        pause(announce = false)
                        announce("الموقع نقل الصفحة خارج نطاق المصدر؛ افتح الموقع وتأكد من الرابط.")
                        return
                    }
                    if (mode == "archive" && section.type != null) {
                        val fixed = section.adaptedArchiveUrl(url)
                        if (fixed != null && fixed != url) {
                            if (fixed == lastAttemptedCategoryRedirect) {
                                pause(announce = false)
                                announce("الموقع أعاد توجيه القسم وحذف نوعه. افتح الرابط داخل المتصفح للتحقق.")
                                return
                            }
                            lastAttemptedCategoryRedirect = fixed
                            load(fixed)
                            return
                        }
                        lastAttemptedCategoryRedirect = ""
                    }
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
                        pause(announce = false)
                        announce("تعذر تحميل الموقع: ${error?.description ?: "خطأ شبكة"}")
                    }
                }
            }
            view = this
            // Offline-first catalog: do not reopen a verified site on every launch
            // when this section already has stored titles and chapters.
            val previous = store.progress(section)
            if (!previous.catalogDone) {
                syncing = true
                announce("بعد إكمال التحقق ستظهر العناوين بالتدريج داخل التطبيق.")
                loadUrl(previous.nextUrl ?: section.startUrl)
            }
        }
    }

    fun switchSection(next: ArabsSection) {
        pause(announce = false)
        section = next
        activeFilter = null
        filteredWorks.clear()
        filteredVisited.clear()
        mode = "archive"
        selectedWork = null
        selectedChapter = null
        indexingDetails = false
        val checkpoint = store.progress(next)
        if (!checkpoint.catalogDone) {
            syncing = true
            load(checkpoint.nextUrl ?: next.startUrl)
        }
        announce("${next.title}: ${checkpoint.totalWorks} عنوان محفوظ؛ يعرض الموجود أثناء استكمال القائمة")
    }

    fun startSync() {
        if (syncing) return
        activeFilter = null
        filteredWorks.clear()
        filteredVisited.clear()
        val progress = store.progress(section)
        if (progress.catalogDone) {
            val pending = store.nextUnscannedWork(section)
            if (pending == null) {
                announce("العناوين والفصول المفهرسة محدثة؛ تُحمّل الصور عند فتح الفصل فقط.")
                return
            }
            syncing = true
            indexingDetails = true
            mode = "detail"
            selectedWork = pending
            selectedChapter = null
            announce("فهرس العناوين ظاهر؛ جار جلب الفصول المتاحة في الخلفية.")
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

    fun applyFilters(order: String, status: String, genre: String) {
        val spec = try { ArabsBrowseFilter(order, status, genre.trim()) }
            catch (_: IllegalArgumentException) {
                announce("خيارات الفلترة غير صحيحة")
                return
            }
        pause(announce = false)
        activeFilter = spec
        filteredWorks.clear()
        filteredVisited.clear()
        selectedWork = null
        selectedChapter = null
        mode = "filter"
        syncing = true
        announce("جار جمع النتائج المفلترة داخل المتصفح...")
        load(spec.url(section))
    }

    fun loadGenreOptions() {
        pause(announce = false)
        mode = "genres"
        syncing = false
        selectedWork = null
        selectedChapter = null
        announce("جار فتح تصنيفات الموقع؛ أكمل التحقق إذا طلبه الموقع.")
        load("https://arabshentai.com/تصنيفات/")
    }

    fun clearFilters() {
        pause(announce = false)
        activeFilter = null
        filteredWorks.clear()
        filteredVisited.clear()
        selectedWork = null
        selectedChapter = null
        mode = "archive"
        announce("تمت إعادة عرض العناوين المحفوظة بدون حذفها.")
    }

    fun refreshFromFirst() {
        pause(announce = false)
        store.resetCheckpoint(section)
        startSync()
    }

    fun pause(announce: Boolean = true) {
        syncing = false
        bulkChapterSync = false
        indexingDetails = false
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
        indexingDetails = false
        val alreadySaved = store.chapters(work.url).size
        if (alreadySaved > 0) {
            announce("${work.title}: $alreadySaved فصل محفوظ. لا حاجة لإعادة الاستيراد.")
        } else {
            announce("${work.title} — جار جلب الفصول.")
            load(work.url)
        }
    }

    fun refreshSelectedWork() {
        val work = selectedWork ?: return
        pause(announce = false)
        selectedWork = work
        mode = "detail"
        announce("جاري تحديث فصول ${work.title} داخل جلسة الموقع الحالية.")
        load(work.url)
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
        mode = if (activeFilter == null) "archive" else "filter"
        announce("المحفوظات تظهر دون إعادة استيراد الصفحات.")
    }

    fun openSiteIfNeeded() {
        if (view?.url.isNullOrBlank()) load(section.startUrl)
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
        callbacks.forEach(handler::removeCallbacks)
        callbacks.clear()
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
                        verifiedSession = false
                        if (tries == 0) announce("أكمل التحقق داخل المتصفح، وسيُستأنف العرض في نفس الجلسة.")
                        if (tries < 180) scheduleExtract(web, requestedUrl, generation, tries + 1)
                        else {
                            pause(announce = false)
                            announce("انتهت مهلة التحقق. المحفوظات باقية؛ أعد تحميل الصفحة للاستئناف.")
                        }
                        return@evaluateJavascript
                    }
                    if (!verifiedSession) {
                        verifiedSession = true
                        // Persist cookies through normal WebView APIs after the visible verification.
                        CookieManager.getInstance().flush()
                        announce("تم فتح صفحة الموقع؛ جاري عرض العناوين والفصول المتاحة.")
                    }
                    if (modeAtRequest == "chapter") {
                        val chapter = selectedChapter
                        val raw = snapshot.optJSONArray("pages") ?: JSONArray()
                        if (chapter != null && urlMatchesChapter(resolved, chapter.url)) {
                            val partial = buildList {
                                for (i in 0 until minOf(raw.length(), 5000)) {
                                    val entry = raw.optJSONObject(i) ?: continue
                                    val address = entry.optString("url")
                                    if (address.startsWith("https://") && isSafePublicUrl(address)) {
                                        add(ArabsPage(address, i))
                                    }
                                }
                            }
                            if (partial.isNotEmpty()) store.savePages(chapter.url, partial, resolved)
                            if (snapshot.optBoolean("pending", false)) {
                                if (tries >= 600) {
                                    pause(announce = false)
                                    announce("فحص طويل لم يكتمل؛ حفظنا ${store.pages(chapter.url).size} صورة. " +
                                        "يمكنك استئناف الفصل دون فقدان المحفوظات.")
                                } else {
                                    if (tries % 12 == 0) {
                                        announce("جاري تحميل الصور تدريجيًا: " +
                                            "${store.pages(chapter.url).size} صورة محفوظة.")
                                    }
                                    scheduleExtract(web, requestedUrl, generation, tries + 1)
                                }
                                return@evaluateJavascript
                            }
                            if (snapshot.optBoolean("incomplete", false)) {
                                pause(announce = false)
                                announce("بعض صور الفصل لم تكتمل؛ المحفوظات باقية، أعد فتح الفصل للاستكمال.")
                                return@evaluateJavascript
                            }
                        }
                    }
                    // Publish partially rendered catalog cards immediately. Do not advance
                    // page checkpoints until the DOM scan has actually completed.
                    if ((modeAtRequest == "archive" || modeAtRequest == "filter") &&
                        snapshot.optBoolean("pending", false)) {
                        val interim = parseCards(snapshot.optJSONArray("items"))
                        if (interim.isNotEmpty()) {
                            if (modeAtRequest == "archive") {
                                store.saveFilteredWorks(section, interim)
                            } else {
                                store.saveFilteredWorks(section, interim)
                                interim.forEach { filteredWorks[it.url] = it }
                            }
                            if (tries % 4 == 0) announce(
                                "${section.title}: ${if (modeAtRequest == "filter") filteredWorks.size else store.progress(section).totalWorks} عنوان ظاهر الآن؛ جار إكمال الصفحة"
                            )
                        }
                        if (tries < 360) {
                            scheduleExtract(web, requestedUrl, generation, tries + 1)
                        } else {
                            pause(announce = false)
                            announce("لم تكتمل هذه الصفحة؛ الصفحات السابقة محفوظة. حاول مجددًا.")
                        }
                        return@evaluateJavascript
                    }
                    if (modeAtRequest == "detail" && snapshot.optBoolean("pending", false)) {
                        if (tries < 16) scheduleExtract(web, requestedUrl, generation, tries + 1)
                        return@evaluateJavascript
                    }
                    if ((modeAtRequest == "archive" || modeAtRequest == "filter") &&
                        snapshot.optBoolean("incomplete", false)) {
                        pause(announce = false)
                        announce("لم تكتمل هذه الصفحة؛ المحفوظات السابقة باقية.")
                        return@evaluateJavascript
                    }
                    val count = when (modeAtRequest) {
                        "archive", "filter" -> snapshot.optJSONArray("items")?.length() ?: 0
                        "genres" -> snapshot.optJSONArray("genres")?.length() ?: 0
                        "detail" -> snapshot.optJSONArray("chapters")?.length() ?: 0
                        else -> snapshot.optJSONArray("pages")?.length() ?: 0
                    }
                    if (count == 0 && tries < (if (modeAtRequest == "detail") 16 else 35)) {
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
        handler.postDelayed(task, when {
            tries == 0 -> 1300L
            mode == "chapter" || mode == "archive" || mode == "filter" -> 1250L
            else -> 2000L
        })
    }

    private fun parseCards(raw: JSONArray?): List<ArabsWork> = buildList {
        val list = raw ?: return@buildList
        for (i in 0 until minOf(list.length(), 500)) {
            val item = list.optJSONObject(i) ?: continue
            val address = item.optString("url")
            val title = item.optString("title").trim()
            if (ArabsSection.safeUrl(address) != null && title.length >= 2) {
                add(ArabsWork(address, title, item.optString("cover")))
            }
        }
    }

    private fun sameWorkPage(actual: String, expected: String): Boolean {
        val a = ArabsSection.safeUrl(actual) ?: return false
        val b = ArabsSection.safeUrl(expected) ?: return false
        return a.path.orEmpty().trimEnd('/') == b.path.orEmpty().trimEnd('/')
    }

    private fun urlMatchesChapter(page: String, chapter: String): Boolean {
        val actual = ArabsSection.safeUrl(page) ?: return false
        val original = ArabsSection.safeUrl(chapter) ?: return false
        return actual.path.orEmpty().trimEnd('/') == original.path.orEmpty().trimEnd('/')
    }

    private fun handle(response: JSONObject, currentMode: String) {
        val url = response.optString("url")
        if (lastProcessed == "$currentMode|$url") return
        when (currentMode) {
            "genres" -> {
                val arr = response.optJSONArray("genres") ?: JSONArray()
                genreOptions = buildList {
                    for (i in 0 until minOf(arr.length(), 400)) {
                        val title = arr.optString(i).trim()
                        if (title.length in 2..80 && title !in this) add(title)
                    }
                }
                announce(if (genreOptions.isNotEmpty())
                    "تم جلب ${genreOptions.size} تصنيفًا من الموقع؛ اختر المطلوب."
                    else "لم تظهر التصنيفات؛ تحقق من اكتمال عرض صفحة التصنيفات.")
            }
            "archive", "filter" -> {
                if (currentMode == "archive" && section.archiveUrl(url) == null) return
                if (currentMode == "filter" && activeFilter?.nextPage(section, url) == null) return
                val cards = parseCards(response.optJSONArray("items"))
                if (cards.isEmpty()) {
                    announce("الصفحة لا تحتوي بطاقات قابلة للاستخراج. تحقق من عرض الموقع أولًا.")
                    pause(announce = false)
                    return
                }
                lastProcessed = "$currentMode|$url"
                if (currentMode == "filter") {
                    val filter = activeFilter ?: return
                    if (!filteredVisited.add(url)) {
                        syncing = false
                        return
                    }
                    store.saveFilteredWorks(section, cards)
                    cards.forEach { filteredWorks[it.url] = it }
                    announce("تم العثور على ${filteredWorks.size} عنوان؛ جار استكمال صفحات التصفية...")
                    val next = response.optString("next").takeIf(String::isNotBlank)
                        ?.let { filter.nextPage(section, it) }
                    if (syncing && next != null && next !in filteredVisited &&
                        filteredVisited.size < 100) {
                        delayedLoad(next)
                    } else {
                        syncing = false
                        announce("تم عرض ${filteredWorks.size} عنوان حسب الفلاتر. افتح العمل لعرض فصوله.")
                    }
                    return
                }
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
                if (!sameWorkPage(url, work.url)) return
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
                            finishDetailsIndex()
                        }
                    } else {
                        announce("لم يُعثر على فصول قابلة للاستخراج. افتح صفحة العمل داخل المتصفح لتشخيصه.")
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
                        finishDetailsIndex()
                    }
                }
            }
            "chapter" -> {
                val chapter = selectedChapter ?: return
                if (!urlMatchesChapter(url, chapter.url)) return
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
                    if (bulkChapterSync) {
                        failedBulkChapters.add(chapter.url)
                        advanceBulkChapter()
                    } else {
                        syncing = false
                        announce("لم تظهر صور الفصل بعد؛ حاول إعادة فتحه عند توفر الصفحة.")
                    }
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
                    } else if (bulkChapterSync) {
                        advanceBulkChapter()
                    } else {
                        syncing = false
                        announce("اكتملت الصور التي أمكن العثور عليها لهذا الفصل.")
                    }
                }
            }
        }
    }

    private fun startBulkChapters() {
        if (!syncing) failedBulkChapters.clear()
        bulkChapterSync = true
        syncing = true
        mode = "chapter"
        advanceBulkChapter()
    }

    private fun advanceBulkChapter() {
        val next = store.nextUnscannedChapter(section, failedBulkChapters)
        if (next == null) {
            syncing = false
            bulkChapterSync = false
            selectedChapter = null
            selectedWork = null
            mode = "archive"
            val warning = if (failedBulkChapters.isEmpty()) "" else
                "تعذّر تحميل ${failedBulkChapters.size} فصل؛ يمكن إعادة المحاولة لاحقًا."
            announce("اكتملت فهرسة الفصول والصور المتاحة. " + warning)
            return
        }
        selectedWork = null
        selectedChapter = next
        mode = "chapter"
        announce("جمع صور ${next.title} — المحفوظات السابقة لن تُكرر.")
        delayedLoad(store.chapterRemaining(next.url) ?: next.url)
    }

    private fun finishDetailsIndex() {
        syncing = false
        indexingDetails = false
        mode = "archive"
        selectedWork = null
        selectedChapter = null
        announce("ظهرت جميع العناوين التي أمكن فهرستها، وتم حفظ قوائم فصولها المتاحة. اختر أي عمل لقراءته.")
    }

    private fun startDetailsAfterArchive() {
        val next = store.nextUnscannedWork(section)
        if (next == null) {
            finishDetailsIndex()
            return
        }
        indexingDetails = true
        mode = "detail"
        selectedWork = next
        announce("العناوين معروضة؛ جار استكمال فهرسة قوائم الفصول تدريجيًا.")
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
