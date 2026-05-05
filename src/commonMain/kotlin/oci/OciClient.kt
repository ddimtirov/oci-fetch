package oci

import io.ktor.client.HttpClient
import io.ktor.client.statement.HttpResponse
import kotlinx.serialization.json.JsonObject

/**
 * Represents a client for interacting with an OCI (Open Container Initiative) registry.
 * Provides methods for performing common OCI image and manifest operations such as
 * fetching blobs, tags, manifests, and resolving image references.
 *
 * Inherits from AutoCloseable, so it can be closed to release any associated resources.
 */
interface OciClient : AutoCloseable {
    /**
     * Requests a GET response from the specified URL,
     * handling anonymous Bearer-Token authentication if necessary.
     */
    suspend fun requestUrl(url: String, acceptHeader: String? = null): HttpResponse

    /**
     * Requests a GET response for a blob from the specified image reference.
     */
    suspend fun requestBlob(image: OciRef): HttpResponse
    /**
     * Requests a GET response for a blob from the specified registry and repository.
     */
    suspend fun requestBlob(registry: String, repository: String, digest: String): HttpResponse

    /**
     * Requests a GET response for a tags-list from the specified image reference.
     */
    suspend fun requestTags(image: OciRef): HttpResponse
    /**
     * Requests a GET response for a tags-list from the specified registry and repository.
     */
    suspend fun requestTags(registry: String, repository: String): HttpResponse

    /**
     * Requests a GET response for a manifest (image or index) from the specified reference.
     */
    suspend fun requestManifest(image: OciRef): HttpResponse
    /**
     * Requests a GET response for a manifest (image or index)
     * from the specified registry, repository, and optionally - tag (defaults to 'latest').
     */
    suspend fun requestManifest(registry: String, repository: String, tag: String ="latest"): HttpResponse

    /**
     * Returns all tags for the given image reference.
     */
    suspend fun fetchTagsList(image: OciRef): List<String>
    /**
     * Returns all tags for the given registry and repository.
     */
    suspend fun fetchTagsList(registry: String, repository: String): List<String>

    /**
     * Fetches all manifest and config blobs for the given image reference.
     * If the top-level manifest is an index (manifest list), it will fetch all contained images.
     */
    suspend fun fetchAllMetadata(image: OciRef): ImageIndexArtifacts

    /**
     * Returns the referrers for a specific repository using the OCI Referrers API,
     * falling back to Cosign tag-schema if unsupported or empty.
     *
     * @param subject The subject as a digest-based `ImageRef` object.
     * @param artifactType The optional artifact type to filter referrers by.
     * @return A JSON representing the referrers, constructed as an OCI image index.
     */
    suspend fun fetchReferrers(subject: OciRef, artifactType: String? = null): JsonObject

    /**
     * Returns the manifests pointing to a target digest by retrieving and checking
     * the manifests of all tags matching a regex, whether they point to the subject.
     *
     * @param subject The subject as a digest-based `ImageRef` object.
     * @param regex identifying which tags to scrape.
     * @param artifactType The optional artifact type to filter referrers by.
     * @return A JSON representing the referrers, constructed as an OCI image index.
     */
    suspend fun scrapeReferrers(subject: OciRef, regex: String, artifactType: String?=null): JsonObject

    /**
     * Resolves a reference to a specific image manifest based on platform constraints.
     * If the reference is an index, it follows it using the selector.
     */
    suspend fun resolveToImageManifest(image: OciRef, selector: PlatformSelector): OciRef

    /**
     * Determines if the given content type and JSON body represent an OCI index or manifest list.
     */
    fun isOciImageIndex(json: JsonObject?, contentType: String): Boolean

    /**
     * Determines if the given JSON body represents an OCI image manifest.
     */
    fun isOciImageManifest(json: JsonObject?): Boolean

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

