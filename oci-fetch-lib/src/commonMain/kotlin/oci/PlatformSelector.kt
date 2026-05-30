package oci

import kotlinx.serialization.json.*

/**
 * A selector for OCI platforms.
 */
data class PlatformSelector(
    val architecture: String? = null,
    val os: String? = null,
    val osVersion: String? = null,
    val osFeatures: List<String> = emptyList(),
    val variant: String? = null
) {
    /**
     * Returns true if this selector matches the given platform JSON object.
     */
    fun matches(platform: JsonObject?): Boolean = when {
        platform == null -> false
        architecture != null && platform["architecture"] ?.jsonPrimitive?.content != architecture -> false
        os           != null && platform["os"]           ?.jsonPrimitive?.content != os           -> false
        osVersion    != null && platform["os.version"]   ?.jsonPrimitive?.content != osVersion    -> false
        variant      != null && platform["variant"]      ?.jsonPrimitive?.content != variant      -> false
        osFeatures.isNotEmpty() -> {
            val features = platform["os.features"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            features.containsAll(osFeatures)
        }
        else -> true
    }

    /**
     * Returns true if this selector matches the given platform.
     */
    fun matches(platform: Platform): Boolean = when {
        !platform.isValid() -> false
        architecture != null && platform.arch      != architecture -> false
        os           != null && platform.osName    != os           -> false
        osVersion    != null && platform.osVersion != osVersion    -> false
        variant      != null && platform.variant   != variant      -> false
        osFeatures.isNotEmpty() -> platform.osFeatures.containsAll(osFeatures)
        else -> true
    }

    /**
     * Returns true if any constraints are set.
     */
    fun hasConstraints(): Boolean {
        return architecture != null || os != null || osVersion != null || osFeatures.isNotEmpty() || variant != null
    }
}

data class Platform(
    val arch: String,
    val osName: String,
    val variant: String? = null,
    val osVersion: String? = null,
    val osFeatures: List<String> = emptyList()) {
    fun isValid() = arch != "unknown" && osName != "unknown"

    companion object {
        fun fromJson(entry: JsonElement): Platform = entry.jsonObject.let { platform ->
            Platform(
                arch = platform["architecture"]?.jsonPrimitive?.content ?: "unknown",
                variant = platform["variant"]?.jsonPrimitive?.content,
                osName = platform["os"]?.jsonPrimitive?.content ?: "unknown",
                osVersion = platform["os.version"]?.jsonPrimitive?.content,
                osFeatures = platform["os.features"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            )
        }
    }
}

class NoSuchPlatformSelectionException(m: String, val available: Collection<Platform>, val selector: PlatformSelector, cause: Throwable? = null) : Exception(m, cause)
class AmbiguousPlatformSelectionException(m: String, val candidates: Collection<Platform>, val selector: PlatformSelector, cause: Throwable? = null) : Exception(m, cause)
