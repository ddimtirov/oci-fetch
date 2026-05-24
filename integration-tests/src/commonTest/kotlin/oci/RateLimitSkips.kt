package oci

import io.ktor.http.HttpStatusCode

class TestSkipException(message: String) : RuntimeException(message)

internal fun skipIfDockerHubRateLimited(
    ref: OciRef,
    status: HttpStatusCode,
    body: String,
    requestDescription: String? = null,
) {
    if (
        ref.registry == "registry-1.docker.io" &&
        status == HttpStatusCode.TooManyRequests &&
        body.contains("\"TOOMANYREQUESTS\"")
    ) {
        val details = requestDescription?.let { "\n$it" } ?: ""
        skipTest("Docker Hub rate limit encountered for ${ref}$details")
    }
}
