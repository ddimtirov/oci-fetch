package example

import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking

// Native entry point: builds as an executable for mingwX64
// Usage:
//   gradlew.bat linkReleaseExecutableMingwX64
//   .\\build\\bin\\mingwX64\\releaseExecutable\\oci-fetch.exe [URL]
fun main(args: Array<String>) {
    val url = args.getOrNull(0) ?: "https://httpbin.org/json"

    val client = HttpClient(Curl)
    try {
        runBlocking {
            val response = client.get(url)
            val body = response.bodyAsText()
            println(body)
        }
    } finally {
        client.close()
    }
}
