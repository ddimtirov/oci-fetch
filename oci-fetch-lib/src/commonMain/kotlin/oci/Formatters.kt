package oci

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive


private val prettyJson = Json { prettyPrint = true }

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
fun formatTsvManifest(manifestStr: String): String = buildString {
    val manifest = Json.parseToJsonElement(manifestStr).jsonObject
    val isManifest = manifest.containsKey("layers") || manifest.containsKey("config")
    require(isManifest) { "The content is not a valid manifest" }

    val artifactType = manifest["artifactType"]?.jsonPrimitive?.content ?: ""
    if (artifactType.isNotEmpty()) {
        appendLine("ARTIFACT_TYPE\t$artifactType")
    }

    val subject = manifest["subject"]?.jsonObject
    if (subject != null) {
        val digest = subject["digest"]?.jsonPrimitive?.content ?: ""
        val mediaType = subject["mediaType"]?.jsonPrimitive?.content ?: ""
        val size = subject["size"]?.jsonPrimitive?.content ?: ""
        appendLine("SUBJECT\t$digest\t$mediaType\t$size")
    }

    val config = manifest["config"]?.jsonObject
    if (config != null) {
        val digest = config["digest"]?.jsonPrimitive?.content ?: ""
        val mediaType = config["mediaType"]?.jsonPrimitive?.content ?: ""
        val size = config["size"]?.jsonPrimitive?.content ?: ""
        appendLine("CONFIG\t$digest\t$mediaType\t$size")
    }

    val layers = manifest["layers"]?.jsonArray
    layers?.forEachIndexed { index, element ->
        val layer = element.jsonObject
        val digest = layer["digest"]?.jsonPrimitive?.content ?: ""
        val mediaType = layer["mediaType"]?.jsonPrimitive?.content ?: ""
        val size = layer["size"]?.jsonPrimitive?.content ?: ""
        appendLine("LAYER\t$index\t$digest\t$mediaType\t$size")
    }

    val annotations = manifest["annotations"]?.jsonObject
    annotations?.forEach { (key, value) ->
        val v = value.jsonPrimitive.content.replace("\n", " ").replace("\t", " ")
        appendLine("ANNOTATION\t$key\t$v")
    }
}.trimEnd()

/**
 * Formats an OCI index of referrers as TSV.
 */
fun formatTsvReferrers(indexStr: String): String = buildString {
    val index = Json.parseToJsonElement(indexStr).jsonObject
    val manifests = index["manifests"]?.jsonArray
    appendLine("digest\tartifactType\tmediaType\tsize\tannotations")
    manifests?.forEach { entry ->
        val obj = entry.jsonObject
        val digest = obj["digest"]?.jsonPrimitive?.content ?: ""
        val artifactType = obj["artifactType"]?.jsonPrimitive?.content ?: ""
        val mediaType = obj["mediaType"]?.jsonPrimitive?.content ?: ""
        val size = obj["size"]?.jsonPrimitive?.contentOrNull ?: ""
        
        val annotations = obj["annotations"]?.jsonObject?.entries?.joinToString("; ") { (k, v) ->
            "$k=${v.jsonPrimitive.content.replace("\n", " ").replace("\t", " ")}"
        } ?: ""

        appendLine("$digest\t$artifactType\t$mediaType\t$size\t$annotations")
    }
}.trimEnd()

/**
 * Pretty-prints a JSON config.
 */
fun formatPrettyJson(config: JsonObject): String =
    prettyJson.encodeToString(JsonElement.serializer(), config)

fun formatPrettyJson(config: String): String =
    formatPrettyJson(Json.parseToJsonElement(config).jsonObject)
