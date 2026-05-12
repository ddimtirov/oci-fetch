package oci

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.kotlincrypto.hash.sha2.SHA256

/**
 * Common implementation logic for OciClient.
 */
internal class OciClientImpl(private val client: HttpClient, val externallyManaged: Boolean) : OciClient {
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

        private val nextRelRegex = Regex("""\brel\s*=\s*"?next"?""", RegexOption.IGNORE_CASE)
    }

    override suspend fun requestUrl(url: String, acceptHeader: String?): HttpResponse {
        val initialResponse = client.get(url) {
            headers {
                acceptHeader?.let { header(HttpHeaders.Accept, it) }
            }
        }
        if (initialResponse.status != HttpStatusCode.Unauthorized) return initialResponse

        // Example: Bearer realm="https://auth.docker.io/token",service="registry.docker.io",scope="repository:library/alpine:pull"
        val wwwAuthenticateHeader = initialResponse.headers[HttpHeaders.WWWAuthenticate]
        checkNotNull(wwwAuthenticateHeader) { "WWW-Authenticate header is missing" }

        val tokenUrl = bearerTokenUrl(wwwAuthenticateHeader)
        val tokenResponse = client.get(tokenUrl)
        check(tokenResponse.status == HttpStatusCode.OK) { "Failed to fetch token: ${tokenResponse.status}" }

        val json = Json.parseToJsonElement(tokenResponse.bodyAsText()).jsonObject
        val token = (json["token"] ?: json["access_token"])?.jsonPrimitive?.content
        checkNotNull(token) { "Token is missing in response $tokenResponse" }

        return client.get(url) {
            headers {
                if (acceptHeader != null) header(HttpHeaders.Accept, acceptHeader)
                header(HttpHeaders.Authorization, "Bearer $token")
            }
        }
    }

    override suspend fun requestBlob(registry: String, repository: String, digest: String): HttpResponse {
        val url = "https://${registry}/v2/${repository}/blobs/${digest}"
        return requestUrl(url, acceptConfig)
    }

    override suspend fun requestBlob(image: OciRef): HttpResponse {
        check(image.isDigest) { "ImageRef must be a digest: $image" }
        return requestBlob(image.registry, image.repository, image.reference)
    }

    override suspend fun requestTags(registry: String, repository: String): HttpResponse {
        val url = "https://$registry/v2/$repository/tags/list"
        return requestUrl(url)
    }

    override suspend fun requestTags(image: OciRef): HttpResponse =
        requestTags(image.registry, image.repository)

    override suspend fun fetchTagsList(image: OciRef): List<String> =
        fetchTagsList(image.registry, image.repository)

    override suspend fun fetchTagsList(registry: String, repository: String): List<String> {
        val initialUrl = "https://$registry/v2/$repository/tags/list"
        val tags = mutableListOf<String>()
        val visitedPages = mutableSetOf<String>()
        var nextPageUrl: String? = initialUrl

        while (nextPageUrl != null) {
            check(visitedPages.add(nextPageUrl)) {
                "Detected tags pagination loop at $nextPageUrl"
            }
            val response = requestUrl(nextPageUrl)
            tags += run {
                check(response.status == HttpStatusCode.OK) { "Failed to fetch tags: ${response.status}" }
                val body = response.bodyAsText()
                val json = Json.parseToJsonElement(body).jsonObject.rethrowErrors()
                json["tags"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            }
            nextPageUrl = nextPageUrl(response)?.let {
                when {
                    it.startsWith("https://") || it.startsWith("http://") -> it
                    it.startsWith("/") -> "https://$registry$it"
                    it.startsWith("v2/") -> "https://$registry/$it"
                    it.startsWith("?") -> "https://$registry/v2/$repository/tags/list$it"
                    else -> error("Unsupported pagination URL in tags Link header: $it")
                }
            }
        }
        return tags.distinct()
    }


    private fun nextPageUrl(response: HttpResponse): String? {
        val linkHeaders = response.headers.getAll(HttpHeaders.Link) ?: return null

        val linkHeader = linkHeaders
            .flatMap { it.split(',') }
            .map { it.trim() }
            .firstOrNull { nextRelRegex.containsMatchIn(it) }
            ?: return null

        val rawUrl = linkHeader.substringBefore(';').trim()
        check(rawUrl.startsWith("<") && rawUrl.endsWith(">")) {
            "Malformed tags pagination Link header: $linkHeader"
        }

        return rawUrl.removePrefix("<").removeSuffix(">")
    }

    override suspend fun requestManifest(image: OciRef): HttpResponse =
        requestUrl("https://${image.registry}/v2/${image.repository}/manifests/${image.reference}", acceptManifest)

    override suspend fun requestManifest(registry: String, repository: String, tag: String): HttpResponse =
        requestUrl("https://$registry/v2/$repository/manifests/$tag", acceptManifest)

    override suspend fun fetchAllMetadata(image: OciRef): ImageIndexArtifacts {
        val topResp = requestManifest(image)
        val topBody = topResp.bodyAsText()
        val contentType = topResp.headers[HttpHeaders.ContentType] ?: ""

        // Determine if top-level is an index (manifest list) or a single image manifest
        val topJson = Json.parseToJsonElement(topBody).jsonObject.rethrowErrors()
        val indexContent = isOciImageIndex(topJson, contentType)

        return if (indexContent) {
            val listManifests = topJson["manifests"]?.jsonArray ?: emptyList()
            val images = listManifests
                .mapNotNull { it.jsonObject["digest"]?.jsonPrimitive?.content }
                .map { digest ->
                    val manifestRef = image.withDigest(digest)
                    val manifestResponse = requestManifest(manifestRef)
                    check(manifestResponse.status == HttpStatusCode.OK) { "Failed to fetch manifest: ${manifestResponse.status}" }
                    val manifestJson = Json.parseToJsonElement(manifestResponse.bodyAsText()).jsonObject

                    val configDigest = manifestJson["config"]?.jsonObject?.get("digest")?.jsonPrimitive?.content
                    val configJson = if (configDigest != null) {
                        val configResponse = requestBlob(manifestRef.withDigest(configDigest))
                        check(configResponse.status == HttpStatusCode.OK) { "Failed to fetch config blob: ${configResponse.status}" }
                        Json.parseToJsonElement(configResponse.bodyAsText()).jsonObject.rethrowErrors()
                    } else {
                        null
                    }
                    ImageArtifacts(manifestRef, manifestJson, configJson)
                }

            ImageIndexArtifacts(image, index = topJson, images)
        } else {
            // topJson is a single image manifest, fetch config blob if present
            val configDigest = topJson["config"]?.jsonObject?.get("digest")?.jsonPrimitive?.content
            val config = configDigest?.let { digest ->
                val configResponse = requestBlob(image.registry, image.repository, digest)
                check(configResponse.status == HttpStatusCode.OK) { "Failed to fetch config blob: ${configResponse.status}" }
                Json.parseToJsonElement(configResponse.bodyAsText()).jsonObject.rethrowErrors()
            }
            ImageIndexArtifacts(image, index = null, listOf(ImageArtifacts(image, topJson, config)))
        }
    }

    override suspend fun fetchReferrers(subject: OciRef, artifactType: String?): JsonObject =
        referrersOciApi(subject, artifactType) ?:        // If we got results from Referrers API, we are done (per OCI spec).
        referrersCosignConvention(subject, artifactType) // Tag schema fallback and Cosign-style tags

    override suspend fun scrapeReferrers(
        subject: OciRef,
        tags: Regex,
        artifactTypeFilter: Regex?,
    ): JsonObject {
        check(subject.isDigest) { "Scraping requires a digest-based reference" }

        val fetchedTags = fetchTagsList(subject)
        val matchingTags = fetchedTags.filter { tags.containsMatchIn(it) }
        val matchingRefs = matchingTags.map { subject.withTag(it) }
        return referrersFromManifests(matchingRefs, artifactTypeFilter)
    }

    override suspend fun resolveToImageManifest(image: OciRef, selector: PlatformSelector): OciRef {
        val response = requestManifest(image)
        require(response.status.isSuccess()) { "Failed to fetch manifest: ${response.status}" }

        val body = response.bodyAsText()
        val contentType = response.headers[HttpHeaders.ContentType] ?: ""
        val json = Json.parseToJsonElement(body).jsonObject.rethrowErrors()

        return when {
            isOciImageManifest(json) -> validateResolvedManifest(image, selector)
            isOciImageIndex(json, contentType) -> {
                val imageManifests = json["manifests"]?.jsonArray ?: error("Index has no manifests")

                val allPlatforms = imageManifests
                    .mapNotNull { it.jsonObject["platform"]?.jsonObject }
                    .map { Platform.fromJson(it) }


                val selectedImageManifest = try {
                    imageManifests.single {
                        selector.matches(it.jsonObject["platform"]?.jsonObject)
                    }
                } catch (e: NoSuchElementException) {
                    throw NoSuchPlatformSelectionException(
                        "No manifests found matching the specified constraints",
                        allPlatforms.filter { it.isValid() }, selector, e
                    )
                } catch (e: IllegalArgumentException) {
                    throw AmbiguousPlatformSelectionException(
                        "Multiple manifests found matching the specified constraints",
                        allPlatforms.filter { selector.matches(it) }, selector, e
                    )
                }

                val imageDigest = requireNotNull(selectedImageManifest.jsonObject["digest"]?.jsonPrimitive?.content) {
                    "Selected manifest entry has no digest"
                }

                val manifestRef = image.withDigest(imageDigest)
                validateResolvedManifest(manifestRef, selector) // just in case, make sure the image is consistent with the index
            }

            else -> error("Reference points to neither an index nor a manifest (Content-Type: $contentType)")
        }
    }

    override fun close() {
        if (!externallyManaged) client.close()
    }

    override fun isOciImageIndex(json: JsonObject?, contentType: String): Boolean = when {
        contentType.contains("application/vnd.oci.image.index.v1+json", true) -> json!=null
        contentType.contains("application/vnd.docker.distribution.manifest.list.v2+json", true) -> json!=null
        else -> json != null && json["manifests"] is JsonArray
    }

    override fun isOciImageManifest(json: JsonObject?): Boolean =
        listOf("layer", "config", "subject", "fsLayers").any { json?.containsKey(it) ?: false }

    private fun JsonObject.rethrowErrors(): JsonObject {
        val errors = jsonObject["errors"]?.jsonArray ?: emptyList()
        check(errors.isEmpty()) { "Index has errors: $errors" }
        return this
    }

    private suspend fun validateResolvedManifest(image: OciRef, selector: PlatformSelector): OciRef {
        if (!selector.hasConstraints()) return image // shortcut expensive validation

        val artifacts = fetchAllMetadata(image)
        require(artifacts.images.size == 1) { "Expected exactly one resolved image, got ${artifacts.images.size}" }
        artifacts.images.first().config.let {
            requireNotNull(it) { "Could not fetch config to verify platform constraints" }
            require(selector.matches(it)) { "Manifest does not match platform constraints" }
        }
        return image
    }

    fun bearerTokenUrl(wwwAuthenticateHeader: String): String {
        check(wwwAuthenticateHeader.contains(" ")) {
            "WWW-Authenticate header is missing schema: $wwwAuthenticateHeader"
        }

        // https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/WWW-Authenticate
        val (schema, params) = wwwAuthenticateHeader.split(" ", limit = 2).map { it.trim() }
        val challengeParams: Map<String, String> = params
            .split(',')
            .map { kvStr -> kvStr.split('=', limit = 2).map { it.trim('"') } }
            .filter { it.size == 2 && it[0].isNotEmpty() }
            .associate { it[0] to it[1] }

        val tokenUrlRealm = challengeParams["realm"]
        val tokenUrlParams = challengeParams
            .filterKeys { it != "realm" }
            .map { (k, v) -> "$k=${urlEncode(v)}" }
            .joinToString("&")

        check(tokenUrlRealm != null) { "Realm is missing in WWW-Authenticate header: $wwwAuthenticateHeader" }
        check(tokenUrlRealm.isNotEmpty()) { "Realm is empty in WWW-Authenticate header: $wwwAuthenticateHeader" }
        check(schema.equals("Bearer", ignoreCase = true)) { "Schema is not Bearer (the only one supported): $wwwAuthenticateHeader" }
        return when {
            tokenUrlParams.isEmpty() -> tokenUrlRealm
            else -> "$tokenUrlRealm?$tokenUrlParams"
        }
    }

    private suspend fun referrersCosignConvention(ref: OciRef, artifactType: String?): JsonObject {
        check(ref.isDigest) { "Referrers API requires a digest-based reference" }
        val artifactTypeFilter = artifactType?.let { Regex("^$it$") }
        val cosignTagPrefix = ref.reference.replace(":", "-")
        val cosignRefs = listOf("", ".sig", ".att", ".sbom")
            .map { suffix -> cosignTagPrefix + suffix }
            .map { tag -> ref.withTag(tag) }
        return referrersFromManifests(cosignRefs, artifactTypeFilter)
    }

    private suspend fun referrersFromManifests(refs: List<OciRef>, artifactTypeFilter: Regex?): JsonObject {
        val discoveredManifests = refs.flatMap { ref ->
            val response = requestManifest(ref)
            if (response.status != HttpStatusCode.OK) {
                require(response.status == HttpStatusCode.NotFound) {
                    "Referrers tag-schema fallback returned unexpected ${response.status} for $ref"
                }
                emptyList()
            } else {
                require(response.status == HttpStatusCode.OK)
                val contentType = response.headers[HttpHeaders.ContentType] ?: ""
                val body = response.bodyAsText()
                val json = Json.parseToJsonElement(body).jsonObject.rethrowErrors()
                when {
                    // It's an index (OCI Referrers Tag Schema), add its manifests
                    isOciImageIndex(json, contentType) ->
                        json["manifests"]?.jsonArray?.map { it.jsonObject } ?: emptyList()

                    // It's a single manifest (Cosign style), convert to descriptor and add
                    isOciImageManifest(json) ->
                        listOf(JsonObject(surrogateManifestCosign(body, response, json, ref.reference)))

                    else -> emptyList()
                }
            }
        }

        val filteredManifests = if (artifactTypeFilter == null) {
            discoveredManifests
        } else {
            discoveredManifests.filter {
                val artifactType = it["artifactType"]?.jsonPrimitive?.content
                artifactType != null && artifactTypeFilter.containsMatchIn(artifactType)
            }
        }

        val resultIndex = mutableMapOf<String, JsonElement>()
        resultIndex["schemaVersion"] = JsonPrimitive(2)
        resultIndex["mediaType"] = JsonPrimitive("application/vnd.oci.image.index.v1+json")
        resultIndex["manifests"] = JsonArray(filteredManifests.map { JsonObject(it) })
        return JsonObject(resultIndex)
    }

    private fun surrogateManifestCosign(body: String, tagResponse: HttpResponse, json: JsonObject, tag: String): MutableMap<String, JsonElement> {
        val bodyBytes = body.encodeToByteArray()

        var mediaType = json["mediaType"]?.jsonPrimitive?.contentOrNull
            ?: tagResponse.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()
            ?: ""
        if (mediaType.isEmpty() || mediaType == "application/json") {
            mediaType = if (json.containsKey("fsLayers")) {
                // Fallback for Docker v1 (schemaVersion 1)
                "application/vnd.docker.distribution.manifest.v1+json"
            } else {
                "application/vnd.oci.image.manifest.v1+json"
            }
        }

        val artifactType = json["artifactType"]?.jsonPrimitive?.contentOrNull ?: when {
            tag.endsWith(".sig") -> "application/vnd.dev.cosign.simplesigning.v1+json"
            tag.endsWith(".att") -> "application/vnd.dsse.envelope.v1+json"
            tag.endsWith(".sbom") -> "text/spdx+json"
            else -> null
        }

        val digest = tagResponse.headers["Docker-Content-Digest"] ?: ("sha256:" + SHA256().digest(bodyBytes).toHexString())
        val size = tagResponse.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: bodyBytes.size.toLong()
        val annotations = json["annotations"]

        val descriptor = mutableMapOf<String, JsonElement>(
            "digest" to JsonPrimitive(digest),
            "mediaType" to JsonPrimitive(mediaType),
            "size" to JsonPrimitive(size),
        )
        artifactType?.let { descriptor["artifactType"] = JsonPrimitive(it) }
        annotations?.let { descriptor["annotations"] = it }

        return descriptor
    }

    /**
     * Returns an index of referrers of the specified type.
     * If OCI Referrers API is not supported or returns an empty index,
     * return null to indicate that fallback methods should be attempted.
     */
    private suspend fun referrersOciApi(ref: OciRef, artifactType: String?): JsonObject? {
        check(ref.isDigest) { "Referrers API requires a digest-based reference" }
        val apiUrl = "https://${ref.registry}/v2/${ref.repository}/referrers/${ref.reference}"
        val query = if (artifactType != null) "?artifactType=${urlEncode(artifactType)}" else ""
        val response = requestUrl(apiUrl + query, "application/vnd.oci.image.index.v1+json")

        if (response.status != HttpStatusCode.OK) {
            // We only fall back if the Referrers API returned an empty list OR 404.
            require(response.status == HttpStatusCode.NotFound) {
                "Referrers API returned ${response.status} for $apiUrl$query"
            }
            return null
        }

        val text = if (artifactType == null) {
            response.bodyAsText()
        } else {
            val filtersAppliedHeader = response.headers["OCI-Filters-Applied"]
            clientSideArtifactTypeFilter(response.bodyAsText(), artifactType, filtersAppliedHeader)
        }

        // However, some registries (like Quay) might support Referrers API but return empty,
        // while still having Cosign tags.
        val json = Json.parseToJsonElement(text).jsonObject.rethrowErrors()
        return if (!json["manifests"]?.jsonArray.isNullOrEmpty()) json else null
        // If empty, fall through to tag-schema and Cosign discovery
    }

    /**
     * Filters the artifact list in the response based on the given artifact type.
     * If the server has already applied the artifactType filter, the body is returned as-is.
     * Otherwise, it applies a client-side filter to the body.
     *
     * According to the OCI Distribution Spec, the Referrers API supports an optional `artifactType` query parameter
     * to filter results server-side. However, not all OCI-compliant registries implement this parameter correctly.
     * 
     * Some registries may ignore the query parameter entirely, while others may acknowledge it via the 
     * `OCI-Filters-Applied` response header. To ensure consistent behavior across different registry implementations,
     * this method checks if the server actually applied the filter (via the `OCI-Filters-Applied: artifactType` header).
     * If the header is absent or doesn't indicate server-side filtering, we perform client-side filtering to ensure
     * the returned artifacts match the requested type. This defensive approach provides compatibility with both
     * compliant and non-compliant registry implementations.
     * 
     * @param body The raw body of the response, typically in JSON format, containing the list of artifacts.
     * @param artifactType The type of artifact to filter by, used for client-side filtering if needed.
     * @param filtersApplied The value of the OCI-Filters-Applied header, indicating which filters the server applied.
     * @return A JSON string containing the filtered list of artifacts, based on the artifact type.
     */
    private fun clientSideArtifactTypeFilter(body: String, artifactType: String, filtersApplied: String?): String = when {
        // the server has already done the filtering by artifactType - pass through the literal response
        filtersApplied != null && filtersApplied.split(',').any { it.trim().equals("artifactType", ignoreCase = true) } -> body

        // filter manually, since the server doesn't support artifactType query param or header is missing
        else -> {
            val json = Json.parseToJsonElement(body).jsonObject
            val filteredManifests = json["manifests"]?.jsonArray?.filter {
                it.jsonObject["artifactType"]?.jsonPrimitive?.content == artifactType
            }
            val overrideManifests = "manifests" to JsonArray(filteredManifests ?: emptyList())
            JsonObject(json + overrideManifests).toString()
        }
    }

}