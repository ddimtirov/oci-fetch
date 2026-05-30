package oci

import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.takeFrom

/**
 * Extracts the URL of the next OCI API page from the `Link` header of a response,
 * following the [OCI Distribution Spec pagination](https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pagination) convention.
 *
 * The `Link` header may contain a relative or absolute URL pointing to the next page of results.
 * This function normalises the URL into an absolute `https` URL using the following rules:
 * - **Absolute URL** (`https://…` or `http://…`) — returned as-is.
 * - **Absolute path** (`/v2/…`) — prepended with `https://<registry>`.
 * - **Relative `v2/` path** (`v2/…`) — prepended with `https://<registry>/`.
 * - **Query-only** (`?key=value`) — appended to `https://<registry>/v2/<v2endpoint>`.
 *
 * Returns `null` when the response contains no `Link` header with `rel="next"`.
 *
 * @param registry The registry host, e.g. `"registry.example.com"`.
 * @param v2endpoint The endpoint path after `/v2/`, e.g. `"library/alpine/tags/list"`.
 * @return The absolute URL of the next page, or `null` if there is no next page.
 * @throws IllegalStateException if the `Link` header contains an unsupported URL format.
 */
fun HttpResponse.nextPageUrl(
    registry: String,
    v2endpoint: String
): String? {
    val link = firstLinkHeader(this) ?: return null
    return try {
        val base = this.call.request.url
        URLBuilder().takeFrom(base).takeFrom(link).buildString()
    } catch (e: Exception) {
        // Fallback robust resolution logic in case URLBuilder fails under certain platform conditions
        when {
            link.startsWith("https://") || link.startsWith("http://") -> link
            link.startsWith("/") -> "https://$registry$link"
            link.startsWith("v2/") -> "https://$registry/$link"
            link.startsWith("?") -> "https://$registry/v2/$v2endpoint$link"
            else -> error("Unsupported pagination URL in Link header: $link")
        }
    }
}

private fun firstLinkHeader(response: HttpResponse): String? {
    val linkHeaders = response.headers.getAll(HttpHeaders.Link) ?: return null

    for (header in linkHeaders) {
        val parts = header.split(',')
        for (part in parts) {
            val segments = part.split(';')
            if (segments.isEmpty()) continue
            val linkPart = segments[0].trim()
            if (!linkPart.startsWith("<") || !linkPart.endsWith(">")) continue
            val url = linkPart.removePrefix("<").removeSuffix(">")

            // Look for rel="next" parameter
            for (i in 1 until segments.size) {
                val param = segments[i].trim()
                val kv = param.split('=', limit = 2)
                if (kv.size == 2 && kv[0].trim().equals("rel", ignoreCase = true)) {
                    val relValue = kv[1].trim().trim('"')
                    val rels = relValue.split(Regex("\\s+")).map { it.lowercase() }
                    if ("next" in rels) {
                        return url
                    }
                }
            }
        }
    }
    return null
}
