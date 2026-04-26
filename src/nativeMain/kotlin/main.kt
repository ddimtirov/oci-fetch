import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.runBlocking
import oci.OciClient
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.*
import kotlin.io.println
import platform.posix.fprintf
import platform.posix.stderr
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * Main command for oci-fetch executable.
 */
class OciFetch : CliktCommand(name = "oci-fetch") {
    val raw by option("--raw", help = "Return raw JSON response").flag()

    override fun run() {
        // This will be called before any subcommand
    }
}

/**
 * Command for meta subcommands.
 */
class MetaCommand : CliktCommand(name = "meta") {
    override fun help(context: Context): String = "Metadata commands"
    override fun run() = Unit
}

/**
 * Command to fetch the index manifest for a repository.
 */
class IndexCommand(private val globalRaw: () -> Boolean) : CliktCommand(name = "index") {
    override fun help(context: Context): String = "Fetch the index manifest"
    val failOnManifest by option("--fail", help = "Fail if the ref points to an image manifest or other object instead of an index").flag()
    val ref by argument(help = "The OCI image reference (e.g., registry-1.docker.io/library/alpine:latest)")

    @OptIn(ExperimentalForeignApi::class)
    override fun run() {
        runBlocking {
            val raw = globalRaw()
            val client = OciClient()
            try {
                val imageRef = OciClient.parseRef(ref)
                val response = client.fetchManifest(imageRef)
                if (response.status.value !in 200..299) {
                    fprintf(stderr, "Error: Failed to fetch manifest: %s\n", response.status.toString())
                    return@runBlocking
                }
                val body = response.bodyAsText()
                val contentType = response.headers[HttpHeaders.ContentType] ?: ""
                val json = Json.parseToJsonElement(body).jsonObject

                val isIndex = client.isIndexContent(contentType, json)
                val isManifest = json.containsKey("layers") || json.containsKey("config")

                if (isIndex) {
                    if (raw) {
                        println(body)
                    } else {
                        val manifests = json["manifests"]?.jsonArray
                        println("digest\tmediaType\tos\tarchitecture\tos.version\tos.features\tvariant")
                        manifests?.forEach { entry ->
                            val obj = entry.jsonObject
                            val digest = obj["digest"]?.jsonPrimitive?.content ?: ""
                            val mediaType = obj["mediaType"]?.jsonPrimitive?.content ?: ""
                            val platform = obj["platform"]?.jsonObject
                            val os = platform?.get("os")?.jsonPrimitive?.content ?: ""
                            val arch = platform?.get("architecture")?.jsonPrimitive?.content ?: ""
                            val osVer = platform?.get("os.version")?.jsonPrimitive?.content ?: ""
                            val osFeat = platform?.get("os.features")?.jsonArray?.joinToString(",") { it.jsonPrimitive.content } ?: ""
                            val variant = platform?.get("variant")?.jsonPrimitive?.content ?: ""
                            println("$digest\t$mediaType\t$os\t$arch\t$osVer\t$osFeat\t$variant")
                        }
                    }
                } else if (!isManifest) {
                    fprintf(stderr, "Error: Format error: Reference points to neither an index nor a manifest (Content-Type: %s)\n", contentType)
                    return@runBlocking
                } else {
                    // It's a manifest
                    if (failOnManifest) {
                        fprintf(stderr, "Error: --fail was requested and reference points to a non-index.\n")
                        return@runBlocking
                    }

                    if (raw) {
                        println(body)
                    } else {
                        // Infer basic index
                        println("digest\tmediaType\tos\tarchitecture\tos.version\tos.features\tvariant")
                        // For a manifest, we don't necessarily have the platform info in the manifest itself (it's in the config)
                        // But the requirement says "infer a basic index and print that"
                        // Usually this means one entry.
                        // We might need to fetch the digest if it's not in the response (it usually is in ETag or Docker-Content-Digest header)
                        val digest = response.headers["Docker-Content-Digest"] ?: ""
                        val mediaType = contentType
                        // Manifests don't have platform info at this level.
                        println("$digest\t$mediaType\t\t\t\t\t")
                    }
                }
            } finally {
                client.close()
            }
        }
    }
}

fun printTsvManifest(body: String) {
    val json = Json.parseToJsonElement(body).jsonObject
    
    // Check if it's a manifest
    // Docker v2 has "layers" and "config"
    // OCI v1 has "layers" and "config"
    // Some artifacts might have "blobs" but OCI manifest has "layers"
    val isManifest = json.containsKey("layers") || json.containsKey("config")
    if (!isManifest) {
        throw IllegalArgumentException("The content is not a valid manifest")
    }

    val artifactType = json["artifactType"]?.jsonPrimitive?.content ?: ""
    if (artifactType.isNotEmpty()) {
        println("ARTIFACT_TYPE\t$artifactType")
    }

    val subject = json["subject"]?.jsonObject
    if (subject != null) {
        val digest = subject["digest"]?.jsonPrimitive?.content ?: ""
        val mediaType = subject["mediaType"]?.jsonPrimitive?.content ?: ""
        val size = subject["size"]?.jsonPrimitive?.content ?: ""
        println("SUBJECT\t$digest\t$mediaType\t$size")
    }

    val config = json["config"]?.jsonObject
    if (config != null) {
        val digest = config["digest"]?.jsonPrimitive?.content ?: ""
        val mediaType = config["mediaType"]?.jsonPrimitive?.content ?: ""
        val size = config["size"]?.jsonPrimitive?.content ?: ""
        println("CONFIG\t$digest\t$mediaType\t$size")
    }

    val layers = json["layers"]?.jsonArray
    layers?.forEachIndexed { index, element ->
        val layer = element.jsonObject
        val digest = layer["digest"]?.jsonPrimitive?.content ?: ""
        val mediaType = layer["mediaType"]?.jsonPrimitive?.content ?: ""
        val size = layer["size"]?.jsonPrimitive?.content ?: ""
        println("LAYER\t$index\t$digest\t$mediaType\t$size")
    }

    val annotations = json["annotations"]?.jsonObject
    annotations?.forEach { (key, value) ->
        val v = value.jsonPrimitive.content.replace("\n", " ").replace("\t", " ")
        println("ANNOTATION\t$key\t$v")
    }
}

/**
 * Command to fetch the manifest for a specific platform.
 */
class ManifestCommand(private val globalRaw: () -> Boolean) : CliktCommand(name = "manifest") {
    override fun help(context: Context): String = "Fetch the manifest for a specific platform"

    val architecture by option("--architecture", help = "The architecture of the image")
    val os by option("--os", help = "The OS of the image")
    val osVersion by option("--os-version", help = "The OS version of the image")
    val osFeatures by option("--os-features", help = "The OS features of the image").multiple()
    val variant by option("--variant", help = "The variant of the image")

    val ref by argument(help = "The OCI image reference (e.g., registry-1.docker.io/library/alpine:latest)")

    @OptIn(ExperimentalForeignApi::class)
    override fun run() {
        runBlocking {
            val raw = globalRaw()
            val client = OciClient()
            try {
                val imageRef = OciClient.parseRef(ref)
                val response = client.fetchManifest(imageRef)
                if (response.status.value !in 200..299) {
                    fprintf(stderr, "Error: Failed to fetch manifest: %s\n", response.status.toString())
                    return@runBlocking
                }

                val body = response.bodyAsText()
                val contentType = response.headers[HttpHeaders.ContentType] ?: ""
                val json = Json.parseToJsonElement(body).jsonObject

                val isIndex = client.isIndexContent(contentType, json)
                val isManifest = json.containsKey("layers") || json.containsKey("config")

                if (isIndex) {
                    val manifests = json["manifests"]?.jsonArray
                    val matches = manifests?.filter { entry ->
                        val obj = entry.jsonObject
                        val platform = obj["platform"]?.jsonObject ?: return@filter false

                        if (architecture != null && platform["architecture"]?.jsonPrimitive?.content != architecture) return@filter false
                        if (os != null && platform["os"]?.jsonPrimitive?.content != os) return@filter false
                        if (osVersion != null && platform["os.version"]?.jsonPrimitive?.content != osVersion) return@filter false
                        if (variant != null && platform["variant"]?.jsonPrimitive?.content != variant) return@filter false
                        if (osFeatures.isNotEmpty()) {
                            val features = platform["os.features"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                            if (!features.containsAll(osFeatures)) return@filter false
                        }
                        true
                    } ?: emptyList()

                    if (matches.isEmpty()) {
                        fprintf(stderr, "Error: No manifest found matching the specified constraints\n")
                        return@runBlocking
                    }

                    if (matches.size > 1) {
                        val options = matches.mapNotNull { entry ->
                            val platform = entry.jsonObject["platform"]?.jsonObject ?: return@mapNotNull null
                            val arch = platform["architecture"]?.jsonPrimitive?.content ?: ""
                            val osName = platform["os"]?.jsonPrimitive?.content ?: ""
                            val osVer = platform["os.version"]?.jsonPrimitive?.content
                            val variantStr = platform["variant"]?.jsonPrimitive?.content
                            val features = platform["os.features"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()

                            if (arch == "unknown" || osName == "unknown") return@mapNotNull null

                            buildString {
                                append("--architecture $arch --os $osName")
                                if (osVer != null) append(" --os-version $osVer")
                                if (variantStr != null) append(" --variant $variantStr")
                                features.forEach { append(" --os-features $it") }
                            }
                        }.distinct().joinToString("\n")
                        fprintf(stderr, "Error: Multiple manifests found matching the specified constraints\n\n$options\n")
                        return@runBlocking
                    }

                    val selectedDigest = matches[0].jsonObject["digest"]?.jsonPrimitive?.content
                    if (selectedDigest == null) {
                        fprintf(stderr, "Error: Selected manifest entry has no digest\n")
                        return@runBlocking
                    }

                    // Fetch the specific manifest
                    val manifestRef = imageRef.copy(reference = selectedDigest)
                    val manifestResponse = client.fetchManifest(manifestRef)
                    if (manifestResponse.status.value !in 200..299) {
                        fprintf(stderr, "Error: Failed to fetch matched manifest: %s\n", manifestResponse.status.toString())
                        return@runBlocking
                    }

                    val manifestBody = manifestResponse.bodyAsText()
                    if (raw) {
                        println(manifestBody)
                    } else {
                        printTsvManifest(manifestBody)
                    }
                } else if (isManifest) {
                    // It's already a manifest. Check if it matches constraints.
                    // Manifests don't have platform info. OCI spec says platform is in index or in config.
                    // The requirement says: "If the ref points to a manifest, dump it if it matches the constraints, or throw error if it does not."
                    // Since the manifest itself doesn't have platform info, we'd need to fetch the config to check.
                    
                    val configDigest = json["config"]?.jsonObject?.get("digest")?.jsonPrimitive?.content
                    if (configDigest == null) {
                         // Some manifests might not have config (e.g. some artifacts), but for images they do.
                         // If we can't check constraints, should we assume it matches or fails?
                         // Most likely we should try to fetch config if constraints are provided.
                         if (architecture != null || os != null || osVersion != null || variant != null || osFeatures.isNotEmpty()) {
                             fprintf(stderr, "Error: Reference points to a manifest but it lacks platform information to verify constraints\n")
                             return@runBlocking
                         }
                    } else {
                        // Fetch config to verify constraints if any are provided
                        if (architecture != null || os != null || osVersion != null || variant != null || osFeatures.isNotEmpty()) {
                            val configResponse = client.fetchArtifacts(imageRef)
                            // OciClient.fetchArtifacts fetches config.
                            val configBody = configResponse.configs.firstOrNull()
                            if (configBody.isNullOrBlank()) {
                                fprintf(stderr, "Error: Could not fetch config to verify platform constraints\n")
                                return@runBlocking
                            }
                            val configJson = Json.parseToJsonElement(configBody).jsonObject
                            
                            if (architecture != null && configJson["architecture"]?.jsonPrimitive?.content != architecture) {
                                fprintf(stderr, "Error: Manifest does not match architecture constraint\n")
                                return@runBlocking
                            }
                            if (os != null && configJson["os"]?.jsonPrimitive?.content != os) {
                                fprintf(stderr, "Error: Manifest does not match OS constraint\n")
                                return@runBlocking
                            }
                            if (osVersion != null && configJson["os.version"]?.jsonPrimitive?.content != osVersion) {
                                fprintf(stderr, "Error: Manifest does not match OS version constraint\n")
                                return@runBlocking
                            }
                            if (variant != null && configJson["variant"]?.jsonPrimitive?.content != variant) {
                                fprintf(stderr, "Error: Manifest does not match variant constraint\n")
                                return@runBlocking
                            }
                            if (osFeatures.isNotEmpty()) {
                                // OS features are rare in config, but let's check
                                val features = configJson["os.features"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                                if (!features.containsAll(osFeatures)) {
                                    fprintf(stderr, "Error: Manifest does not match OS features constraints\n")
                                    return@runBlocking
                                }
                            }
                        }
                    }
                    
                    if (raw) {
                        println(body)
                    } else {
                        printTsvManifest(body)
                    }
                } else {
                    fprintf(stderr, "Error: Format error: Reference points to neither an index nor a manifest (Content-Type: %s)\n", contentType)
                    return@runBlocking
                }
            } catch (e: Exception) {
                fprintf(stderr, "Error: %s\n", e.message ?: "Unknown error")
            } finally {
                client.close()
            }
        }
    }
}

/**
 * Command to fetch the config for a specific platform.
 */
class ConfigCommand(private val globalRaw: () -> Boolean) : CliktCommand(name = "config") {
    override fun help(context: Context): String = "Fetch the config for a specific platform"

    val architecture by option("--architecture", help = "The architecture of the image")
    val os by option("--os", help = "The OS of the image")
    val osVersion by option("--os-version", help = "The OS version of the image")
    val osFeatures by option("--os-features", help = "The OS features of the image").multiple()
    val variant by option("--variant", help = "The variant of the image")

    val ref by argument(help = "The OCI image reference (e.g., registry-1.docker.io/library/alpine:latest)")

    @OptIn(ExperimentalForeignApi::class)
    override fun run() {
        runBlocking {
            val raw = globalRaw()
            val client = OciClient()
            try {
                val imageRef = OciClient.parseRef(ref)
                val response = client.fetchManifest(imageRef)
                if (response.status.value !in 200..299) {
                    fprintf(stderr, "Error: Failed to fetch manifest: %s\n", response.status.toString())
                    return@runBlocking
                }

                val body = response.bodyAsText()
                val contentType = response.headers[HttpHeaders.ContentType] ?: ""
                val json = Json.parseToJsonElement(body).jsonObject

                val isIndex = client.isIndexContent(contentType, json)
                val isManifest = json.containsKey("layers") || json.containsKey("config")
                // Config usually has "rootfs" and "history", and maybe "config" or "architecture"
                val isConfig = json.containsKey("rootfs") || json.containsKey("history")

                val manifestDigest: String
                if (isIndex) {
                    val manifests = json["manifests"]?.jsonArray
                    val matches = manifests?.filter { entry ->
                        val obj = entry.jsonObject
                        val platform = obj["platform"]?.jsonObject ?: return@filter false

                        if (architecture != null && platform["architecture"]?.jsonPrimitive?.content != architecture) return@filter false
                        if (os != null && platform["os"]?.jsonPrimitive?.content != os) return@filter false
                        if (osVersion != null && platform["os.version"]?.jsonPrimitive?.content != osVersion) return@filter false
                        if (variant != null && platform["variant"]?.jsonPrimitive?.content != variant) return@filter false
                        if (osFeatures.isNotEmpty()) {
                            val features = platform["os.features"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                            if (!features.containsAll(osFeatures)) return@filter false
                        }
                        true
                    } ?: emptyList()

                    if (matches.isEmpty()) {
                        fprintf(stderr, "Error: No manifest found matching the specified constraints\n")
                        return@runBlocking
                    }

                    if (matches.size > 1) {
                        val options = matches.mapNotNull { entry ->
                            val platform = entry.jsonObject["platform"]?.jsonObject ?: return@mapNotNull null
                            val arch = platform["architecture"]?.jsonPrimitive?.content ?: ""
                            val osName = platform["os"]?.jsonPrimitive?.content ?: ""
                            val osVer = platform["os.version"]?.jsonPrimitive?.content
                            val variantStr = platform["variant"]?.jsonPrimitive?.content
                            val features = platform["os.features"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()

                            if (arch == "unknown" || osName == "unknown") return@mapNotNull null

                            buildString {
                                append("--architecture $arch --os $osName")
                                if (osVer != null) append(" --os-version $osVer")
                                if (variantStr != null) append(" --variant $variantStr")
                                features.forEach { append(" --os-features $it") }
                            }
                        }.distinct().joinToString("\n")
                        fprintf(stderr, "Error: Multiple manifests found matching the specified constraints\n\n$options\n")
                        return@runBlocking
                    }

                    val selectedDigest = matches[0].jsonObject["digest"]?.jsonPrimitive?.content
                    if (selectedDigest == null) {
                        fprintf(stderr, "Error: Selected manifest entry has no digest\n")
                        return@runBlocking
                    }
                    manifestDigest = selectedDigest
                } else if (isManifest) {
                    // It's already a manifest. Check if it matches constraints.
                    // We need the config to check constraints.
                    val artifacts = client.fetchArtifacts(imageRef)
                    val configBody = artifacts.configs.firstOrNull()
                    
                    if (architecture != null || os != null || osVersion != null || variant != null || osFeatures.isNotEmpty()) {
                        if (configBody.isNullOrBlank()) {
                            fprintf(stderr, "Error: Reference points to a manifest but it lacks platform information to verify constraints\n")
                            return@runBlocking
                        }
                        val configJson = Json.parseToJsonElement(configBody).jsonObject
                        if (architecture != null && configJson["architecture"]?.jsonPrimitive?.content != architecture) {
                            fprintf(stderr, "Error: Manifest does not match architecture constraint\n")
                            return@runBlocking
                        }
                        if (os != null && configJson["os"]?.jsonPrimitive?.content != os) {
                            fprintf(stderr, "Error: Manifest does not match OS constraint\n")
                            return@runBlocking
                        }
                        if (osVersion != null && configJson["os.version"]?.jsonPrimitive?.content != osVersion) {
                            fprintf(stderr, "Error: Manifest does not match OS version constraint\n")
                            return@runBlocking
                        }
                        if (variant != null && configJson["variant"]?.jsonPrimitive?.content != variant) {
                            fprintf(stderr, "Error: Manifest does not match variant constraint\n")
                            return@runBlocking
                        }
                        if (osFeatures.isNotEmpty()) {
                            val features = configJson["os.features"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                            if (!features.containsAll(osFeatures)) {
                                fprintf(stderr, "Error: Manifest does not match OS features constraints\n")
                                return@runBlocking
                            }
                        }
                    }
                    
                    if (configBody.isNullOrBlank()) {
                        fprintf(stderr, "Error: Manifest has no config\n")
                        return@runBlocking
                    }

                    if (raw) {
                        println(configBody)
                    } else {
                        val prettyJson = Json { prettyPrint = true }
                        val configElement = Json.parseToJsonElement(configBody)
                        println(prettyJson.encodeToString(JsonElement.serializer(), configElement))
                    }
                    return@runBlocking
                } else if (isConfig) {
                    // It points to a config directly
                    if (raw) {
                        println(body)
                    } else {
                        val prettyJson = Json { prettyPrint = true }
                        println(prettyJson.encodeToString(JsonElement.serializer(), json))
                    }
                    return@runBlocking
                } else {
                    fprintf(stderr, "Error: Format error: Reference points to an unknown type (Content-Type: %s)\n", contentType)
                    return@runBlocking
                }

                // If we reached here, we followed an index and have manifestDigest
                val artifacts = client.fetchArtifacts(imageRef.copy(reference = manifestDigest))
                val configBody = artifacts.configs.firstOrNull()
                if (configBody.isNullOrBlank()) {
                    fprintf(stderr, "Error: Manifest has no config\n")
                    return@runBlocking
                }

                if (raw) {
                    println(configBody)
                } else {
                    val prettyJson = Json { prettyPrint = true }
                    val configElement = Json.parseToJsonElement(configBody)
                    println(prettyJson.encodeToString(JsonElement.serializer(), configElement))
                }
            } catch (e: Exception) {
                fprintf(stderr, "Error: %s\n", e.message ?: "Unknown error")
            } finally {
                client.close()
            }
        }
    }
}

/**
 * Command to list tags for a repository.
 */
class TagsCommand(private val globalRaw: () -> Boolean) : CliktCommand(name = "tags") {
    override fun help(context: Context): String = "List tags for a repository"
    val repositoryRef by argument(help = "The OCI repository (e.g., registry-1.docker.io/library/alpine)")

    @OptIn(ExperimentalForeignApi::class)
    override fun run() {
        runBlocking {
            val raw = globalRaw()
            val client = OciClient()
            try {
                val response = client.fetchTags(repositoryRef)
                val body = response.bodyAsText()
                if (raw) {
                    println(body)
                } else {
                    val json = Json.parseToJsonElement(body).jsonObject
                    val tags = json["tags"]?.jsonArray
                    tags?.forEach {
                        println(it.jsonPrimitive.content)
                    }
                }
            } finally {
                client.close()
            }
        }
    }
}

fun main(args: Array<String>) {
    val ociFetch = OciFetch()
    val tagsCommand = TagsCommand(globalRaw = { ociFetch.raw })
    val metaCommand = MetaCommand()
    val indexCommand = IndexCommand(globalRaw = { ociFetch.raw })
    val manifestCommand = ManifestCommand(globalRaw = { ociFetch.raw })
    val configCommand = ConfigCommand(globalRaw = { ociFetch.raw })
    metaCommand.subcommands(indexCommand, manifestCommand, configCommand)
    ociFetch.subcommands(tagsCommand, metaCommand).main(args)
}
