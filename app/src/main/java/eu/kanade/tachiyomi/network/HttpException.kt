package eu.kanade.tachiyomi.network

/** Mihon 1.6-compatible HTTP status exception. */
class HttpException(val code: Int) : IllegalStateException("HTTP error $code")
