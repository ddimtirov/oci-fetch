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
         * Parses an image reference string into an ImageRef using standard Docker conventions.
         * If the first segment is not recognized as a registry host (lacks '.', ':', and is not 'localhost'),
         * it defaults the registry to 'registry-1.docker.io'. If the repository name contains no '/',
         * it automatically prepends 'library/'.
         */
        fun parse(spec: String, defaultTag: String = "latest"): OciRef {
            return parse(spec, defaultTag, strict = false)
        }

        /**
         * Parses an image reference string into an ImageRef in strict mode, without any Docker-specific
         * defaults or fallbacks. The first segment is always assumed to be the registry host.
         */
        fun parseStrict(spec: String, defaultTag: String = "latest"): OciRef {
            return parse(spec, defaultTag, strict = true)
        }

        private fun parse(spec: String, defaultTag: String, strict: Boolean): OciRef {
            val parts = spec.split('/')
            
            val registry: String
            val repo: String
            
            if (strict) {
                require(parts.size >= 2) { "Invalid image reference: $spec" }
                registry = parts.first()
                repo = parts.drop(1).joinToString("/")
            } else {
                val first = parts.first()
                val isRegistry = first.contains('.') || first.contains(':') || first == "localhost"
                
                if (isRegistry) {
                    registry = first
                    repo = parts.drop(1).joinToString("/")
                } else {
                    registry = "registry-1.docker.io"
                    val fullRepo = spec
                    repo = if ('/' !in fullRepo) {
                        "library/$fullRepo"
                    } else {
                        fullRepo
                    }
                }
            }

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
