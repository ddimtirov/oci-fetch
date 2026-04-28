package oci

import io.ktor.client.HttpClient
import io.ktor.client.statement.HttpResponse
import kotlinx.serialization.json.*

/**
 * Minimal OCI Registry client for fetching image manifests.
 * Handles Bearer token challenge (WWW-Authenticate) and retries the request.
 * 
 * Multiplatform implementation supporting JVM, JS, Native (Windows/Linux), and WASM.
 */
interface OciClient : AutoCloseable {
    /**
     * Fetches the given URL using OCI-compatible authentication if needed.
     */
    suspend fun fetchUrl(url: String): HttpResponse

    /**
     * Fetches the manifest for the given image reference.
     */
    suspend fun fetchManifest(image: ImageRef): HttpResponse

    /**
     * Fetches referrers for the given image digest using various mechanisms.
     */
    suspend fun fetchReferrers(
        image: ImageRef,
        digest: String,
        artifactType: String? = null,
        scrapeRegex: String? = null
    ): String

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
     * Fetches tags for the given repository and returns them as a list of strings.
     */
    suspend fun fetchTagsList(repository: String): List<String>

    /**
     * Determines if the given content type and JSON body represent an OCI index or manifest list.
     */
    fun isIndexContent(contentType: String, json: JsonObject?): Boolean

    /**
     * Determines if the given JSON body represents an OCI manifest.
     */
    fun isManifestContent(json: JsonObject?): Boolean

    /**
     * Resolves an image reference to a specific manifest based on platform constraints.
     * If the reference is an index, it follows it using the selector.
     */
    suspend fun resolveManifest(image: ImageRef, selector: PlatformSelector): ManifestResolution

    /**
     * Parses an image reference string into an ImageRef.
     * Example: registry-1.docker.io/library/alpine:latest
     */
    fun parseRef(spec: String, defaultTag: String = "latest"): ImageRef

    companion object {
        /**
         * Factory method to create the default OciClient implementation.
         * Using 'operator fun invoke' allows users to continue calling OciClient()
         * as if it were a class constructor.
         *
         * @param httpClient Optional HttpClient instance. If null, a new default HttpClient will be created internally.
         *                   If provided, the caller is responsible for managing its lifecycle
         *                   (i.e. the provided client will not be closed by OciClient.close()).
         */
        operator fun invoke(httpClient: HttpClient? = null): OciClient = OciClientImpl(httpClient ?: createHttpClient(), httpClient==null)
    }
}

