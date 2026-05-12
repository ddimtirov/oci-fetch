package oci

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

/**
 * Creates a JVM-specific HttpClient engine using CIO.
 */
internal actual fun createHttpClient(): HttpClient = HttpClient(CIO) {
    installOciBearerTokenAuth()
}

/**
 * JVM-specific URL encoding using java.net.URLEncoder.
 */
internal actual fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
