package oci

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js

/**
 * Creates a WASM-JS-specific HttpClient engine.
 */
internal actual fun createHttpClient(): HttpClient = HttpClient(Js)

internal actual fun urlEncode(s: String): String = encodeURIComponentWasm(s)

/**
 * WASM-JS-specific URL encoding using encodeURIComponent.
 */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(s) => encodeURIComponent(s)")
private external fun encodeURIComponentWasm(s: String): String
