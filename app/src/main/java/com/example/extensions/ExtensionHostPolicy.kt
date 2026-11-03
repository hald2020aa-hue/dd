package com.example.extensions

/** Pure decision logic; deliberately does not execute code from third-party APKs. */
object ExtensionHostPolicy {
    const val FEATURE = "tachiyomi.extension"
    const val SOURCE_CLASS = "tachiyomi.extension.class"
    const val EXTENSION_LIB = "tachiyomix.extensionLib"

    enum class State { VERIFIED, UNLISTED, MISSING_SIGNATURE, SIGNATURE_MISMATCH, UNSUPPORTED_LIB, MALFORMED }

    data class Decision(val state: State, val reason: String) {
        val canInspect: Boolean get() = state == State.VERIFIED
        // Verification alone NEVER grants permission to execute the APK.
        val canExecute: Boolean get() = false
    }

    fun absoluteSourceClasses(packageName: String, raw: String?): List<String> = raw.orEmpty()
        .split(';')
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .map { if (it.startsWith('.')) packageName + it else it }
        .filter { it.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_$][a-zA-Z0-9_$]*)+")) }
        .distinct()

    fun evaluate(
        listedInStore: Boolean,
        declaresFeature: Boolean,
        classes: List<String>,
        libraryVersion: String?,
        installedFingerprints: Set<String>,
        pinnedSigner: String?,
    ): Decision {
        if (!declaresFeature || classes.isEmpty())
            return Decision(State.MALFORMED, "ملف الملحق لا يعلن واجهة المصادر المطلوبة")
        if (libraryVersion !in setOf("1.4", "1.6"))
            return Decision(State.UNSUPPORTED_LIB, "واجهة المكتبة ${libraryVersion ?: "غير معروفة"} غير مدعومة")
        if (!listedInStore || pinnedSigner == null)
            return Decision(State.UNLISTED, "الملحق ليس في فهرس موثوق محمّل داخل التطبيق")
        if (installedFingerprints.isEmpty())
            return Decision(State.MISSING_SIGNATURE, "لم يتمكن النظام من قراءة شهادة توقيع الملحق")
        val normalized = installedFingerprints.map { it.lowercase() }.toSet()
        val pinned = pinnedSigner.lowercase()
        if (normalized.size != 1 || pinned !in normalized)
            return Decision(State.SIGNATURE_MISMATCH, "توقيع الملحق لا يطابق توقيع المستودع")
        return Decision(State.VERIFIED, "التوقيع والبيانات متطابقة، لكن محرك التنفيذ لم يُفعّل بعد")
    }

    fun normalizedLibVersion(value: Any?, fallbackVersionName: String?): String? {
        val raw = when (value) {
            is Number -> value.toString()
            is String -> value
            else -> fallbackVersionName?.substringBeforeLast('.')
        } ?: return null
        val found = Regex("^(1\\.[0-9]+)").find(raw.trim()) ?: return null
        return found.groupValues[1]
    }
}
