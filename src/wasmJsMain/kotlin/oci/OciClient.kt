package oci

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.statement.HttpResponse

/**
 * WASM-JS implementation of OciClient using JS fetch API.
 */
actual class OciClient actual constructor() {
    private val impl = OciClientImpl(HttpClient(Js))

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
 * WASM-JS-specific URL encoding using encodeURIComponent.
 */
@JsFun("(s) => encodeURIComponent(s)")
private external fun encodeURIComponentWasm(s: String): String

internal actual fun urlEncode(s: String): String = encodeURIComponentWasm(s)
