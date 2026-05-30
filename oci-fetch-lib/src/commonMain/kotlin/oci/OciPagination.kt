package oci

import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders

private val nextRelRegex = Regex("""\brel\s*=\s*"?next"?""", RegexOption.IGNORE_CASE)

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
    return firstLinkHeader(this)?.let {
        when {
            it.startsWith("https://") || it.startsWith("http://") -> it
            it.startsWith("/") -> "https://$registry$it"
            it.startsWith("v2/") -> "https://$registry/$it"
            it.startsWith("?") -> "https://$registry/v2/$v2endpoint$it"
            else -> error("Unsupported pagination URL in Link header: $it")
        }
    }
}

private fun firstLinkHeader(response: HttpResponse): String? {
    val linkHeaders = response.headers.getAll(HttpHeaders.Link) ?: return null

    val linkHeader = linkHeaders
        .flatMap { it.split(',') }
        .map { it.trim() }
        .firstOrNull { nextRelRegex.containsMatchIn(it) }
        ?: return null

    val rawUrl = linkHeader.substringBefore(';').trim()
    check(rawUrl.startsWith("<") && rawUrl.endsWith(">")) {
        "Malformed pagination Link header: $linkHeader"
    }

    return rawUrl.removePrefix("<").removeSuffix(">")
}
