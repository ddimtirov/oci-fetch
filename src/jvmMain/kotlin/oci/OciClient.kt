package oci

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.statement.HttpResponse
import kotlinx.serialization.json.JsonObject

/**
 * JVM implementation of OciClient using Ktor CIO HTTP client engine.
 */
actual class OciClient actual constructor() : AutoCloseable {
    private val impl = OciClientImpl(HttpClient(CIO))

    actual suspend fun fetchUrl(url: String): HttpResponse {
        return impl.fetchUrl(url)
    }

    actual suspend fun fetchManifest(image: ImageRef): HttpResponse {
        return impl.fetchManifest(image)
    }

    actual suspend fun fetchArtifacts(image: ImageRef): FetchedArtifacts {
        return impl.fetchArtifacts(image)
    }

    actual suspend fun fetchTags(repository: String): HttpResponse {
        return impl.fetchTags(repository)
    }

    actual suspend fun fetchTagsList(repository: String): List<String> {
        return impl.fetchTagsList(repository)
    }

    actual fun isIndexContent(contentType: String, json: JsonObject?): Boolean {
        return impl.isIndexContent(contentType, json)
    }

    actual fun isManifestContent(json: JsonObject?): Boolean {
        return impl.isManifestContent(json)
    }

    actual override fun close() {
        impl.close()
    }

    actual suspend fun resolveManifest(image: ImageRef, selector: PlatformSelector): ManifestResolution {
        return impl.resolveManifest(image, selector)
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
internal actual fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
