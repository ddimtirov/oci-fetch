package oci

import kotlinx.serialization.json.*

/**
 * Formats an OCI index manifest as TSV.
 */
fun formatTsvIndex(
    body: String,
    selector: PlatformSelector = PlatformSelector()
): String = buildString {
    val json = Json.parseToJsonElement(body).jsonObject
    val manifests = json["manifests"]?.jsonArray
    appendLine("digest\tmediaType\tos\tarchitecture\tos.version\tos.features\tvariant")
    manifests?.forEach { entry ->
        val obj = entry.jsonObject
        val digest = obj["digest"]?.jsonPrimitive?.content ?: ""
        val mediaType = obj["mediaType"]?.jsonPrimitive?.content ?: ""
        val platform = obj["platform"]?.jsonObject
        
        if (!selector.matches(platform)) return@forEach

        val os = platform?.get("os")?.jsonPrimitive?.content ?: ""
        val arch = platform?.get("architecture")?.jsonPrimitive?.content ?: ""
        val osVer = platform?.get("os.version")?.jsonPrimitive?.content ?: ""
        val osFeatList = platform?.get("os.features")?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val osFeat = osFeatList.joinToString(",")
        val variant = platform?.get("variant")?.jsonPrimitive?.content ?: ""

        appendLine("$digest\t$mediaType\t$os\t$arch\t$osVer\t$osFeat\t$variant")
    }
}.trimEnd()

/**
 * Formats an OCI manifest as TSV.
 */
fun formatTsvManifest(body: String): String = buildString {
    val json = Json.parseToJsonElement(body).jsonObject
    
    val isManifest = json.containsKey("layers") || json.containsKey("config")
    if (!isManifest) {
        throw IllegalArgumentException("The content is not a valid manifest")
    }

    val artifactType = json["artifactType"]?.jsonPrimitive?.content ?: ""
    if (artifactType.isNotEmpty()) {
        appendLine("ARTIFACT_TYPE\t$artifactType")
    }

    val subject = json["subject"]?.jsonObject
    if (subject != null) {
        val digest = subject["digest"]?.jsonPrimitive?.content ?: ""
        val mediaType = subject["mediaType"]?.jsonPrimitive?.content ?: ""
        val size = subject["size"]?.jsonPrimitive?.content ?: ""
        appendLine("SUBJECT\t$digest\t$mediaType\t$size")
    }

    val config = json["config"]?.jsonObject
    if (config != null) {
        val digest = config["digest"]?.jsonPrimitive?.content ?: ""
        val mediaType = config["mediaType"]?.jsonPrimitive?.content ?: ""
        val size = config["size"]?.jsonPrimitive?.content ?: ""
        appendLine("CONFIG\t$digest\t$mediaType\t$size")
    }

    val layers = json["layers"]?.jsonArray
    layers?.forEachIndexed { index, element ->
        val layer = element.jsonObject
        val digest = layer["digest"]?.jsonPrimitive?.content ?: ""
        val mediaType = layer["mediaType"]?.jsonPrimitive?.content ?: ""
        val size = layer["size"]?.jsonPrimitive?.content ?: ""
        appendLine("LAYER\t$index\t$digest\t$mediaType\t$size")
    }

    val annotations = json["annotations"]?.jsonObject
    annotations?.forEach { (key, value) ->
        val v = value.jsonPrimitive.content.replace("\n", " ").replace("\t", " ")
        appendLine("ANNOTATION\t$key\t$v")
    }
}.trimEnd()

/**
 * Pretty-prints a JSON config.
 */
fun formatPrettyConfig(body: String): String {
    val prettyJson = Json { prettyPrint = true }
    val configElement = Json.parseToJsonElement(body)
    return prettyJson.encodeToString(JsonElement.serializer(), configElement)
}
