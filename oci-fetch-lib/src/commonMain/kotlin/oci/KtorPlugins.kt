package oci

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun HttpClientConfig<*>.installHttpCache() {
    install(HttpCache)
}

internal fun HttpClientConfig<*>.installOciBearerTokenAuth(
    credentialsStore: CredentialsStore = CredentialsStore.default,
) {
    install(Auth) {
        bearer {
            loadTokens { null }
            refreshTokens {
                val wwwAuthenticateHeader = response.headers[HttpHeaders.WWWAuthenticate]
                checkNotNull(wwwAuthenticateHeader) { "WWW-Authenticate header is missing" }

                val tokenUrl = bearerTokenUrl(wwwAuthenticateHeader)
                val token = credentialsStore.get(tokenUrl) { url ->
                    val tokenResponse = client.get(url) {
                        markAsRefreshTokenRequest()
                    }
                    check(tokenResponse.status == HttpStatusCode.OK) {
                        "Failed to fetch token: ${tokenResponse.status}"
                    }

                    val json = Json.parseToJsonElement(tokenResponse.bodyAsText()).jsonObject
                    val t = (json["token"] ?: json["access_token"])
                        ?.jsonPrimitive
                        ?.content
                    checkNotNull(t) { "Token is missing in response $tokenResponse" }
                }
                BearerTokens(accessToken = token, refreshToken = token)
            }
        }
    }
}

internal fun bearerTokenUrl(wwwAuthenticateHeader: String): String {
    check(wwwAuthenticateHeader.contains(" ")) {
        "WWW-Authenticate header is missing schema: $wwwAuthenticateHeader"
    }

    // https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/WWW-Authenticate
    val (schema, params) = wwwAuthenticateHeader.split(" ", limit = 2).map { it.trim() }
    val challengeParams: Map<String, String> = params
        .split(',')
        .map { kvStr -> kvStr.split('=', limit = 2).map { it.trim('"') } }
        .filter { it.size == 2 && it[0].isNotEmpty() }
        .associate { it[0] to it[1] }

    val tokenUrlRealm = challengeParams["realm"]
    val tokenUrlParams = challengeParams
        .filterKeys { it != "realm" }
        .map { (k, v) -> "$k=${urlEncode(v)}" }
        .joinToString("&")

    check(tokenUrlRealm != null) { "Realm is missing in WWW-Authenticate header: $wwwAuthenticateHeader" }
    check(tokenUrlRealm.isNotEmpty()) { "Realm is empty in WWW-Authenticate header: $wwwAuthenticateHeader" }
    check(schema.equals("Bearer", ignoreCase = true)) { "Schema is not Bearer (the only one supported): $wwwAuthenticateHeader" }
    return when {
        tokenUrlParams.isEmpty() -> tokenUrlRealm
        else -> "$tokenUrlRealm?$tokenUrlParams"
    }
}
