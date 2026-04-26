package oci

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Minimal OCI Registry client for fetching image manifests.
 * Handles Bearer token challenge (WWW-Authenticate) and retries the request.
 * 
 * Multiplatform implementation supporting JVM, JS, Native (Windows/Linux), and WASM.
 */
expect class OciClient() {
    /**
     * Fetches the manifest for the given image reference.
     */
    suspend fun fetchManifest(image: ImageRef): HttpResponse

    /**
     * Fetches manifest/index, image manifests, and matching configs.
     * Keeps JSON bodies as text; parses minimally to discover digests.
     */
    suspend fun fetchArtifacts(image: ImageRef): FetchedArtifacts

    /**
     * Fetches tags for the given repository.
     */
    suspend fun fetchTags(repository: String): HttpResponse

    /**
     * Determines if the given content type and JSON body represent an OCI index or manifest list.
     */
    fun isIndexContent(contentType: String, json: JsonObject?): Boolean

    /**
     * Closes the underlying HTTP client.
     */
    fun close()

    companion object {
        /**
         * Parses an image reference string into an ImageRef.
         * Example: registry-1.docker.io/library/alpine:latest
         */
        fun parseRef(spec: String, defaultTag: String = "latest"): ImageRef
    }
}

/**
 * Image reference containing registry, repository, and tag/digest.
 */
data class ImageRef(
    val registry: String,
    val repository: String,
    val reference: String = "latest"
)

/**
 * Fetched artifacts for an image reference. JSON blobs are kept as raw text.
 * - listManifest: the manifest list / index JSON if present, otherwise null
 * - imageManifests: list of image manifest JSONs (one per platform if index, or single if not)
 * - configs: list of corresponding config JSONs in the same order as imageManifests
 */
data class FetchedArtifacts(
    val listManifest: String?,
    val imageManifests: List<String>,
    val configs: List<String>
)

/**
 * Common implementation logic for OciClient.
 */
internal class OciClientImpl(private val client: HttpClient) {
    companion object {
        // Accept both Docker and OCI manifest and index media types
        val acceptManifest = listOf(
            "application/vnd.oci.image.manifest.v1+json",
            "application/vnd.docker.distribution.manifest.v2+json",
            "application/vnd.oci.image.index.v1+json",
            "application/vnd.docker.distribution.manifest.list.v2+json",
        ).joinToString(",")

        val acceptConfig = listOf(
            "application/vnd.oci.image.config.v1+json",
            "application/vnd.docker.container.image.v1+json",
            "application/octet-stream",
        ).joinToString(",")

        fun parseRef(spec: String, defaultTag: String = "latest"): ImageRef {
            // input like: registry-1.docker.io/library/alpine
            val parts = spec.split('/')
            require(parts.size >= 2) { "Invalid image reference: $spec" }
            val registry = parts.first()
            val repo = parts.drop(1).joinToString("/")
            val repoParts = repo.split('@', ':')
            return when {
                '@' in repo -> {
                    val (r, digest) = repo.split('@', limit = 2)
                    ImageRef(registry, r, digest)
                }
                ':' in repo -> {
                    val idx = repo.lastIndexOf(':')
                    val r = repo.substring(0, idx)
                    val tag = repo.substring(idx + 1)
                    ImageRef(registry, r, tag)
                }
                else -> ImageRef(registry, repo, defaultTag)
            }
        }
    }

    suspend fun fetchManifest(image: ImageRef): HttpResponse {
        val url = "https://${image.registry}/v2/${image.repository}/manifests/${image.reference}"
        return authorizedGet(url, acceptManifest)
    }

    suspend fun fetchTags(repository: String): HttpResponse {
        // repository is like "library/alpine"
        // registry is needed, so maybe we should pass ImageRef or just the full spec
        val parts = repository.split('/', limit = 2)
        val registry = parts[0]
        val name = parts.getOrNull(1) ?: ""
        val url = "https://$registry/v2/$name/tags/list"
        return authorizedGet(url)
    }

    suspend fun authorizedGet(url: String, acceptHeader: String? = null): HttpResponse {
        val initial = client.get(url) {
            headers {
                if (acceptHeader != null) header(HttpHeaders.Accept, acceptHeader)
            }
        }
        if (initial.status != HttpStatusCode.Unauthorized) return initial

        val www = initial.headers[HttpHeaders.WWWAuthenticate] ?: return initial
        val challenge = parseWwwAuthenticate(www)
        if (!challenge.scheme.equals("Bearer", ignoreCase = true)) return initial

        val token = fetchBearerToken(challenge.params) ?: return initial
        return client.get(url) {
            headers {
                if (acceptHeader != null) header(HttpHeaders.Accept, acceptHeader)
                header(HttpHeaders.Authorization, "Bearer $token")
            }
        }
    }

    suspend fun fetchBlob(registry: String, repository: String, digest: String): HttpResponse {
        val url = "https://${registry}/v2/${repository}/blobs/${digest}"
        return authorizedGet(url, acceptConfig)
    }

    suspend fun fetchArtifacts(image: ImageRef): FetchedArtifacts {
        val topResp = fetchManifest(image)
        val topBody = topResp.bodyAsText()
        val contentType = topResp.headers[HttpHeaders.ContentType] ?: ""

        // Determine if top-level is an index (manifest list) or a single image manifest
        val topJson = runCatching { Json.parseToJsonElement(topBody).jsonObject }.getOrNull()
        val isIndex = isIndexContent(contentType, topJson)

        return if (isIndex && topJson != null) {
            val manifestsArray = topJson["manifests"]?.jsonArray ?: emptyList()
            val imageManifests = mutableListOf<String>()
            val configs = mutableListOf<String>()

            // For each entry, fetch the image manifest by digest
            for (entry in manifestsArray) {
                val digest = runCatching { entry.jsonObject["digest"]?.jsonPrimitive?.content }
                    .getOrNull()
                if (digest.isNullOrBlank()) continue
                val mResp = authorizedGet("https://${image.registry}/v2/${image.repository}/manifests/${digest}", acceptManifest)
                val mBody = mResp.bodyAsText()
                imageManifests.add(mBody)
                // Parse config digest and fetch config blob
                val mJson = runCatching { Json.parseToJsonElement(mBody).jsonObject }.getOrNull()
                val cfgDigest = runCatching { mJson?.get("config")?.jsonObject?.get("digest")?.jsonPrimitive?.content }
                    .getOrNull()
                if (!cfgDigest.isNullOrBlank()) {
                    val cResp = fetchBlob(image.registry, image.repository, cfgDigest)
                    val cBody = cResp.bodyAsText()
                    configs.add(cBody)
                } else {
                    // keep positions aligned; add empty string if config missing
                    configs.add("")
                }
            }
            FetchedArtifacts(listManifest = topBody, imageManifests = imageManifests, configs = configs)
        } else {
            // Single image manifest path
            val imageManifestBody = topBody
            val imageManifests = listOf(imageManifestBody)
            val mJson = runCatching { Json.parseToJsonElement(imageManifestBody).jsonObject }.getOrNull()
            val cfgDigest = runCatching { mJson?.get("config")?.jsonObject?.get("digest")?.jsonPrimitive?.content }
                .getOrNull()
            val configs = if (!cfgDigest.isNullOrBlank()) {
                val cResp = fetchBlob(image.registry, image.repository, cfgDigest)
                listOf(cResp.bodyAsText())
            } else emptyList()
            FetchedArtifacts(listManifest = null, imageManifests = imageManifests, configs = configs)
        }
    }

    fun isIndexContent(contentType: String, json: JsonObject?): Boolean {
        val ct = contentType.lowercase()
        if (ct.contains("application/vnd.oci.image.index.v1+json")) return true
        if (ct.contains("application/vnd.docker.distribution.manifest.list.v2+json")) return true
        // Fallback to structure check
        val hasManifests = json?.get("manifests") is kotlinx.serialization.json.JsonArray
        return hasManifests
    }

    suspend fun fetchBearerToken(params: Map<String, String>): String? {
        val realm = params["realm"] ?: return null
        // Preserve service and scope; commonly included in the 401 challenge
        val service = params["service"]
        val scope = params["scope"]
        val query = buildList {
            if (service != null) add("service=" + urlEncode(service))
            if (scope != null) add("scope=" + urlEncode(scope))
        }.joinToString("&")
        val url = if (query.isNotEmpty()) "$realm?$query" else realm

        val resp = client.get(url)
        if (resp.status != HttpStatusCode.OK) return null
        val body = resp.bodyAsText()
        val json = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val token = json["token"]?.jsonPrimitive?.content
        val access = json["access_token"]?.jsonPrimitive?.content
        return token ?: access
    }

    fun parseWwwAuthenticate(header: String): Challenge {
        // Example: Bearer realm="https://auth.docker.io/token",service="registry.docker.io",scope="repository:library/alpine:pull"
        val parts = header.split(" ", limit = 2)
        val scheme = parts.firstOrNull()?.trim() ?: ""
        val paramStr = parts.getOrNull(1) ?: ""
        val params = mutableMapOf<String, String>()
        for (segment in paramStr.split(',')) {
            val kv = segment.split('=', limit = 2)
            if (kv.size == 2) {
                val key = kv[0].trim().trim('"')
                val value = kv[1].trim().trim('"')
                if (key.isNotEmpty()) params[key] = value
            }
        }
        return Challenge(scheme, params)
    }

    data class Challenge(val scheme: String, val params: Map<String, String>)

    fun close() {
        client.close()
    }
}

/**
 * Platform-specific URL encoding function.
 */
internal expect fun urlEncode(s: String): String
