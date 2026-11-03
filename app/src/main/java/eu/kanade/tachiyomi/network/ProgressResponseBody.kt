package eu.kanade.tachiyomi.network

import okhttp3.MediaType
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer

/** Forwards the actual response stream, reporting download progress. */
class ProgressResponseBody(
    private val wrapped: ResponseBody,
    private val listener: ProgressListener,
    private val resumedBytes: Long,
) : ResponseBody() {
    private val stream: BufferedSource by lazy {
        object : ForwardingSource(wrapped.source()) {
            var readSoFar = resumedBytes
            override fun read(sink: Buffer, byteCount: Long): Long {
                val readNow = super.read(sink, byteCount)
                if (readNow > 0) readSoFar += readNow
                val length = wrapped.contentLength().let { if (it >= 0) it + resumedBytes else -1L }
                listener.update(readSoFar, length, readNow == -1L)
                return readNow
            }
        }.buffer()
    }

    override fun contentType(): MediaType? = wrapped.contentType()
    override fun contentLength(): Long = wrapped.contentLength()
    override fun source(): BufferedSource = stream
}
