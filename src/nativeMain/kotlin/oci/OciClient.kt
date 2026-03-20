package oci

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.statement.HttpResponse

/**
 * Native implementation of OciClient using Ktor CIO engine.
 * Supports Windows (mingwX64) and Linux (linuxX64) targets.
 */
actual class OciClient actual constructor() {
    private val impl = OciClientImpl(HttpClient(CIO))

    actual suspend fun fetchManifest(image: ImageRef): HttpResponse {
        return impl.fetchManifest(image)
    }

    actual suspend fun fetchArtifacts(image: ImageRef): FetchedArtifacts {
        return impl.fetchArtifacts(image)
    }

    actual fun close() {
        impl.close()
    }

    actual companion object {
        actual fun parseRef(spec: String, defaultTag: String): ImageRef {
            return OciClientImpl.parseRef(spec, defaultTag)
        }
    }
}

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
