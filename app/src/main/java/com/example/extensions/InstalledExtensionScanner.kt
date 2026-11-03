package com.example.extensions

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/** Only finds packages marked as Tachiyomi/Mihon extensions, and verifies their signing identity. */
data class InstalledExtensionRecord(
    val packageName: String,
    val displayName: String,
    val version: String,
    val libraryVersion: String?,
    val sourceClasses: List<String>,
    val declaredSourceNames: List<String>,
    val decision: ExtensionHostPolicy.Decision,
)

class InstalledExtensionScanner(private val context: Context) {
    @Suppress("DEPRECATION")
    suspend fun scan(snapshot: ExtensionStoreSnapshot?): List<InstalledExtensionRecord> = withContext(Dispatchers.IO) {
        val manager = context.packageManager
        val flags = PackageManager.GET_META_DATA or PackageManager.GET_CONFIGURATIONS or (
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
            else PackageManager.GET_SIGNATURES
        )
        val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else manager.getInstalledPackages(flags)
        val entries = snapshot?.extensions.orEmpty().associateBy { it.packageName }
        packages.asSequence()
            .filter { pkg -> pkg.reqFeatures.orEmpty().any { it.name == ExtensionHostPolicy.FEATURE } }
            .map { pkg ->
                val meta = pkg.applicationInfo?.metaData
                val found = entries[pkg.packageName]
                val sourceClassNames = ExtensionHostPolicy.absoluteSourceClasses(
                    pkg.packageName, meta?.getString(ExtensionHostPolicy.SOURCE_CLASS)
                )
                val lib = ExtensionHostPolicy.normalizedLibVersion(
                    meta?.get(ExtensionHostPolicy.EXTENSION_LIB), pkg.versionName
                )
                val verdict = ExtensionHostPolicy.evaluate(
                    listedInStore = found != null,
                    declaresFeature = true,
                    classes = sourceClassNames,
                    libraryVersion = lib,
                    installedFingerprints = signingFingerprints(pkg),
                    pinnedSigner = snapshot?.signingKeySha256,
                )
                InstalledExtensionRecord(
                    packageName = pkg.packageName,
                    displayName = meta?.getString("tachiyomix.name") ?: found?.name ?: pkg.packageName,
                    version = pkg.versionName.orEmpty(),
                    libraryVersion = lib,
                    sourceClasses = sourceClassNames,
                    declaredSourceNames = found?.sources?.map { it.name }?.distinct().orEmpty(),
                    decision = verdict,
                )
            }.sortedWith(compareByDescending<InstalledExtensionRecord> { it.decision.canInspect }
                .thenBy { it.displayName.lowercase() }).toList()
    }

    @Suppress("DEPRECATION")
    private fun signingFingerprints(pkg: PackageInfo): Set<String> {
        val certs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkg.signingInfo?.apkContentsSigners?.toList().orEmpty()
        } else pkg.signatures?.toList().orEmpty()
        return certs.map { cert ->
            MessageDigest.getInstance("SHA-256").digest(cert.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
        }.toSet()
    }
}
