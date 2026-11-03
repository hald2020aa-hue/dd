package okhttp3

/**
 * Binary API compatibility for Keiyoushi builds that referenced compression
 * algorithms in the okhttp3 root package. OkHttp 5's Brotli/Zstd implementations
 * live in okhttp3.brotli and okhttp3.zstd instead.
 *
 * This delegates to the real implementations. It does NOT circumvent network
 * protection, implement challenges, or bypass site access restrictions.
 */
@Suppress("unused")
object Brotli : CompressionInterceptor.DecompressionAlgorithm by okhttp3.brotli.Brotli

@Suppress("unused")
object Zstd : CompressionInterceptor.DecompressionAlgorithm by okhttp3.zstd.Zstd
