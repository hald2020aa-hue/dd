package com.example.extensions

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dalvik.system.DexClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Experimental, explicitly user-triggered loader for a single verified extension.
 * This is NOT a complete Mihon API implementation or a security sandbox.
 * Unsatisfied runtime dependencies are surfaced as errors; no false success fallback.
 */
class MihonRuntimeController(private val context: Context) {
    data class Session(
        val packageName: String,
        val sources: List<Any>,
    )

    @Suppress("DEPRECATION")
    suspend fun openVerified(packageName: String): Session = withContext(Dispatchers.IO) {
        val snapshot = ExtensionStoreRepository(context).cached()
            ?: throw SecurityException("حمّل فهرس المستودع الموثوق أولًا")
        val approved = InstalledExtensionScanner(context).scan(snapshot)
            .firstOrNull { it.packageName == packageName }
            ?: throw SecurityException("الملحق غير مثبت أو غير قابل للفحص")
        if (!approved.decision.canInspect) throw SecurityException(approved.decision.reason)
        require(approved.libraryVersion == "1.6") { "اختبار التشغيل v6 مخصص لواجهة Mihon 1.6" }

        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 33) {
            pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
        } else pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
        val apkPath = flags.sourceDir ?: throw IllegalStateException("تعذر تحديد APK المثبت")
        // The installed package is re-verified immediately before reading its code path.
        val rechecked = InstalledExtensionScanner(context).scan(snapshot)
            .firstOrNull { it.packageName == packageName && it.decision.canInspect }
            ?: throw SecurityException("تغيرت حالة الثقة بالملحق؛ أعد الفحص")
        require(rechecked.version == approved.version && rechecked.sourceClasses == approved.sourceClasses) {
            "تغير إصدار الملحق أثناء محاولة تشغيله"
        }

        val classLoader = DexClassLoader(
            apkPath, context.codeCacheDir.absolutePath, flags.nativeLibraryDir, context.classLoader
        )
        val sources = MihonReflection.instantiate(classLoader, rechecked.sourceClasses)
        Session(packageName, sources)
    }
}
