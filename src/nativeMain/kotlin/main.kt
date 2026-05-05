import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import io.ktor.client.statement.bodyAsBytes
import kotlinx.coroutines.runBlocking
import oci.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.serialization.json.*
import kotlin.io.println
import platform.posix.fgets
import platform.posix.stdin
import platform.posix.feof
import kotlinx.cinterop.*
import oci.OciRef
import org.kotlincrypto.hash.sha2.SHA256

/**
 * Main command for oci-fetch executable.
 */
class OciFetch : CliktCommand(name = "oci-fetch") {
    val raw by option("--raw", help = "Return raw JSON response").flag()

    override fun run() {
        if (currentContext.invokedSubcommand == null) {
            echo(getFormattedHelp())
            platform.posix.exit(0)
        }
    }
}

/**
 * Command for meta subcommands.
 */
class MetaCommand : CliktCommand(name = "meta") {
    override fun help(context: Context): String = "Metadata commands"

    val architecture by option("--architecture", help = "The architecture of the image")
    val os by option("--os", help = "The OS of the image")
    val osVersion by option("--os-version", help = "The OS version of the image")
    val osFeatures by option("--os-features", help = "The OS features of the image").multiple()
    val variant by option("--variant", help = "The variant of the image")

    val platformSelector: PlatformSelector
        get() = PlatformSelector(
            architecture = architecture,
            os = os,
            osVersion = osVersion,
            osFeatures = osFeatures,
            variant = variant
        )

    override fun run() {
        if (currentContext.invokedSubcommand == null) {
            echo(getFormattedHelp())
            platform.posix.exit(0)
        }
    }
}

/**
 * Command to fetch the index manifest for a repository.
 */
class IndexCommand(
    private val globalRaw: () -> Boolean,
    private val meta: () -> MetaCommand? = { null }
) : CliktCommand(name = "index") {
    override fun help(context: Context): String = "Fetch the index manifest"
    val requireIndex by option("--fail", help = "Fail if the ref points to an image manifest or other object instead of an index").flag()
    val ref by argument(help = "The OCI image reference (e.g., registry-1.docker.io/library/alpine:latest)")

    @OptIn(ExperimentalForeignApi::class)
    override fun run() {
        val meta = meta()
        val selector = meta?.platformSelector ?: PlatformSelector()

        runBlocking {
            val raw = globalRaw()
            if (raw && selector.hasConstraints()) {
                throw UsageError("Filtering options (--os, --architecture, etc.) cannot be combined with --raw")
            }
            val output = OciClient().use { client ->
                queryAndFormat(client, raw, selector)
            }
            println(output)
        }
    }

    private suspend fun queryAndFormat(client: OciClient, raw: Boolean, selector: PlatformSelector): String {
        val imageRef = OciRef.parse(ref)
        val response = client.requestManifest(imageRef)
        check(response.status.isSuccess()) { "Failed to fetch manifest: ${response.status}" }

        val body = response.bodyAsText()
        val contentType = response.headers[HttpHeaders.ContentType] ?: ""
        val json = Json.parseToJsonElement(body).jsonObject

        val isIndex = client.isOciImageIndex(json, contentType)
        val isManifest = client.isOciImageManifest(json)
        check(isIndex || isManifest) { "Reference points to neither an index nor a manifest (Content-Type: $contentType)" }
        check(isIndex || !requireIndex) { "--fail was requested and reference points to a non-index." }

        if (raw) return body // pass through whatever we got, regardless of whether we recognize it as an index

        return if (isIndex) {
            formatTsvIndex(body, selector)
        } else { // infer basic index info
            val digest = response.headers["Docker-Content-Digest"] ?: ""
            val mediaType = contentType
            "digest\tmediaType\tos\tarchitecture\tos.version\tos.features\tvariant\n" +
                    "$digest\t$mediaType\t\t\t\t\t"
        }
    }

}

/**
 * Command to fetch referrers for an image.
 */
class ReferrersCommand(
    private val globalRaw: () -> Boolean,
    private val meta: () -> MetaCommand? = { null }
) : CliktCommand(name = "referrers") {
    override fun help(context: Context): String = "Fetch referrers for an image"

    val type by option("--type", help = "Filter referrers by artifact type")
    val scrape by option("--scrape", help = "Force scrape mode: regex used to discover referrers by scraping tags")
    val ref by argument(help = "The OCI image reference (e.g., registry-1.docker.io/library/alpine:latest)")

    @OptIn(ExperimentalForeignApi::class)
    override fun run() {
        val selector = meta()?.platformSelector ?: PlatformSelector()

        runBlocking {
            val raw = globalRaw()
            val output = OciClient().use { client ->
                val imageRef = OciRef.parse(ref)

                val digestRef = when {
                    imageRef.isDigest -> imageRef
                    selector.hasConstraints() -> client.resolveToImageManifest(imageRef, selector).also { check(it.isDigest) }
                    else -> {
                        val response = client.requestManifest(imageRef)
                        check(response.status.isSuccess()) { "Failed to fetch manifest: ${response.status}" }
                        val digest = response.headers["Docker-Content-Digest"]
                            ?: ("sha256:" + SHA256().digest(response.bodyAsBytes()).toHexString())
                        imageRef.withDigest(digest)
                    }
                }

                val scrapedTagsRegex = scrape
                val referrers = if (scrapedTagsRegex != null) {
                    client.scrapeReferrers(digestRef, scrapedTagsRegex, type)
                } else {
                    client.fetchReferrers(digestRef, type)
                }
                if (raw) formatPrettyJson(referrers) else formatTsvReferrers(referrers.toString())
            }
            println(output)
        }
    }
}

/**
 * Command to fetch the manifest for a specific platform.
 */
class ManifestCommand(
    private val globalRaw: () -> Boolean,
    private val meta: () -> MetaCommand? = { null }
) : CliktCommand(name = "manifest") {
    override fun help(context: Context): String = "Fetch the manifest for a specific platform"

    val ref by argument(help = "The OCI image reference (e.g., registry-1.docker.io/library/alpine:latest)")

    @OptIn(ExperimentalForeignApi::class)
    override fun run() {
        val selector = meta()?.platformSelector ?: PlatformSelector()

        runBlocking {
            val raw = globalRaw()
            val output = OciClient().use { client ->
                val imageRef = client.resolveToImageManifest(OciRef.parse(ref), selector)
                val manifestResponse = client.requestManifest(imageRef)
                check(manifestResponse.status.isSuccess()) { "Failed to fetch manifest: ${manifestResponse.status}" }
                val manifestStr = manifestResponse.bodyAsText()

                if (raw) manifestStr else formatTsvManifest(manifestStr)
            }
            println(output)
        }
    }
}

/**
 * Command to fetch the config for a specific platform.
 */
class ConfigCommand(
    private val globalRaw: () -> Boolean,
    private val meta: () -> MetaCommand? = { null }
) : CliktCommand(name = "config") {
    override fun help(context: Context): String = "Fetch the config for a specific platform"

    val ref by argument(help = "The OCI image reference (e.g., registry-1.docker.io/library/alpine:latest)")

    @OptIn(ExperimentalForeignApi::class)
    override fun run() {
        val meta = meta()
        val selector = meta?.platformSelector ?: PlatformSelector()

        runBlocking {
            val raw = globalRaw()
            val output = OciClient().use { client ->
                val ref = OciRef.parse(this@ConfigCommand.ref)
                val imageRef = client.resolveToImageManifest(ref, selector)
                val config = client.fetchAllMetadata(imageRef).images.single().config
                check(config!=null) { "Manifest has no config" }

                if (raw) config else formatPrettyJson(config)
            }
            println(output)
        }
    }

}

/**
 * Command to fetch a URL with OCI authentication.
 */
class GetCommand(private val globalRaw: () -> Boolean) : CliktCommand(name = "get") {
    override fun help(context: Context): String = "Get the url but supports the anonymous auth methods of container registries"
    val url by argument(help = "The URL to fetch")

    @OptIn(ExperimentalForeignApi::class)
    override fun run() {
        runBlocking {
            val raw = globalRaw()
            val output = OciClient().use { client ->
                queryAndFormat(client, raw)
            }
            println(output)
        }
    }

    private suspend fun queryAndFormat(client: OciClient, raw: Boolean): String {
        val response = client.requestUrl(url, null)
        val body = response.bodyAsText()
        if (raw) return body

        try {
            val json = Json.parseToJsonElement(body)
            val prettyJson = Json { prettyPrint = true }
            return prettyJson.encodeToString(JsonElement.serializer(), json)
        } catch (e: Exception) { // Not JSON, just return body
            return body
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
            val output = OciClient().use { client ->
                queryAndFormat(client, raw)
            }
            println(output)
        }
    }

    private suspend fun queryAndFormat(client: OciClient, raw: Boolean): String {
        val ref = OciRef.parse(repositoryRef)
        return if (raw) {
            val response = client.requestTags(ref)
            response.bodyAsText()
        } else {
            val tags = client.fetchTagsList(ref)
            tags.joinToString("\n")
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
fun readStdin(): String = buildString {
    memScoped {
        val bufferSize = 4096
        val buffer = allocArray<ByteVar>(bufferSize)
        while (feof(stdin) == 0) {
            val result = fgets(buffer, bufferSize, stdin)
            if (result != null) {
                append(result.toKString())
            }
        }
    }
}

/**
 * Command for parse subcommands.
 */
class ParseCommand : CliktCommand(name = "parse") {
    override fun help(context: Context): String = "Parse and pretty-print OCI objects from stdin"
    override fun run() {
        if (currentContext.invokedSubcommand == null) {
            echo(getFormattedHelp())
            platform.posix.exit(0)
        }
    }
}

class ParseFormatCommand(
    name: String,
    private val helpText: String,
    protected val globalRaw: () -> Boolean,
    private val formatter: (String) -> String
) : CliktCommand(name = name) {
    override fun help(context: Context): String = helpText
    override fun run() {
        if (globalRaw()) {
            throw UsageError("--raw is not supported for parse command")
        }
        val input = readStdin()
        if (input.isBlank()) return
        println(formatter(input))
    }
}

fun main(args: Array<String>) {
    val ociFetch = OciFetch()
    val metaCommand = MetaCommand()

    val raw = { ociFetch.raw }
    ociFetch.subcommands(
        GetCommand(globalRaw = raw),
        TagsCommand(globalRaw = raw),
        ParseCommand().subcommands(
            ParseFormatCommand(name = "index", helpText = "Parse an index from stdin", globalRaw = raw, formatter = { formatTsvIndex(it) }),
            ParseFormatCommand(name = "manifest", helpText = "Parse a manifest from stdin", globalRaw = raw, formatter = { formatTsvManifest(it) }),
            ParseFormatCommand(name = "config", helpText = "Parse a config from stdin", globalRaw = raw, formatter = { formatPrettyJson(it) })
        ),
        metaCommand.subcommands(
            IndexCommand(globalRaw = raw, meta = { metaCommand }),
            ManifestCommand(globalRaw = raw, meta = { metaCommand }),
            ConfigCommand(globalRaw = raw, meta = { metaCommand }),
            ReferrersCommand(globalRaw = raw, meta = { metaCommand })
        )
    )

    try {
        ociFetch.parse(args)
    } catch (_: PrintHelpMessage) {
        ociFetch.echo(ociFetch.getFormattedHelp())
        platform.posix.exit(0)
    } catch (e: CliktError) {
        ociFetch.echo(e.message ?: "Error: command line processing for ${e::class.simpleName}", err = true)
        platform.posix.exit(1)
    } catch (e: NoSuchPlatformSelectionException) {
        ociFetch.echo(platformSelectionError(e, e.available), err = true)
        platform.posix.exit(11)
    } catch (e: AmbiguousPlatformSelectionException) {
        ociFetch.echo(platformSelectionError(e, e.candidates), err = true)
        platform.posix.exit(12)
    } catch (e: Exception) {
        ociFetch.echo("Error: " + (e.message ?: "Unknown error") , err = true)
        platform.posix.exit(10)
    }
}


private fun platformSelectionError(e: Exception, platforms: Collection<Platform>): String {
    val options = platforms
        .asSequence()
        .filter { it.isValid() }
        .map { platform -> platform.toCliOptionsString() }
        .sorted()
        .distinct()
        .joinToString("\n")

    return "${e.message}\n\n$options"
}

private fun Platform.toCliOptionsString(): String = buildString {
    append("--architecture $arch")
    append(" --os $osName")
    variant?.let { append(" --variant $it") }
    osVersion?.let { append(" --os-version $it") }
    osFeatures.forEach { append(" --os-features $it") }
}

