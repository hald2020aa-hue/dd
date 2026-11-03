package com.example.extensions

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream

/** A bounded reader for Mihon's protobuf ExtensionStore format; skips unknown fields. */
object ProtobufStoreReader {
    private class Wire(private val b: ByteArray) {
        data class Value(val number: Int, val bytes: ByteArray? = null, val numberValue: Long? = null) {
            fun text() = bytes?.toString(Charsets.UTF_8).orEmpty()
        }
        private var pos = 0
        private fun varint(): Long {
            var value = 0L
            for (i in 0..9) {
                if (pos >= b.size) throw IOException("Truncated protobuf")
                val c = b[pos++].toInt() and 255
                if (i == 9 && c > 1) throw IOException("Invalid protobuf varint")
                value = value or ((c and 127).toLong() shl (i * 7))
                if ((c and 128) == 0) return value
            }
            throw IOException("Invalid protobuf varint")
        }
        fun fields(): List<Value> {
            val out = ArrayList<Value>()
            while (pos < b.size) {
                val key = varint()
                val number = (key ushr 3).toInt()
                if (number <= 0) throw IOException("Invalid protobuf field")
                when ((key and 7L).toInt()) {
                    0 -> out += Value(number, numberValue = varint())
                    1 -> { skip(8) }
                    2 -> {
                        val length = varint()
                        if (length > Int.MAX_VALUE || length < 0 || length > b.size - pos)
                            throw IOException("Invalid protobuf length")
                        out += Value(number, bytes = b.copyOfRange(pos, pos + length.toInt()))
                        pos += length.toInt()
                    }
                    5 -> skip(4)
                    else -> throw IOException("Unsupported protobuf wire type")
                }
                if (out.size > 50_000) throw IOException("Too many protobuf fields")
            }
            return out
        }
        private fun skip(n: Int) {
            if (pos + n > b.size) throw IOException("Truncated protobuf")
            pos += n
        }
    }
    private fun List<Wire.Value>.one(id: Int) = firstOrNull { it.number == id }
    private fun List<Wire.Value>.all(id: Int) = filter { it.number == id }
    private fun Wire.Value?.child(): List<Wire.Value> = this?.bytes?.let { Wire(it).fields() } ?: emptyList()

    fun decode(raw: ByteArray, url: String): ExtensionStoreSnapshot {
        require(raw.size <= 16_000_000) { "Index exceeds 16 MB" }
        val bytes = if (raw.size >= 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) {
            GZIPInputStream(ByteArrayInputStream(raw)).use { gz ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val count = gz.read(buf)
                    if (count < 0) break
                    if (out.size() + count > 16_000_000) throw IOException("GZIP index too large")
                    out.write(buf, 0, count)
                }
                out.toByteArray()
            }
        } else raw
        val root = Wire(bytes).fields()
        val signed = ExtensionStoreCodec.parseFingerprint(root.one(3)?.text())
        val nested = root.one(101).child()
        val extensions = nested.all(1).ifEmpty { root.all(5) }
        val entries = ArrayList<ExtensionEntry>()
        for (value in extensions.take(12_000)) {
            val f = value.child()
            val pkg = f.one(2)?.text().orEmpty()
            val resource = f.one(3).child()
            val apk = resource.one(1)?.text().orEmpty()
            if (!ExtensionStoreCodec.isExtensionPackage(pkg) || !ExtensionStoreCodec.isHttpsUrl(apk)) continue
            val sources = f.all(8).take(100).map { sf ->
                val s = sf.child()
                ExtensionSourceInfo(
                    id = s.one(1)?.numberValue?.toString().orEmpty(),
                    name = s.one(2)?.text().orEmpty(),
                    language = s.one(3)?.text().orEmpty().ifEmpty { "all" },
                    homeUrl = s.one(4)?.text().orEmpty()
                )
            }
            val languages = sources.map { it.language }.distinct()
            entries += ExtensionEntry(
                name = f.one(1)?.text().orEmpty().ifEmpty { pkg },
                packageName = pkg,
                versionName = f.one(6)?.text().orEmpty(),
                versionCode = f.one(5)?.numberValue ?: 0L,
                language = if (languages.size == 1) languages.first() else "all",
                apkUrl = apk,
                contentRating = (f.one(7)?.numberValue ?: 1L).toInt(),
                sources = sources
            )
        }
        require(entries.isNotEmpty()) { "No compatible extensions in this protobuf store" }
        return ExtensionStoreSnapshot(
            root.one(1)?.text().orEmpty().ifBlank { "Extension Store" }, url,
            signed, entries.distinctBy { it.packageName }, "Mihon v2 Protobuf"
        )
    }
}
