package example

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.java.Java
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking

/**
 * Small executable example: downloads JSON from an HTTP URL and prints it.
 *
 * Usage:
 *   ./gradlew runExample              # uses default URL (https://httpbin.org/json)
 *   ./gradlew runExample -Durl=...    # provide a custom URL
 */
fun main() = runBlocking {
    val url = System.getProperty("url") ?: "https://httpbin.org/json"

    HttpClient(Java).use { client ->
        val response = client.get(url)
        val body = response.bodyAsText()
        println(body)
    }
}
