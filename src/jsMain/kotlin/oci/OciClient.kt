package oci

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js

/**
 * Creates a JS-specific HttpClient engine.
 */
actual fun createHttpClient(): HttpClient = HttpClient(Js)

/**
 * JS-specific URL encoding using encodeURIComponent.
 */
internal actual fun urlEncode(s: String): String = js("encodeURIComponent(s)") as String
