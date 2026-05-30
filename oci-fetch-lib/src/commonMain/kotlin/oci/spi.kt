package oci

import io.ktor.client.HttpClient

/**
 * Creates a platform-specific HttpClient engine.
 */
internal expect fun createHttpClient(): HttpClient

/**
 * Platform-specific URL encoding function.
 */
internal expect fun urlEncode(s: String): String
