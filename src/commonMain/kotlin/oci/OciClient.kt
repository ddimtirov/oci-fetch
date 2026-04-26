package oci

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
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
class OciClient(private val httpClient: HttpClient = createHttpClient()) : AutoCloseable {
    private val impl = OciClientImpl(httpClient)

    /**
     * Fetches the given URL using OCI-compatible authentication if needed.
     */
    suspend fun fetchUrl(url: String): HttpResponse {
        return impl.fetchUrl(url)
    }

    /**
     * Fetches the manifest for the given image reference.
     */
    suspend fun fetchManifest(image: ImageRef): HttpResponse {
        return impl.fetchManifest(image)
    }

    /**
     * Fetches manifest/index, image manifests, and matching configs.
     * Keeps JSON bodies as text; parses minimally to discover digests.
     */
    suspend fun fetchArtifacts(image: ImageRef): FetchedArtifacts {
        return impl.fetchArtifacts(image)
    }

    /**
     * Fetches tags for the given repository.
     */
    suspend fun fetchTags(repository: String): HttpResponse {
        return impl.fetchTags(repository)
    }

    /**
     * Fetches tags for the given repository and returns them as a list of strings.
     */
    suspend fun fetchTagsList(repository: String): List<String> {
        return impl.fetchTagsList(repository)
    }

    /**
     * Determines if the given content type and JSON body represent an OCI index or manifest list.
     */
    fun isIndexContent(contentType: String, json: JsonObject?): Boolean {
        return impl.isIndexContent(contentType, json)
    }

    /**
     * Determines if the given JSON body represents an OCI manifest.
     */
    fun isManifestContent(json: JsonObject?): Boolean {
        return impl.isManifestContent(json)
    }

    /**
     * Closes the underlying HTTP client.
     */
    override fun close() {
        impl.close()
    }

    /**
     * Resolves an image reference to a specific manifest based on platform constraints.
     * If the reference is an index, it follows it using the selector.
     */
    suspend fun resolveManifest(image: ImageRef, selector: PlatformSelector): ManifestResolution {
        return impl.resolveManifest(image, selector)
    }

    companion object {
        /**
         * Parses an image reference string into an ImageRef.
         * Example: registry-1.docker.io/library/alpine:latest
         */
        fun parseRef(spec: String, defaultTag: String = "latest"): ImageRef {
            return OciClientImpl.parseRef(spec, defaultTag)
        }
    }
}

/**
 * Creates a platform-specific HttpClient engine.
 */
expect fun createHttpClient(): HttpClient

/**
 * Result of manifest resolution.
 */
data class ManifestResolution(
    val body: String,
    val digest: String,
    val contentType: String,
    val imageRef: ImageRef
)

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

    suspend fun fetchUrl(url: String): HttpResponse {
        return authorizedGet(url)
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

    suspend fun fetchTagsList(repository: String): List<String> {
        val response = fetchTags(repository)
        if (response.status != HttpStatusCode.OK) {
            throw Exception("Failed to fetch tags: ${response.status}")
        }
        val body = response.bodyAsText()
        val json = Json.parseToJsonElement(body).jsonObject
        return json["tags"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
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

    fun isManifestContent(json: JsonObject?): Boolean = json != null && (json.containsKey("layers") || json.containsKey("config"))

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

    suspend fun resolveManifest(image: ImageRef, selector: PlatformSelector): ManifestResolution {
        val response = fetchManifest(image)
        if (!response.status.isSuccess()) {
            throw Exception("Failed to fetch manifest: ${response.status}")
        }

        val body = response.bodyAsText()
        val contentType = response.headers[HttpHeaders.ContentType] ?: ""
        val json = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()

        val isIndex = isIndexContent(contentType, json)
        val isManifest = json?.let { it.containsKey("layers") || it.containsKey("config") } ?: false

        if (isIndex && json != null) {
            val manifests = json["manifests"]?.jsonArray
            val matches = manifests?.filter { entry ->
                selector.matches(entry.jsonObject["platform"]?.jsonObject)
            } ?: emptyList()

            if (matches.isEmpty()) {
                throw Exception("No manifest found matching the specified constraints")
            }

            if (matches.size > 1) {
                val options = matches.mapNotNull { entry ->
                    val platform = entry.jsonObject["platform"]?.jsonObject ?: return@mapNotNull null
                    val arch = platform["architecture"]?.jsonPrimitive?.content ?: ""
                    val osName = platform["os"]?.jsonPrimitive?.content ?: ""
                    val osVer = platform["os.version"]?.jsonPrimitive?.content
                    val variantStr = platform["variant"]?.jsonPrimitive?.content
                    val features = platform["os.features"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()

                    if (arch == "unknown" || osName == "unknown") return@mapNotNull null

                    buildString {
                        append("--architecture $arch --os $osName")
                        if (osVer != null) append(" --os-version $osVer")
                        if (variantStr != null) append(" --variant $variantStr")
                        features.forEach { append(" --os-features $it") }
                    }
                }.distinct().joinToString("\n")
                throw Exception("Multiple manifests found matching the specified constraints\n\n$options")
            }

            val selectedEntry = matches[0].jsonObject
            val selectedDigest = selectedEntry["digest"]?.jsonPrimitive?.content
                ?: throw Exception("Selected manifest entry has no digest")

            val manifestRef = image.copy(reference = selectedDigest)
            val manifestResponse = fetchManifest(manifestRef)
            if (!manifestResponse.status.isSuccess()) {
                throw Exception("Failed to fetch matched manifest: ${manifestResponse.status}")
            }

            return ManifestResolution(
                body = manifestResponse.bodyAsText(),
                digest = selectedDigest,
                contentType = manifestResponse.headers[HttpHeaders.ContentType] ?: "",
                imageRef = manifestRef
            )
        } else if (isManifest && json != null) {
            // Already a manifest, check constraints
            val configDigest = json["config"]?.jsonObject?.get("digest")?.jsonPrimitive?.content
            if (selector.hasConstraints()) {
                if (configDigest == null) {
                    throw Exception("Reference points to a manifest but it lacks platform information to verify constraints")
                }
                
                val artifacts = fetchArtifacts(image)
                val configBody = artifacts.configs.firstOrNull()
                if (configBody.isNullOrBlank()) {
                    throw Exception("Could not fetch config to verify platform constraints")
                }
                val configJson = Json.parseToJsonElement(configBody).jsonObject
                if (!selector.matches(configJson)) {
                    throw Exception("Manifest does not match platform constraints")
                }
            }
            
            return ManifestResolution(
                body = body,
                digest = response.headers["Docker-Content-Digest"] ?: configDigest ?: "",
                contentType = contentType,
                imageRef = image
            )
        } else {
            throw Exception("Reference points to neither an index nor a manifest (Content-Type: $contentType)")
        }
    }
}

/**
 * Platform-specific URL encoding function.
 */
internal expect fun urlEncode(s: String): String
