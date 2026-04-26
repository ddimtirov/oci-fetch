package oci

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.statement.HttpResponse
import kotlinx.serialization.json.JsonObject

/**
 * JavaScript implementation of OciClient using JS fetch API.
 */
actual class OciClient actual constructor() {
    private val impl = OciClientImpl(HttpClient(Js))

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

    actual fun isIndexContent(contentType: String, json: JsonObject?): Boolean {
        return impl.isIndexContent(contentType, json)
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
 * JS-specific URL encoding using encodeURIComponent.
 */
internal actual fun urlEncode(s: String): String = js("encodeURIComponent(s)") as String
