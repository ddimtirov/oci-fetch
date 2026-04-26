package oci

import kotlinx.serialization.json.*

/**
 * A selector for OCI platforms.
 */
@kotlin.jvm.JvmInline
value class PlatformSelector(private val data: SelectorData = SelectorData()) {
    data class SelectorData(
        val architecture: String? = null,
        val os: String? = null,
        val osVersion: String? = null,
        val osFeatures: List<String> = emptyList(),
        val variant: String? = null
    )

    constructor(
        architecture: String? = null,
        os: String? = null,
        osVersion: String? = null,
        osFeatures: List<String> = emptyList(),
        variant: String? = null
    ) : this(SelectorData(architecture, os, osVersion, osFeatures, variant))

    val architecture: String? get() = data.architecture
    val os: String? get() = data.os
    val osVersion: String? get() = data.osVersion
    val osFeatures: List<String> get() = data.osFeatures
    val variant: String? get() = data.variant

    /**
     * Returns true if this selector matches the given platform JSON object.
     */
    fun matches(platform: JsonObject?): Boolean {
        if (platform == null) {
            return architecture == null && os == null && osVersion == null && osFeatures.isEmpty() && variant == null
        }

        if (architecture != null && platform["architecture"]?.jsonPrimitive?.content != architecture) return false
        if (os != null && platform["os"]?.jsonPrimitive?.content != os) return false
        if (osVersion != null && platform["os.version"]?.jsonPrimitive?.content != osVersion) return false
        if (variant != null && platform["variant"]?.jsonPrimitive?.content != variant) return false
        
        if (osFeatures.isNotEmpty()) {
            val features = platform["os.features"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            if (!features.containsAll(osFeatures)) return false
        }

        return true
    }

    /**
     * Filters a list of manifests based on this selector.
     * Expects a list of JSON objects each containing a "platform" field.
     */
    fun filterManifests(manifests: JsonArray): List<JsonObject> {
        return manifests.mapNotNull { it.jsonObject }.filter { matches(it["platform"]?.jsonObject) }
    }

    /**
     * Returns true if any constraints are set.
     */
    fun hasConstraints(): Boolean {
        return architecture != null || os != null || osVersion != null || osFeatures.isNotEmpty() || variant != null
    }
}
