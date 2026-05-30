package oci

import kotlinx.serialization.json.JsonObject

/**
 * Image reference containing registry, repository, and tag/digest.
 */
data class OciRef(
    val registry: String,
    val repository: String,
    val reference: String = "latest",
    val isDigest: Boolean = false
) {
    fun withDigest(digest: String): OciRef = copy(isDigest = true, reference = digest)
    fun withTag(tag: String): OciRef = copy(isDigest = false, reference = tag)
    fun withReference(reference: String): OciRef = copy(isDigest = reference.contains(':'), reference = reference)
    override fun toString(): String =
        if (isDigest) "$registry/$repository@$reference"
        else "$registry/$repository:$reference"

    companion object {
        /**
         * Parses an image reference string into an ImageRef.
         * Example: registry-1.docker.io/library/alpine:latest
         */
        fun parse(spec: String, defaultTag: String = "latest"): OciRef {
            // input like: registry-1.docker.io/library/alpine
            val parts = spec.split('/')
            require(parts.size >= 2) { "Invalid image reference: $spec" }

            val registry = parts.first()
            val repo = parts.drop(1).joinToString("/")
            return when {
                '@' in repo -> {
                    val (r, digest) = repo.split('@', limit = 2)
                    OciRef(registry, r, digest, isDigest = true)
                }
                ':' in repo -> {
                    val idx = repo.lastIndexOf(':')
                    val r = repo.substring(0, idx)
                    val tag = repo.substring(idx + 1)
                    OciRef(registry, r, tag)
                }
                else -> OciRef(registry, repo, defaultTag)
            }
        }
    }
}

data class ImageIndexArtifacts(
    val ref: OciRef,
    val index: JsonObject?,
    val images: List<ImageArtifacts>
)

data class ImageArtifacts(
    val ref: OciRef,
    val manifest: JsonObject,
    val config: JsonObject?,
)
