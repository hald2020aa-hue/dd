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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Verification and discovery stage. No untrusted APK bytecode is loaded here. */
class InstalledExtensionsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scanner = InstalledExtensionScanner(applicationContext)
        val store = ExtensionStoreRepository(applicationContext)
        setContent {
            MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
                InstalledExtensionsScreen(
                    scanner, store,
                    onBack = { finish() },
                    onTryRun = { pkg -> startActivity(
                        Intent(this, ExtensionRuntimeActivity::class.java)
                            .putExtra(ExtensionRuntimeActivity.EXTRA_PACKAGE, pkg)
                    ) }
                )
            }
        }
    }
}

@Composable
private fun InstalledExtensionsScreen(
    scanner: InstalledExtensionScanner,
    store: ExtensionStoreRepository,
    onBack: () -> Unit,
    onTryRun: (String) -> Unit,
) {
    var refresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var installed by remember { mutableStateOf<List<InstalledExtensionRecord>>(emptyList()) }
    LaunchedEffect(refresh) {
        busy = true
        error = null
        try { installed = scanner.scan(store.cached()) }
        catch (e: Exception) { error = e.localizedMessage ?: "تعذر قراءة حزم Android" }
        finally { busy = false }
    }
    Surface(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onBack) { Text("رجوع") }
                    Text("الملحقات المثبتة", style = MaterialTheme.typography.titleLarge)
                }
                Text("فحص الملحقات المثبتة على الجهاز: المصدر والتوقيع وإصدار واجهة Mihon.")
                Text(
                    "يمكن اختبار تشغيل ملحق موثوق يدويًا. نجاح الفحص لا يضمن توافق مكتبات Mihon أو سلامة كود الملحق.",
                    color = MaterialTheme.colorScheme.error
                )
                Button(onClick = { refresh++ }, enabled = !busy) { Text("إعادة فحص الملحقات") }
                if (busy) CircularProgressIndicator()
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("موجودة: ${installed.size} | توقيع متحقق: ${installed.count { it.decision.canInspect }}")
                Text("للتحقق من الثقة، حمّل المستودع أولًا في متجر الملحقات.")
            }
            if (!busy && installed.isEmpty()) item {
                Text("لم يتم العثور على ملحقات مسجلة في Android. إذا ثبّتَّ ملحقًا، افتح هذه الشاشة مجددًا.")
            }
            items(installed, key = { it.packageName }) { ext ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(ext.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(ext.packageName, style = MaterialTheme.typography.bodySmall)
                        Text("الإصدار: ${ext.version} | واجهة: ${ext.libraryVersion ?: "مجهولة"}")
                        Text(ext.decision.reason,
                            color = if (ext.decision.canInspect) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error)
                        if (ext.decision.canInspect && ext.libraryVersion == "1.6") {
                            OutlinedButton(onClick = { onTryRun(ext.packageName) }) {
                                Text("اختبار تشغيل الملحق — تجريبي")
                            }
                        }
                        if (ext.sourceClasses.isNotEmpty())
                            Text("تعريفات الكود: ${ext.sourceClasses.joinToString()}", style = MaterialTheme.typography.bodySmall)
                        if (ext.declaredSourceNames.isNotEmpty())
                            Text("مصادر الفهرس: ${ext.declaredSourceNames.take(4).joinToString("، ")}")
                    }
                }
            }
        }
    }
}
