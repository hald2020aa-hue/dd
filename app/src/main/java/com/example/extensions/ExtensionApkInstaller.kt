package com.example.extensions

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/** Explicit user-controlled APK installer with pinned repo signing identity verification. */
class ExtensionApkInstaller(private val context: Context) {
    private val dnsGuard = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            com.example.sources.PublicNetworkGuard.filterPublic(Dns.SYSTEM.lookup(hostname))
    }
    private val client = OkHttpClient.Builder().dns(dnsGuard)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS).build()

    /** Never claim a package is installed just because the Android installer was opened. */
    suspend fun downloadVerified(entry: ExtensionEntry, store: ExtensionStoreSnapshot): File =
        withContext(Dispatchers.IO) {
            val pinned = store.signingKeySha256 ?: throw SecurityException(
                "هذا المستودع لا ينشر بصمة توقيع موثوقة؛ لن يتم تثبيت APK منه"
            )
            require(ExtensionStoreCodec.isExtensionPackage(entry.packageName)) { "اسم الحزمة غير صالح" }
            require(ExtensionStoreCodec.isHttpsUrl(entry.apkUrl)) { "رابط APK غير صالح" }
            val dir = File(context.cacheDir, "manga_d_extensions").apply { mkdirs() }
            val file = File(dir, "${entry.packageName}_${entry.versionCode}.apk")
            val partial = File(dir, "${entry.packageName}_${entry.versionCode}.part")
            try {
                streamApk(entry.apkUrl, partial)
                validateArchive(partial, entry, pinned)
                if (file.exists()) file.delete()
                if (!partial.renameTo(file)) throw IOException("تعذر إنهاء تنزيل APK")
                file
            } catch (e: Exception) {
                partial.delete()
                throw e
            }
        }

    private fun streamApk(url: String, destination: File) {
        var target = url
        repeat(6) { hop ->
            require(ExtensionStoreCodec.isHttpsUrl(target) && com.example.sources.isSafePublicUrl(target)) {
                "محاولة تنزيل من عنوان غير آمن"
            }
            client.newCall(Request.Builder().url(target).build()).execute().use { response ->
                if (response.code in 300..399) {
                    if (hop >= 5) throw IOException("عدد تحويلات التحميل كبير")
                    target = URI(target).resolve(response.header("Location") ?: throw IOException("تحويل فارغ")).toString()
                    return@use
                }
                if (!response.isSuccessful) throw IOException("فشل تنزيل الملحق HTTP ${response.code}")
                if (response.body?.contentLength() ?: 0L > 60L * 1024L * 1024L)
                    throw IOException("الملحق كبير جدًا")
                response.body?.byteStream()?.use { input ->
                    destination.outputStream().buffered().use { output ->
                        var total = 0L
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > 60L * 1024 * 1024) throw IOException("تجاوز حجم APK المسموح")
                            output.write(buf, 0, n)
                        }
                    }
                } ?: throw IOException("ملف APK فارغ")
                return
            }
        }
        throw IOException("تعذر تنزيل الملحق")
    }

    @Suppress("DEPRECATION")
    private fun validateArchive(file: File, entry: ExtensionEntry, expectedFingerprint: String) {
        if (file.length() < 100L) throw IOException("ملف APK غير مكتمل")
        ZipFile(file).use { zip ->
            if (zip.getEntry("AndroidManifest.xml") == null || zip.getEntry("classes.dex") == null)
                throw SecurityException("الملف ليس حزمة Android قابلة للتحقق")
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw SecurityException("لم يتمكن Android من قراءة توقيع الحزمة")
        if (info.packageName != entry.packageName)
            throw SecurityException("اسم APK يختلف عن اسم الحزمة المنشورة في المستودع")
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
            else info.versionCode.toLong()
        if (entry.versionCode > 0 && code != entry.versionCode)
            throw SecurityException("إصدار APK يختلف عن الإصدار المنشور")
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            info.signingInfo?.apkContentsSigners?.toList().orEmpty()
        else info.signatures?.toList().orEmpty()
        if (signatures.isEmpty()) throw SecurityException("الملحق غير موقّع")
        val digests = signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        if (digests.none { it.equals(expectedFingerprint, ignoreCase = true) })
            throw SecurityException("شهادة توقيع APK لا تطابق بصمة المستودع؛ تم منع التثبيت")
    }

    fun requestUserInstall(activity: Activity, apkFile: File): Boolean {
        // User must explicitly enable installing unknown apps for this host if required.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()) {
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}"))
            activity.startActivity(settings)
            return false
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.extensions.provider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(intent)
        return true
    }
}
