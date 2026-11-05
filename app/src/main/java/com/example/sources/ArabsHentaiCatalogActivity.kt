package com.example.sources

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage

/** All four sections inside Manga D. Cached entries are shown without a network request. */
class ArabsHentaiCatalogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
                val store = remember { ArabsHentaiStore(applicationContext) }
                var state by remember { mutableStateOf(
                    ArabsHentaiBrowserEngine.UiState(
                        works = store.list(ArabsSection.MANGA),
                        progress = store.progress(ArabsSection.MANGA),
                    ),
                ) }
                val engine = remember(store) {
                    ArabsHentaiBrowserEngine(applicationContext, store) { newState -> state = newState }
                }
                DisposableEffect(engine) {
                    onDispose { engine.dispose(); store.close() }
                }
                ArabsCatalogScreen(
                    state = state,
                    engine = engine,
                    onBack = { finish() },
                    onReadChapter = { chapterUrl ->
                        startActivity(
                            Intent(this, ArabsChapterReaderActivity::class.java)
                                .putExtra("chapterUrl", chapterUrl),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun ArabsCatalogScreen(
    state: ArabsHentaiBrowserEngine.UiState,
    engine: ArabsHentaiBrowserEngine,
    onBack: () -> Unit,
    onReadChapter: (String) -> Unit,
) {
    var search by remember(state.section) { mutableStateOf("") }
    var showBrowser by remember { mutableStateOf(state.works.isEmpty()) }
    var filterOrder by remember(state.section) { mutableStateOf("new_chapter") }
    var filterStatus by remember(state.section) { mutableStateOf("") }
    var filterGenre by remember(state.section) { mutableStateOf("") }
    BackHandler(state.selectedWork != null || state.selectedChapter != null) {
        engine.backToCatalog()
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onBack) { Text("رجوع") }
                Text("Manga D UI — فهرس القراءة", style = MaterialTheme.typography.titleLarge)
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(ArabsSection.entries) { section ->
                    if (state.section == section) {
                        Button(onClick = { engine.switchSection(section); showBrowser = true }) {
                            Text(section.title)
                        }
                    } else {
                        OutlinedButton(onClick = { engine.switchSection(section); showBrowser = true }) {
                            Text(section.title)
                        }
                    }
                }
            }
            Text("الفلاتر: الترتيب والحالة والتصنيفات", style = MaterialTheme.typography.titleMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                items(listOf("آخر تحديث" to "new_chapter", "الأحدث" to "new-manga",
                    "أبجدي" to "alphabet")) { (label, value) ->
                    if (filterOrder == value) Button(onClick = { filterOrder = value }) { Text(label) }
                    else OutlinedButton(onClick = { filterOrder = value }) { Text(label) }
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                items(listOf("الكل" to "", "مستمر" to "on-going", "مكتمل" to "end",
                    "متوقف" to "on-hold", "ملغي" to "canceled")) { (label, value) ->
                    if (filterStatus == value) Button(onClick = { filterStatus = value }) { Text(label) }
                    else OutlinedButton(onClick = { filterStatus = value }) { Text(label) }
                }
            }
            OutlinedTextField(
                value = filterGenre,
                onValueChange = { filterGenre = it.take(80) },
                label = { Text("التصنيف حسب اسم الموقع (اختياري)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = {
                engine.loadGenreOptions()
                showBrowser = true
            }) { Text("تحميل تصنيفات الموقع") }
            if (state.genreOptions.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(state.genreOptions) { genre ->
                        if (filterGenre == genre) Button(onClick = { filterGenre = genre }) { Text(genre) }
                        else OutlinedButton(onClick = { filterGenre = genre }) { Text(genre) }
                    }
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    Button(onClick = {
                        engine.applyFilters(filterOrder, filterStatus, filterGenre)
                        showBrowser = true
                    }) { Text("تطبيق الفلاتر") }
                }
                item {
                    OutlinedButton(onClick = {
                        filterOrder = "new_chapter"
                        filterStatus = ""
                        filterGenre = ""
                        engine.clearFilters()
                    }) { Text("إلغاء الفلاتر") }
                }
            }
            Text(
                "محفوظ: ${state.progress.totalWorks} عنوان، " +
                    "الصفحات: ${state.progress.pageCount}" +
                    if (state.progress.expectedPages > 0) "/${state.progress.expectedPages}" else "",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(state.status, style = MaterialTheme.typography.bodySmall)
            if (state.progress.lastError.isNotBlank()) {
                Text(state.progress.lastError, color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(enabled = !state.syncing, onClick = { engine.startSync(); showBrowser = true }) {
                    Text("مزامنة العناوين والفصول وجميع الصور")
                }
                OutlinedButton(enabled = state.syncing, onClick = { engine.pause() }) { Text("إيقاف") }
                OutlinedButton(onClick = { engine.refreshFromFirst(); showBrowser = true }) {
                    Text("تحديث كامل")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = {
                    if (!showBrowser) engine.openSiteIfNeeded()
                    showBrowser = !showBrowser
                }) {
                    Text(if (showBrowser) "إخفاء المتصفح" else "عرض المتصفح")
                }
                OutlinedButton(onClick = engine::refreshView) { Text("تحديث الصفحة") }
                if (state.selectedWork != null) {
                    OutlinedButton(onClick = engine::backToCatalog) { Text("رجوع للفهرس") }
                }
            }
            // Keep WebView mounted even while showing the cached catalog: closing it
            // during an in-progress verification would reset the current browsing UI.
            AndroidView(
                factory = { engine.createWebView(it) },
                modifier = Modifier.fillMaxWidth().height(if (showBrowser) 310.dp else 1.dp),
            )
            HorizontalDivider()
            if (state.selectedChapter != null) {
                Text("صور ${state.selectedChapter.title}: ${state.pages.size}")
                if (state.pages.isNotEmpty()) {
                    Button(onClick = { onReadChapter(state.selectedChapter.url) }) {
                        Text("قراءة جميع صفحات الفصل")
                    }
                }
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(state.pages, key = { it.url }) { page ->
                        Text("صورة ${page.position + 1}: ${page.url.substringAfterLast('/').take(65)}")
                    }
                }
            } else if (state.selectedWork != null) {
                Text("فصول: ${state.selectedWork.title}", style = MaterialTheme.typography.titleMedium)
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(state.chapters, key = { it.url }) { chapter ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                                .clickable { engine.openChapter(chapter); showBrowser = true },
                        ) {
                            Text(chapter.title, modifier = Modifier.padding(12.dp))
                        }
                    }
                    if (state.chapters.isEmpty()) item {
                        Text("لا توجد فصول محفوظة بعد؛ افتح صفحة العنوان وتحقق من تحميلها.")
                    }
                }
            } else {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text("ابحث في العناوين المحفوظة") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val filtered = remember(state.works, search) {
                    if (search.isBlank()) state.works else state.works.filter {
                        it.title.contains(search, ignoreCase = true)
                    }
                }
                Text((if (state.filterActive) "نتائج الفلاتر" else "العناوين المحفوظة") +
                    " (${filtered.size}) — بدون إعادة استيراد", style = MaterialTheme.typography.titleMedium)
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(filtered, key = { it.url }) { work ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                                .clickable { engine.openWork(work); showBrowser = true },
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                if (work.cover.isNotBlank()) {
                                    AsyncImage(
                                        model = work.cover,
                                        contentDescription = work.title,
                                        modifier = Modifier.size(64.dp),
                                    )
                                }
                                Column {
                                    Text(work.title, style = MaterialTheme.typography.titleMedium)
                                    Text(work.url, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    if (filtered.isEmpty()) item {
                        Text("لا توجد نتائج بعد. أكمل التحقق؛ يتم استخراج العناصر تلقائيًا بعد ظهورها.")
                    }
                }
            }
        }
    }
}
