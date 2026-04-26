package oci

import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl

/**
 * Creates a native-specific HttpClient engine using Curl.
 */
actual fun createHttpClient(): HttpClient = HttpClient(Curl)

/**
 * Native-specific URL encoding.
 * Uses a simple implementation for URL encoding on native platforms.
 */
internal actual fun urlEncode(s: String): String {
    val allowed = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_.~".toSet()
    return buildString {
        for (b in s.encodeToByteArray()) {
            val c = b.toInt().toChar()
            if (c in allowed) {
                append(c)
            } else {
                append("%")
                append((b.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0'))
            }
        }
    }
}
