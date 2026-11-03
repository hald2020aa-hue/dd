package com.example.extensions

/**
 * Pure diagnostics for third-party Mihon extension linkage errors.
 * This never guesses that a missing API has been supplied or an extension can run.
 */
object MihonRuntimeDiagnostics {
    private val descriptor = Regex("L([A-Za-z0-9_/$]+);")
    private val quotedClass = Regex("(?:class|Class) \\\"([^\\\"]+)\\\"")
    private val simpleClass = Regex("^[A-Za-z_][A-Za-z0-9_.$/]+$")

    fun missingClass(error: Throwable): String? {
        val detail = error.message.orEmpty()
        val candidate = descriptor.find(detail)?.groupValues?.getOrNull(1)
            ?: quotedClass.find(detail)?.groupValues?.getOrNull(1)
            ?: detail.trim().takeIf { simpleClass.matches(it) }
        return candidate?.replace('/', '.')?.removeSuffix(";")
    }

    fun explain(error: Throwable): String {
        // Linkage errors are not always missing classes. Preserve the method/field
        // signature for ABI mismatches instead of mislabeling them "missing class".
        if (error is NoSuchMethodError || error is AbstractMethodError || error is NoSuchFieldError) {
            return "عدم توافق بين إصدار مكتبات Mihon والملحق: ${error.javaClass.simpleName} — " +
                error.message.orEmpty().take(350)
        }
        if (error is UnsatisfiedLinkError) {
            return "مكتبة تشغيل أصلية غير متوفرة أو غير متوافقة: ${error.message.orEmpty().take(300)}"
        }
        val className = missingClass(error) ?: "غير محددة من رسالة Android"
        val requiredPart = when {
            className.startsWith("eu.kanade.tachiyomi.source.") ->
                "واجهة مصادر Mihon الحقيقية (Source API / HttpSource)"
            className.startsWith("eu.kanade.tachiyomi.network.") ->
                "طبقة الشبكة NetworkHelper وإعدادات المصدر"
            className.startsWith("uy.kohesive.injekt.") ->
                "مكتبة حقن الاعتمادات Injekt ومهيّئها"
            className.startsWith("rx.") -> "RxJava المتوافق مع إصدار الملحق"
            className.startsWith("kotlinx.serialization.") -> "Kotlin Serialization المتوافق"
            className.startsWith("keiyoushi.") -> "مكوّن Keiyoushi يعتمد عليه الملحق"
            else -> "اعتماد تشغيل أو صنف برمجي غير متوفر أو غير متوافق"
        }
        return "المكتبة الناقصة: $className. المطلوب: $requiredPart. " +
            "لا يعني تثبيت الملحق أن محرك Mihon جاهز؛ يلزم توفير تنفيذ متوافق ثم إعادة اختبار البحث والفصول."
    }
}
