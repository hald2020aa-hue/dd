@file:Suppress("DEPRECATION")

package eu.kanade.tachiyomi.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import rx.Producer
import rx.Subscription
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Manga D implementation of Mihon extension-host's network helper ABI.
 * This file deliberately generates the JVM class `OkHttpExtensionsKt`.
 * It performs actual OkHttp requests; it is not a placeholder class.
 * Compatible with the suspend call shape used by TachiyomiX/Mihon 1.6.
 */
val jsonMime = "application/json; charset=utf-8".toMediaType()

@Deprecated("Use the suspending await API")
fun Call.asObservable(): Observable<Response> = Observable.unsafeCreate { subscriber ->
    val call = clone() // OkHttp calls are one-shot; each Rx subscriber owns a fresh call.
    val started = AtomicBoolean(false)
    val bridge = object : Producer, Subscription {
        override fun request(n: Long) {
            if (n <= 0 || !started.compareAndSet(false, true)) return
            try {
                val response = call.execute()
                if (subscriber.isUnsubscribed) {
                    response.close()
                } else {
                    subscriber.onNext(response)
                    subscriber.onCompleted()
                }
            } catch (error: Throwable) {
                if (!subscriber.isUnsubscribed) subscriber.onError(error)
            }
        }

        override fun unsubscribe() = call.cancel()
        override fun isUnsubscribed(): Boolean = call.isCanceled()
    }
    subscriber.add(bridge)
    subscriber.setProducer(bridge)
}

@Deprecated("Use the suspending awaitSuccess API")
fun Call.asObservableSuccess(): Observable<Response> = asObservable().doOnNext { response ->
    if (!response.isSuccessful) {
        val code = response.code
        response.close()
        throw HttpException(code)
    }
}

/** Enqueues on OkHttp's dispatcher. Cancelling the coroutine cancels the call. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response)
            else response.close()
        }
    })
}

suspend fun Call.awaitSuccess(): Response {
    val response = await()
    if (!response.isSuccessful) {
        val code = response.code
        response.close()
        throw HttpException(code)
    }
    return response
}

/** Stream progress for image downloads; avoids buffering entire images in memory. */
fun OkHttpClient.newCachelessCallWithProgress(
    request: Request,
    listener: ProgressListener,
    existingSize: Long = 0L,
): Call = newBuilder()
    .cache(null)
    .addNetworkInterceptor { chain ->
        val requestWithRange = chain.request().newBuilder().apply {
            if (existingSize > 0 && chain.request().header("Range") == null) {
                header("Range", "bytes=$existingSize-")
            }
        }.build()
        val response = chain.proceed(requestWithRange)
        val resumedBytes = if (response.code == 206) existingSize else 0L
        response.newBuilder()
            .body(ProgressResponseBody(response.body, listener, resumedBytes))
            .build()
    }.build().newCall(request)
