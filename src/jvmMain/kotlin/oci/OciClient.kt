package oci

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.statement.HttpResponse

/**
 * JVM implementation of OciClient using Java HTTP client engine.
 */
actual class OciClient actual constructor() {
    private val impl = OciClientImpl(HttpClient(Java))

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
 * JVM-specific URL encoding using java.net.URLEncoder.
 */
internal actual fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, Charsets.UTF_8)
