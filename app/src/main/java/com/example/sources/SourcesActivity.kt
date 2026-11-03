package com.example.sources

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** First real source-engine management entry point: catalog -> search -> chapters -> page URLs. */
class SourcesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val engine = SourceManager(SourceCatalogStore(applicationContext))
        setContent {
            MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
                SourcesScreen(engine, onBack = { finish() }, onPreview = { url ->
                    startActivity(Intent(this, SourcePagePreviewActivity::class.java).putExtra("imageUrl", url))
                })
            }
        }
    }
}

@Composable
private fun SourcesScreen(
    engine: SourceManager,
    onBack: () -> Unit,
    onPreview: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var catalog by remember { mutableStateOf(engine.availableSources()) }
    var selected by remember { mutableStateOf<SourceDefinition?>(null) }
    var editorVisible by remember { mutableStateOf(false) }
    var manifestInput by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var catalogUrl by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MangaResult>>(emptyList()) }
    var manga by remember { mutableStateOf<MangaResult?>(null) }
    var chapters by remember { mutableStateOf<List<ChapterResult>>(emptyList()) }
    var chapter by remember { mutableStateOf<ChapterResult?>(null) }
    var pages by remember { mutableStateOf<List<PageResult>>(emptyList()) }

    Surface(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onBack) { Text("رجوع") }
                    Text("مصادر المانجا", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text("محرك HTML: البحث، المانجا، الفصول، وروابط الصفحات. إضافة مصدر تتطلب قواعد CSS متوافقة مع موقعه.")
                if (message.isNotBlank()) {
                    Text(message, color = MaterialTheme.colorScheme.tertiary)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { editorVisible = !editorVisible }) {
                        Text(if (editorVisible) "إغلاق إضافة المصدر" else "استيراد تعريف مصدر")
                    }
                    if (busy) CircularProgressIndicator()
                }
                if (editorVisible) {
                    OutlinedTextField(
                        value = manifestInput,
                        onValueChange = { manifestInput = it.take(24_000) },
                        label = { Text("JSON تعريف المصدر وقواعد CSS") },
                        minLines = 5,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(onClick = {
                        try {
                            val added = engine.addManifest(manifestInput)
                            catalog = engine.availableSources()
                            selected = added
                            editorVisible = false
                            manifestInput = ""
                            message = "تم حفظ المصدر ${added.name}. اختبر البحث للتحقق من قواعده."
                            results = emptyList()
                        } catch (e: Exception) {
                            message = "تعذر إضافة المصدر: ${e.message ?: "بيانات غير صالحة"}"
                        }
                    }) { Text("حفظ المصدر") }
                }
            }
            item {
                OutlinedTextField(
                    value = catalogUrl,
                    onValueChange = { catalogUrl = it },
                    label = { Text("رابط كتالوج مصادر JSON (HTTPS)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(enabled = !busy && catalogUrl.isNotBlank(), onClick = {
                    scope.launch {
                        busy = true; message = "جاري استيراد كتالوج المصادر..."
                        try {
                            val count = engine.installCatalog(catalogUrl.trim())
                            catalog = engine.availableSources()
                            catalogUrl = ""
                            message = "استوردت $count تعريف مصدر. اختبر المواقع كلًا على حدة."
                        } catch (e: Exception) { message = "فشل استيراد الكتالوج: ${e.message}" }
                        finally { busy = false }
                    }
                }) { Text("استيراد كتالوج") }
            }
            item { Text("المصادر المثبتة (${catalog.size})", style = MaterialTheme.typography.titleMedium) }
            if (catalog.isEmpty()) {
                item { Text("لا توجد مصادر مثبتة. استورد ملف JSON أولًا. أرفقنا قالبًا للتخصيص في docs/example-source.json.") }
            }
            items(catalog, key = { it.id }) { source ->
                Card(modifier = Modifier.fillMaxWidth().clickable {
                    selected = source
                    results = emptyList(); manga = null; chapters = emptyList(); chapter = null; pages = emptyList()
                    message = "المصدر النشط: ${source.name}"
                }) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(source.name, style = MaterialTheme.typography.titleMedium)
                        Text("${source.id} | ${source.baseUrl}", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = {
                                selected = source
                                results = emptyList(); manga = null; chapters = emptyList(); pages = emptyList()
                            }) { Text("تحديد") }
                            OutlinedButton(onClick = {
                                engine.deleteSource(source.id)
                                catalog = engine.availableSources()
                                if (selected?.id == source.id) selected = null
                                message = "تم حذف تعريف المصدر ${source.name}"
                            }) { Text("حذف") }
                        }
                    }
                }
            }
            if (selected != null) {
                item {
                    HorizontalDivider()
                    Text("بحث داخل: ${selected!!.name}", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("اسم المانجا") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(enabled = !busy && query.isNotBlank(), onClick = {
                        val id = selected!!.id
                        scope.launch {
                            busy = true; message = "جاري البحث الفعلي في المصدر..."
                            try {
                                results = engine.search(id, query)
                                manga = null; chapters = emptyList(); chapter = null; pages = emptyList()
                                message = "عدد النتائج: ${results.size}"
                            } catch (e: Exception) { message = "فشل البحث: ${e.message}" }
                            finally { busy = false }
                        }
                    }) { Text("بحث") }
                }
                items(results, key = { it.url }) { item ->
                    Card(modifier = Modifier.fillMaxWidth().clickable {
                        manga = item; chapter = null; pages = emptyList()
                        scope.launch {
                            busy = true; message = "جاري جلب الفصول..."
                            try {
                                chapters = engine.chapters(item.sourceId, item.url)
                                message = "فصول ${item.title}: ${chapters.size}"
                            } catch (e: Exception) { message = "فشل جلب الفصول: ${e.message}"; chapters = emptyList() }
                            finally { busy = false }
                        }
                    }) {
                        Column(Modifier.padding(12.dp)) {
                            Text(item.title, fontWeight = FontWeight.SemiBold)
                            Text(item.url, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (manga != null) {
                    item { Text("فصول: ${manga!!.title}", style = MaterialTheme.typography.titleMedium) }
                    items(chapters, key = { it.url }) { c ->
                        OutlinedButton(onClick = {
                            chapter = c; pages = emptyList()
                            scope.launch {
                                busy = true; message = "جاري جلب صور الفصل..."
                                try {
                                    pages = engine.pages(selected!!.id, c.url)
                                    message = "صور الفصل: ${pages.size}"
                                } catch (e: Exception) { message = "فشل جلب الصور: ${e.message}" }
                                finally { busy = false }
                            }
                        }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                            Text(c.title)
                        }
                    }
                }
                if (chapter != null) {
                    item { Text("صفحات: ${chapter!!.title}", style = MaterialTheme.typography.titleMedium) }
                    items(pages, key = { it.index }) { p ->
                        OutlinedButton(onClick = { onPreview(p.imageUrl) }, modifier = Modifier.fillMaxWidth()) {
                            Text("معاينة صورة ${p.index + 1}")
                        }
                    }
                }
            }
        }
    }
}
