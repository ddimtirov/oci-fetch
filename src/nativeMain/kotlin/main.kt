import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.runBlocking
import oci.OciClient
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.*
import kotlin.io.println

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
 * Command to list tags for a repository.
 */
class TagsCommand(private val globalRaw: () -> Boolean) : CliktCommand(name = "tags") {
    override fun help(context: Context): String = "List tags for a repository"
    val repository by argument(help = "The OCI repository (e.g., registry-1.docker.io/library/alpine)")

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
    ociFetch.subcommands(tagsCommand).main(args)
}
