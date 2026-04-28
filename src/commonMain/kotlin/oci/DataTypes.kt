package oci

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