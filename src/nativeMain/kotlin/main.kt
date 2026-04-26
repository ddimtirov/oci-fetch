import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
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

/**
 * Command to list tags for a repository.
 */
class TagsCommand(private val globalRaw: () -> Boolean) : CliktCommand(name = "tags") {
    override fun help(context: Context): String = "List tags for a repository"
    val repository by argument(help = "The OCI repository (e.g., registry-1.docker.io/library/alpine)")

    @OptIn(ExperimentalForeignApi::class)
    override fun run() {
        runBlocking {
            val raw = globalRaw()
            val client = OciClient()
            try {
                val response = client.fetchTags(repository)
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
    metaCommand.subcommands(indexCommand)
    ociFetch.subcommands(tagsCommand, metaCommand).main(args)
}
