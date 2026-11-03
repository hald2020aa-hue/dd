package com.example.extensions

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Installation is user-confirmed. Runtime execution of Mihon extensions is NOT provided here. */
class ExtensionStoreActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = ExtensionStoreRepository(applicationContext)
        val installer = ExtensionApkInstaller(applicationContext)
        setContent {
            MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
                ExtensionStoreScreen(
                    repository = repo,
                    installer = installer,
                    activity = this,
                    onBack = { finish() }
                )
            }
        }
    }
}

@Composable
private fun ExtensionStoreScreen(
    repository: ExtensionStoreRepository,
    installer: ExtensionApkInstaller,
    activity: Activity,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(repository.savedUrl()) }
    var snapshot by remember { mutableStateOf<ExtensionStoreSnapshot?>(null) }
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var showMature by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<ExtensionEntry?>(null) }

    LaunchedEffect(Unit) { snapshot = repository.cached() }
    val all = snapshot?.extensions.orEmpty()
    val visible = remember(all, query, showMature) {
        all.filter { entry ->
            (showMature || entry.contentRating == 1) &&
                (query.isBlank() || entry.name.contains(query, ignoreCase = true) ||
                    entry.packageName.contains(query, ignoreCase = true) ||
                    entry.sources.any { it.name.contains(query, ignoreCase = true) })
        }
    }

    Surface(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onBack) { Text("رجوع") }
                    Text("متجر ملحقات المانجا", style = MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.height(8.dp))
                Text("فهرس Mihon / Keiyoushi: عرض الملحقات وتنزيلها بعد التحقق من توقيع APK.")
                Text(
                    "تنبيه: تشغيل الملحقات داخل Manga D غير متاح بعد. التثبيت وحده لا يربطها بمحرك القراءة.",
                    color = MaterialTheme.colorScheme.error
                )
            }
            item {
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text("رابط index.pb أو repo.json أو index.json") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy && url.isNotBlank(), onClick = {
                        scope.launch {
                            busy = true
                            status = "جار تحميل فهرس المستودع والتحقق منه..."
                            try {
                                val loaded = repository.load(url)
                                snapshot = loaded
                                status = "تم تحميل ${loaded.extensions.size} ملحقًا من ${loaded.name} (${loaded.format})."
                            } catch (e: Exception) {
                                status = "فشل الاستيراد: ${e.localizedMessage ?: "خطأ غير معروف"}"
                            } finally { busy = false }
                        }
                    }) { Text("استيراد المستودع") }
                    if (busy) CircularProgressIndicator()
                }
                if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.tertiary)
            }
            item {
                val store = snapshot
                if (store != null) {
                    Text("${store.name} — ${all.size} ملحق", fontWeight = FontWeight.Bold)
                    Text(
                        if (store.signingKeySha256 == null) "بصمة التوقيع غير متاحة؛ التثبيت ممنوع"
                        else "بصمة توقيع المستودع متاحة؛ يتم التحقق منها قبل التثبيت"
                    )
                    OutlinedTextField(
                        value = query, onValueChange = { query = it.take(100) },
                        label = { Text("البحث عن ملحق أو موقع") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Checkbox(checked = showMature, onCheckedChange = { showMature = it })
                        Text("إظهار جميع فئات المحتوى، بما فيها المختلط و18+", modifier = Modifier.padding(top = 12.dp))
                    }
                    Text("النتائج: ${visible.size}")
                    HorizontalDivider()
                }
            }
            items(visible, key = { it.packageName }) { extension ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(extension.name, style = MaterialTheme.typography.titleMedium)
                        Text("${extension.language} • v${extension.versionName} • ${extension.sources.size} مصدر")
                        Text(extension.packageName, style = MaterialTheme.typography.bodySmall)
                        Text(extension.sources.take(3).joinToString("، ") { it.name },
                            style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(
                            enabled = !busy && snapshot?.signingKeySha256 != null,
                            onClick = { pending = extension }
                        ) { Text("تنزيل وتثبيت APK") }
                    }
                }
            }
            if (snapshot != null && visible.isEmpty()) {
                item { Text("لا توجد نتائج مطابقة للبحث والفلاتر المحددة.") }
            }
        }
    }

    val chosen = pending
    if (chosen != null) {
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("تأكيد تنزيل ملحق خارجي") },
            text = { Text(
                "${chosen.name} من مستودع ${snapshot?.name}. قد تمتلك ملحقات الطرف الثالث صلاحيات واسعة. " +
                "سنفحص اسم الحزمة والإصدار والتوقيع، ثم أندرويد سيطلب موافقتك على التثبيت. " +
                "لا يمكن تشغيل الملحق داخل Manga D حتى اكتمال محرك التوافق."
            ) },
            confirmButton = {
                Button(onClick = {
                    pending = null
                    scope.launch {
                        busy = true
                        status = "جار تنزيل الملحق والتحقق من توقيعه..."
                        try {
                            val file = installer.downloadVerified(chosen, snapshot!!)
                            val requested = installer.requestUserInstall(activity, file)
                            status = if (requested) "تم فتح شاشة تثبيت Android. لم يُؤكد التثبيت بعد."
                                else "فعّل السماح بتثبيت التطبيقات من هذا المصدر، ثم اضغط تنزيل وتثبيت مرة أخرى."
                        } catch (e: Exception) {
                            status = "مُنِع تنزيل/تثبيت الملحق: ${e.localizedMessage ?: "خطأ"}"
                        } finally { busy = false }
                    }
                }) { Text("موافق، تنزيل وفحص") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("إلغاء") } }
        )
    }
}
