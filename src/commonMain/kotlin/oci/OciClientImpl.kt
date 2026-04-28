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

        const val EMPTY_REFERRERS_INDEX = """{"schemaVersion":2,"mediaType":"application/vnd.oci.image.index.v1+json","manifests":[]}"""
    }

    override fun parseRef(spec: String, defaultTag: String): ImageRef {
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

    override suspend fun fetchManifest(image: ImageRef): HttpResponse {
        val url = "https://${image.registry}/v2/${image.repository}/manifests/${image.reference}"
        return authorizedGet(url, acceptManifest)
    }

    override suspend fun fetchUrl(url: String): HttpResponse {
        return authorizedGet(url)
    }

    override suspend fun fetchTags(repository: String): HttpResponse {
        // repository is like "library/alpine"
        // registry is needed, so maybe we should pass ImageRef or just the full spec
        val parts = repository.split('/', limit = 2)
        val registry = parts[0]
        val name = parts.getOrNull(1) ?: ""
        val url = "https://$registry/v2/$name/tags/list"
        return authorizedGet(url)
    }

    override suspend fun fetchTagsList(repository: String): List<String> {
        val response = fetchTags(repository)
        check(response.status == HttpStatusCode.OK) { "Failed to fetch tags: ${response.status}" }
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

    override suspend fun fetchArtifacts(image: ImageRef): FetchedArtifacts {
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

    override fun isIndexContent(contentType: String, json: JsonObject?): Boolean {
        val ct = contentType.lowercase()
        if (ct.contains("application/vnd.oci.image.index.v1+json")) return true
        if (ct.contains("application/vnd.docker.distribution.manifest.list.v2+json")) return true
        // Fallback to structure check
        val hasManifests = json?.get("manifests") is JsonArray
        return hasManifests
    }

    override fun isManifestContent(json: JsonObject?): Boolean =
        listOf("layer", "config", "subject", "fsLayers").any { json?.containsKey(it) ?: false }

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

    override fun close() {
        if (!externallyManaged) client.close()
    }

    override suspend fun fetchReferrers(
        image: ImageRef,
        digest: String,
        artifactType: String?,
        scrapeRegex: String?
    ): String {
        if (scrapeRegex != null) {
            return scrapeReferrers(image, digest, scrapeRegex, artifactType)
        }

        // 1. OCI Referrers API
        val apiUrl = "https://${image.registry}/v2/${image.repository}/referrers/$digest"
        val query = if (artifactType != null) "?artifactType=${urlEncode(artifactType)}" else ""
        val response = authorizedGet(apiUrl + query, "application/vnd.oci.image.index.v1+json")

        if (response.status == HttpStatusCode.OK) {
            val body = response.bodyAsText()
            val finalBody = if (artifactType == null) {
                body
            } else {
                val filtersApplied = response.headers["OCI-Filters-Applied"] ?: ""
                val serverFilteredArtifactType = filtersApplied
                    .split(',')
                    .map { it.trim() }
                    .any { it.equals("artifactType", ignoreCase = true) }
                if (serverFilteredArtifactType) body else filterByArtifactType(body, artifactType)
            }

            // If we got results from Referrers API, we are done (per OCI spec).
            // However, some registries (like Quay) might support Referrers API but return empty,
            // while still having Cosign tags.
            // We only fall back if the Referrers API returned an empty list OR 404.
            val json = Json.parseToJsonElement(finalBody).jsonObject
            val manifests = json["manifests"]?.jsonArray
            if (manifests != null && manifests.isNotEmpty()) {
                return finalBody
            }
            // If empty, fall through to tag-schema and Cosign discovery
        } else {
            require(response.status == HttpStatusCode.NotFound) { "Referrers API returned ${response.status} for $apiUrl$query" }
        }

        // 2. Tag Schema Fallback and Cosign-style tags
        val tagBase = digest.replace(":", "-")
        val possibleTags = listOf(tagBase, "$tagBase.sig", "$tagBase.att", "$tagBase.sbom")

        val discoveredManifests = mutableListOf<JsonObject>()

        for (tag in possibleTags) {
            val tagResponse = authorizedGet("https://${image.registry}/v2/${image.repository}/manifests/$tag", "application/vnd.oci.image.index.v1+json")
            if (tagResponse.status == HttpStatusCode.OK) {
                val body = tagResponse.bodyAsText()
                val contentType = tagResponse.headers[HttpHeaders.ContentType] ?: ""
                val json = Json.parseToJsonElement(body).jsonObject

                if (isIndexContent(contentType, json)) {
                    // It's an index (OCI Referrers Tag Schema), add its manifests
                    val manifests = json["manifests"]?.jsonArray
                    manifests?.forEach { discoveredManifests.add(it.jsonObject) }
                } else if (isManifestContent(json)) {
                    // It's a single manifest (Cosign style), convert to descriptor and add
                    val bodyBytes = body.encodeToByteArray()
                    val mDigest = tagResponse.headers["Docker-Content-Digest"] ?: ("sha256:" + SHA256().digest(bodyBytes).toHexString())
                    val size = tagResponse.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: bodyBytes.size.toLong()

                    var mediaType = json["mediaType"]?.jsonPrimitive?.contentOrNull
                        ?: tagResponse.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()
                        ?: ""

                    // Fallback for Docker v1 (schemaVersion 1)
                    if (mediaType.isEmpty() || mediaType == "application/json") {
                        if (json.containsKey("fsLayers")) {
                            mediaType = "application/vnd.docker.distribution.manifest.v1+json"
                        } else {
                            mediaType = "application/vnd.oci.image.manifest.v1+json"
                        }
                    }

                    val descriptor = mutableMapOf<String, JsonElement>()
                    descriptor["digest"] = JsonPrimitive(mDigest)
                    descriptor["mediaType"] = JsonPrimitive(mediaType)
                    descriptor["size"] = JsonPrimitive(size)

                    val artifactType = json["artifactType"]?.jsonPrimitive?.contentOrNull ?: when {
                        tag.endsWith(".sig") -> "application/vnd.dev.cosign.simplesigning.v1+json"
                        tag.endsWith(".att") -> "application/vnd.dsse.envelope.v1+json"
                        tag.endsWith(".sbom") -> "text/spdx+json"
                        else -> null
                    }
                    if (artifactType != null) {
                        descriptor["artifactType"] = JsonPrimitive(artifactType)
                    }

                    json["annotations"]?.let { descriptor["annotations"] = it }

                    discoveredManifests.add(JsonObject(descriptor))
                }
            } else {
                require(tagResponse.status == HttpStatusCode.NotFound) { "Referrers tag-schema fallback returned ${tagResponse.status} for $tag" }
            }
        }

        val filteredManifests = if (artifactType == null) {
            discoveredManifests
        } else {
            discoveredManifests.filter {
                it["artifactType"]?.jsonPrimitive?.content == artifactType
            }
        }

        val resultIndex = mutableMapOf<String, JsonElement>()
        resultIndex["schemaVersion"] = JsonPrimitive(2)
        resultIndex["mediaType"] = JsonPrimitive("application/vnd.oci.image.index.v1+json")
        resultIndex["manifests"] = JsonArray(filteredManifests.map { JsonObject(it) })

        return JsonObject(resultIndex).toString()
    }

    /** Returns a copy of the referrers index body with `manifests` filtered to entries matching `artifactType`. */
    private fun filterByArtifactType(body: String, artifactType: String): String {
        val json = Json.parseToJsonElement(body).jsonObject
        val manifests = json["manifests"]?.jsonArray?.filter {
            it.jsonObject["artifactType"]?.jsonPrimitive?.content == artifactType
        } ?: emptyList()
        return JsonObject(json + ("manifests" to JsonArray(manifests))).toString()
    }

    private suspend fun scrapeReferrers(
        image: ImageRef,
        targetDigest: String,
        regex: String,
        artifactType: String?
    ): String {
        val pattern = Regex(regex)

        val tags = fetchTagsList(image.registry + "/" + image.repository)
        val matchingTags = tags.filter { pattern.containsMatchIn(it) }
        val referrers = matchingTags.flatMap { tag ->
            val ref = image.copy(reference = tag)
            val resp = fetchManifest(ref)
            if (resp.status != HttpStatusCode.OK) return@flatMap emptyList()

            val body = resp.bodyAsText()
            val json = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return@flatMap emptyList()

            val subject = json["subject"]?.jsonObject
            val subjectDigest = subject?.get("digest")?.jsonPrimitive?.content

            if (subjectDigest == targetDigest) return@flatMap emptyList()
            if (artifactType != null && json["artifactType"]?.jsonPrimitive?.content != artifactType) return@flatMap emptyList()

            val bodyBytes = body.encodeToByteArray()
            val bodyDigest = SHA256().digest(bodyBytes).toHexString()
            val digest = resp.headers["Docker-Content-Digest"] ?: "sha256:$bodyDigest"
            val size = resp.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: bodyBytes.size
            val mediaType = json["mediaType"]?.jsonPrimitive?.contentOrNull
                ?: resp.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()
                ?: ""
            val descriptor = mutableMapOf<String, JsonElement>(
                "digest" to JsonPrimitive(digest),
                "mediaType" to JsonPrimitive(mediaType),
                "size" to JsonPrimitive(size),
            )
            json["artifactType"]?.let { descriptor["artifactType"] = it }
            json["annotations"]?.let { descriptor["annotations"] = it }

            return@flatMap listOf(JsonObject(descriptor))
        }

        // Constructs and serializes OCI image index with referrers
        return JsonObject(mapOf(
            "schemaVersion" to JsonPrimitive(2),
            "mediaType" to JsonPrimitive("application/vnd.oci.image.index.v1+json"),
            "manifests" to JsonArray(referrers),
        )).toString()
    }

    override suspend fun resolveManifest(image: ImageRef, selector: PlatformSelector): ManifestResolution {
        val response = fetchManifest(image)
        require(response.status.isSuccess()) { "Failed to fetch manifest: ${response.status}" }

        val body = response.bodyAsText()
        val contentType = response.headers[HttpHeaders.ContentType] ?: ""
        val json = Json.parseToJsonElement(body).jsonObject

        return when {
            isIndexContent(contentType, json) -> {
                val selectedDigest = selectManifestFromIndex(json, selector)
                val manifestRef = image.copy(reference = selectedDigest)
                val manifestResponse = fetchManifest(manifestRef)
                require(manifestResponse.status.isSuccess()) {
                    "Failed to fetch matched manifest: ${manifestResponse.status}"
                }
                ManifestResolution(
                    body = manifestResponse.bodyAsText(),
                    digest = selectedDigest,
                    contentType = manifestResponse.headers[HttpHeaders.ContentType] ?: "",
                    imageRef = manifestRef
                )
            }
            isManifestContent(json) -> {
                val configDigest = validateResolvedManifest(json, selector, image)
                ManifestResolution(
                    body = body,
                    digest = response.headers["Docker-Content-Digest"] ?: configDigest ?: "",
                    contentType = contentType,
                    imageRef = image
                )
            }
            else -> error("Reference points to neither an index nor a manifest (Content-Type: $contentType)")
        }
    }

    private suspend fun validateResolvedManifest(json: JsonObject, selector: PlatformSelector, image: ImageRef): String? {
        // Already a manifest, check constraints
        val configDigest = json["config"]?.jsonObject?.get("digest")?.jsonPrimitive?.content
        if (selector.hasConstraints()) {
            requireNotNull(configDigest) {
                "Reference points to a manifest but it lacks platform information to verify constraints"
            }

            val artifacts = fetchArtifacts(image)
            val configBody = artifacts.configs.firstOrNull()
            require(!configBody.isNullOrBlank()) {
                "Could not fetch config to verify platform constraints"
            }
            val configJson = Json.parseToJsonElement(configBody).jsonObject
            require(selector.matches(configJson)) {
                "Manifest does not match platform constraints"
            }
        }
        return configDigest
    }

    private fun selectManifestFromIndex(json: JsonObject, selector: PlatformSelector): String {
        val manifests = json["manifests"]?.jsonArray ?: error("Index has no manifests")
        val matches = manifests.filter { selector.matches(it.jsonObject["platform"]?.jsonObject) }
        require(matches.isNotEmpty()) { "No manifest found matching the specified constraints" }
        require(matches.size <= 1) { errorMessageWithCmdLineOptions(matches) }

        val selectedDigest = requireNotNull(matches.first().jsonObject["digest"]?.jsonPrimitive?.content) {
            "Selected manifest entry has no digest"
        }
        return selectedDigest
    }

    private fun errorMessageWithCmdLineOptions(matches: List<JsonElement>): String {
        val options = matches.mapNotNull { entry ->
            // Extracts platform details; builds distinct CLI options for matches
            val platform = entry.jsonObject["platform"]?.jsonObject ?: return@mapNotNull null
            val arch = platform["architecture"]?.jsonPrimitive?.content ?: ""
            val osName = platform["os"]?.jsonPrimitive?.content ?: ""
            val osVer = platform["os.version"]?.jsonPrimitive?.content
            val variantStr = platform["variant"]?.jsonPrimitive?.content
            val features = platform["os.features"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            if (arch == "unknown" || osName == "unknown") return@mapNotNull null
            buildString {
                append("--architecture $arch --os $osName")
                osVer?.let { append(" --os-version $it") }
                variantStr?.let { append(" --variant $it") }
                features.forEach { append(" --os-features $it") }
            }
        }.distinct().joinToString("\n")

        return "Multiple manifests found matching the specified constraints\n\n$options"
    }

}