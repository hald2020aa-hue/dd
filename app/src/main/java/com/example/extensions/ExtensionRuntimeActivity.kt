package com.example.extensions

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Experimental runtime probe. Explicit user action required before third-party code is loaded. */
class ExtensionRuntimeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        val host = MihonRuntimeController(applicationContext)
        setContent {
            MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
                RuntimeScreen(packageName, host, onBack = { finish() }, onPreview = { url ->
                    startActivity(Intent(this, com.example.sources.SourcePagePreviewActivity::class.java)
                        .putExtra("imageUrl", url))
                })
            }
        }
    }
    companion object { const val EXTRA_PACKAGE = "packageName" }
}

@Composable
private fun RuntimeScreen(
    packageName: String,
    host: MihonRuntimeController,
    onBack: () -> Unit,
    onPreview: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("لم يتم تشغيل كود الملحق بعد.") }
    var session by remember { mutableStateOf<MihonRuntimeController.Session?>(null) }
    var currentSource by remember { mutableStateOf<Any?>(null) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MihonReflection.Manga>>(emptyList()) }
    var selectedManga by remember { mutableStateOf<MihonReflection.Manga?>(null) }
    var chapters by remember { mutableStateOf<List<MihonReflection.Chapter>>(emptyList()) }
    var pages by remember { mutableStateOf<List<MihonReflection.Page>>(emptyList()) }

    fun launchTask(action: suspend () -> Unit) {
        scope.launch {
            busy = true
            try { action() }
            catch (error: Throwable) { status = "تعذر تشغيل المصدر: ${MihonReflection.rootCauseMessage(error)}" }
            finally { busy = false }
        }
    }

    Surface(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onBack) { Text("رجوع") }
                    Text("اختبار تشغيل Mihon 1.6", style = MaterialTheme.typography.titleLarge)
                }
                Text(packageName, style = MaterialTheme.typography.bodySmall)
                Text(
                    "تجريبي: هذا الاختبار ينفذ كود الملحق الخارجي بعد موافقتك. " +
                        "التحقق من التوقيع لا يضمن أمان الكود. لا تتوفر بعد جميع مكتبات Mihon المضيفة.",
                    color = MaterialTheme.colorScheme.error
                )
                if (session == null) {
                    Button(enabled = !busy, onClick = {
                        launchTask {
                            status = "جار التحقق من توقيع APK وتحميل مصادره..."
                            val loaded = host.openVerified(packageName)
                            session = loaded
                            currentSource = loaded.sources.firstOrNull()
                            status = "تم تحميل ${loaded.sources.size} مصدر. جرّب البحث للتحقق من التشغيل الحقيقي."
                        }
                    }) { Text("أوافق على اختبار تشغيل الملحق") }
                }
                if (busy) CircularProgressIndicator()
                Text(status, color = MaterialTheme.colorScheme.tertiary)
            }
            if (session != null) {
                item { Text("المصادر المتاحة في الملحق", style = MaterialTheme.typography.titleMedium) }
                items(session!!.sources.indices.toList()) { index ->
                    val source = session!!.sources[index]
                    OutlinedButton(onClick = {
                        currentSource = source
                        results = emptyList(); chapters = emptyList(); pages = emptyList()
                        status = "المصدر الحالي: ${MihonReflection.sourceName(source)}"
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("${MihonReflection.sourceName(source)} — ${MihonReflection.sourceId(source)}")
                    }
                }
            }
            if (currentSource != null) {
                item {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it.take(120) },
                        label = { Text("البحث في المصدر المثبت") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy && query.isNotBlank(), onClick = {
                            val source = currentSource ?: return@Button
                            launchTask {
                                status = "جار البحث من واجهة الملحق نفسها..."
                                results = withContext(Dispatchers.IO) { MihonReflection.search(source, query) }
                                chapters = emptyList(); pages = emptyList()
                                status = "نتائج البحث: ${results.size}"
                            }
                        }) { Text("بحث") }
                        OutlinedButton(enabled = !busy, onClick = {
                            val source = currentSource ?: return@OutlinedButton
                            launchTask {
                                results = withContext(Dispatchers.IO) { MihonReflection.popular(source) }
                                chapters = emptyList(); pages = emptyList()
                                status = "النتائج الشائعة: ${results.size}"
                            }
                        }) { Text("استعراض") }
                    }
                }
                items(results, key = { it.url }) { item ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp)) {
                            Text(item.title, style = MaterialTheme.typography.titleMedium)
                            Text(item.url, style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(enabled = !busy, onClick = {
                                val source = currentSource ?: return@OutlinedButton
                                launchTask {
                                    selectedManga = item
                                    chapters = withContext(Dispatchers.IO) { MihonReflection.chapters(source, item) }
                                    pages = emptyList()
                                    status = "فصول ${item.title}: ${chapters.size}"
                                }
                            }) { Text("عرض الفصول") }
                        }
                    }
                }
                if (selectedManga != null) item { Text("فصول ${selectedManga!!.title}") }
                items(chapters, key = { it.url }) { chapter ->
                    OutlinedButton(enabled = !busy, onClick = {
                        val source = currentSource ?: return@OutlinedButton
                        launchTask {
                            pages = withContext(Dispatchers.IO) { MihonReflection.pages(source, chapter) }
                            status = "صفحات الفصل: ${pages.size}"
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text(chapter.name) }
                }
                items(pages) { page ->
                    OutlinedButton(onClick = {
                        val url = page.imageUrl ?: page.url
                        if (url.startsWith("https://")) onPreview(url)
                        else status = "رابط الصورة ليس HTTPS. يلزم محرك تنزيل الصور الخاص بالمصدر."
                    }, modifier = Modifier.fillMaxWidth()) { Text("صفحة ${page.index + 1}") }
                }
            }
        }
    }
}
