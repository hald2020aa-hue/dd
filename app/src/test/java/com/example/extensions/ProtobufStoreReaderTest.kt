package com.example.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class ProtobufStoreReaderTest {
    private fun varint(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var x = value
        while (x and -128L != 0L) { out.write(((x and 127) or 128).toInt()); x = x ushr 7 }
        out.write(x.toInt())
        return out.toByteArray()
    }
    private fun field(id: Int, body: ByteArray): ByteArray =
        varint((id * 8 + 2).toLong()) + varint(body.size.toLong()) + body
    private fun string(id: Int, value: String) = field(id, value.toByteArray())
    private fun number(id: Int, value: Long) = varint((id * 8).toLong()) + varint(value)

    @Test fun parsesV2ProtoIndex() {
        val sources = field(8, number(1, 42) + string(2, "Sample") + string(3, "en") + string(4, "https://example.org"))
        val resources = field(3, string(1, "https://example.org/test.apk"))
        val ext = field(1, string(1, "Sample") + string(2, "eu.kanade.tachiyomi.extension.en.sample") +
            resources + string(4, "1.6") + number(5, 106005) + string(6, "1.6.5") + number(7, 1) + sources)
        val blob = string(1, "Demo") + string(3, "a".repeat(64)) + field(101, ext)
        val result = ProtobufStoreReader.decode(blob, "https://example.org/index.pb")
        assertEquals("Demo", result.name)
        assertEquals(1, result.extensions.size)
        assertEquals(106005L, result.extensions.first().versionCode)
        assertEquals("Sample", result.extensions.first().sources.first().name)
        assertTrue(result.format.contains("Protobuf"))
    }
}
