package com.example.extensions

import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Narrow reflection adapter for the public Mihon Source/SourceFactory call shape.
 * This does not provide Mihon's host APIs; missing libraries must be reported, not faked.
 * It never loads code on construction: only the explicit user-initiated probe does.
 */
object MihonReflection {
    data class Manga(val title: String, val url: String, val thumbnailUrl: String?, val native: Any)
    data class Chapter(val name: String, val url: String, val native: Any)
    data class Page(val index: Int, val url: String, val imageUrl: String?)

    /** Load only fully-qualified class names verified against the installed package manifest. */
    fun instantiate(classLoader: ClassLoader, classNames: List<String>): List<Any> {
        require(classNames.isNotEmpty() && classNames.size <= 30) { "لا توجد تعريفات مصادر صالحة" }
        val result = ArrayList<Any>()
        for (name in classNames) {
            require(name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+"))) {
                "اسم تعريف المصدر غير صالح"
            }
            val instance = Class.forName(name, true, classLoader).getDeclaredConstructor().newInstance()
            val factory = instance.javaClass.methods.firstOrNull {
                it.name == "createSources" && it.parameterTypes.isEmpty()
            }
            if (factory != null) {
                val sources = unwrap { factory.invoke(instance) } as? List<*>
                    ?: throw IllegalStateException("مصنع الملحق لا يُرجع قائمة مصادر")
                result.addAll(sources.filterNotNull().take(60))
            } else {
                result += instance
            }
        }
        return result.take(60).also { require(it.isNotEmpty()) { "المصدر لم يُنشئ أي مصدر قراءة" } }
    }

    fun sourceName(source: Any): String = try {
        value(source, "getName") as? String ?: source.javaClass.simpleName
    } catch (_: Throwable) { source.javaClass.simpleName }

    fun sourceId(source: Any): String = try {
        value(source, "getId")?.toString().orEmpty()
    } catch (_: Throwable) { "?" }

    suspend fun popular(source: Any): List<Manga> = mangaList(invokeSuspend(source, "getPopularManga", 1))

    suspend fun search(source: Any, query: String): List<Manga> {
        require(query.isNotBlank() && query.length <= 120) { "أدخل اسمًا للبحث" }
        val clazz = source.javaClass.classLoader
        val filterClass = Class.forName("eu.kanade.tachiyomi.source.model.FilterList", true, clazz)
        val filters = try { filterClass.getDeclaredConstructor().newInstance() }
        catch (_: NoSuchMethodException) { value(source, "getFilterList") ?: throw IllegalStateException("تعذر إنشاء مرشحات المصدر") }
        return mangaList(invokeSuspend(source, "getSearchManga", 1, query, filters))
    }

    suspend fun chapters(source: Any, manga: Manga): List<Chapter> {
        val update = invokeSuspend(source, "getMangaUpdate", manga.native, emptyList<Any>(), true, true)
            ?: return emptyList()
        val chapters = value(update, "getChapters") as? List<*> ?: return emptyList()
        return chapters.filterNotNull().take(500).map { chapter ->
            Chapter(value(chapter, "getName")?.toString().orEmpty(),
                value(chapter, "getUrl")?.toString().orEmpty(), chapter)
        }
    }

    suspend fun pages(source: Any, chapter: Chapter): List<Page> {
        val pages = invokeSuspend(source, "getPageList", chapter.native) as? List<*> ?: return emptyList()
        return pages.filterNotNull().take(500).mapIndexed { index, item ->
            Page(index, value(item, "getUrl")?.toString().orEmpty(),
                value(item, "getImageUrl")?.toString())
        }
    }

    private fun mangaList(paged: Any?): List<Manga> {
        val list = paged?.let { value(it, "getMangas") } as? List<*> ?: return emptyList()
        return list.filterNotNull().take(150).map { item ->
            Manga(value(item, "getTitle")?.toString().orEmpty(),
                value(item, "getUrl")?.toString().orEmpty(),
                value(item, "getThumbnail_url")?.toString(), item)
        }
    }

    private fun value(target: Any, method: String): Any? = unwrap {
        target.javaClass.methods.firstOrNull { it.name == method && it.parameterTypes.isEmpty() }
            ?.invoke(target)
    }

    /** Handles Kotlin's Continuation ABI without pretending that stub interfaces are a runtime. */
    private suspend fun invokeSuspend(target: Any, name: String, vararg parameters: Any): Any? =
        suspendCoroutine { continuation ->
            val method = target.javaClass.methods.firstOrNull { method ->
                method.name == name && method.parameterTypes.size == parameters.size + 1 &&
                    Continuation::class.java.isAssignableFrom(method.parameterTypes.last())
            }
            if (method == null) {
                continuation.resumeWithException(UnsupportedOperationException("الملحق لا يوفر واجهة $name المتوافقة"))
                return@suspendCoroutine
            }
            val bridge = object : Continuation<Any?> {
                override val context = continuation.context
                override fun resumeWith(result: Result<Any?>) { continuation.resumeWith(result) }
            }
            try {
                val result = method.invoke(target, *parameters, bridge)
                if (result !== COROUTINE_SUSPENDED) continuation.resume(result)
            } catch (e: InvocationTargetException) {
                continuation.resumeWithException(e.targetException ?: e)
            } catch (e: Throwable) {
                continuation.resumeWithException(e)
            }
        }

    private inline fun <T> unwrap(block: () -> T): T = try { block() }
    catch (e: InvocationTargetException) { throw (e.targetException ?: e) }

    fun rootCauseMessage(error: Throwable): String {
        var last = error
        val seen = HashSet<Throwable>()
        while (last.cause != null && seen.add(last)) last = last.cause!!
        val name = last.javaClass.simpleName
        val detail = last.message.orEmpty().take(280)
        return if (last is LinkageError || last is ClassNotFoundException) {
            "مكتبات تشغيل Mihon غير مكتملة أو غير متوافقة: $name — $detail"
        } else "$name — $detail"
    }
}
