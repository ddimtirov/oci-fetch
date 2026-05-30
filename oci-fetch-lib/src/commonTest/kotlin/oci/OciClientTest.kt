package oci

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.fullPath
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import oci.testing.OciJsonSchemas
import org.kotlincrypto.hash.sha2.SHA256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

private const val SUBJECT_DIGEST = "sha256:abcdef"
private const val TAG_FALLBACK = "sha256-abcdef"
private const val OCI_IMAGE_CONTENT_TYPE = "application/vnd.oci.image.index.v1+json"
private val DIGEST_IMAGE = OciRef("registry.example.com", "library/alpine", SUBJECT_DIGEST, true)

class OciClientTest {

    @Test
    fun bearerTokenUrlParsesHeader() {
        assertEquals(
            "https://auth.docker.io/token?service=registry.docker.io&scope=repository%3Alibrary%2Falpine%3Apull",
            bearerTokenUrl("Bearer realm=\"https://auth.docker.io/token\",service=\"registry.docker.io\",scope=\"repository:library/alpine:pull\""),
        )
    }

    @Test
    fun bearerTokenUrlRejectsWrongSchema() {
        assertFailsWith<IllegalStateException> {
            bearerTokenUrl("Basic realm=\"https://auth.example.com/token\",service=\"registry.example.com\"")
        }
    }

    @Test
    fun requestUrlUsesBearerPluginToFetchTokenAndRetry() = runTest {
        var resourceRequests = 0
        var tokenRequests = 0
        val client = OciClient(mockClientWithBearerAuth { req ->
            when {
                req.url.fullPath.startsWith("/v2/library/alpine/manifests/latest") -> {
                    resourceRequests += 1
                    val authHeader = req.headers[HttpHeaders.Authorization]
                    if (authHeader == null) {
                        respond(
                            content = "unauthorized",
                            status = HttpStatusCode.Unauthorized,
                            headers = headersOf(
                                HttpHeaders.WWWAuthenticate,
                                "Bearer realm=\"https://auth.example.com/token\",service=\"registry.example.com\",scope=\"repository:library/alpine:pull\""
                            ),
                        )
                    } else {
                        assertEquals("Bearer test-token", authHeader)
                        respond(
                            content = "{\"schemaVersion\":2,\"config\":{}}",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/vnd.oci.image.manifest.v1+json"),
                        )
                    }
                }

                req.url.host == "auth.example.com" -> {
                    tokenRequests += 1
                    assertNull(req.headers[HttpHeaders.Authorization])
                    respond(
                        content = "{\"token\":\"test-token\"}",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

                else -> error("Unexpected request: ${req.url}")
            }
        })

        client.use {
            val response = it.requestUrl("https://registry.example.com/v2/library/alpine/manifests/latest", OciClientImpl.acceptManifest)
            assertEquals(HttpStatusCode.OK, response.status)
        }

        assertEquals(2, resourceRequests)
        assertEquals(1, tokenRequests)
    }

    @Test
    fun testClientInstantiation() = runTest {
        val client = OciClient()
        client.use {
            assertEquals(client, it)
        }
        // can't assert, but the inner http client is closed
    }

    @Test
    fun externalHttpClientInstantiation() = runTest {
        val http = HttpClient(MockEngine { respond("ok") })
        val client = OciClient(http)
        assertTrue(http.isActive, "From the moment the OCI client is created, the http client should be active.")
        client.use {
            assertTrue(http.isActive, "The http client should remain active while the OCI client is used.")
        }
        assertTrue(http.isActive, "Externally created http client should remain active after the OCI client is closed.")

        client.close()
        assertTrue(http.isActive, "Externally created http client is not closed when we close the OCI client.")


        http.close()
        repeat(10) { // poor man's awaitility
            if (!http.isActive) return@repeat
            delay(100.milliseconds)
        }
        assertFalse(http.isActive, "Explicitly closing the http client should work.")
    }

    @Test
    fun testIsOciIndex() {
        OciClient().use { client ->
            assertTrue(client.isOciImageIndex(json("{}"), "application/vnd.oci.image.index.v1+json"))
            assertTrue(client.isOciImageIndex(json("{}"), "application/vnd.docker.distribution.manifest.list.v2+json"))
            assertFalse(client.isOciImageIndex(null, "application/vnd.oci.image.index.v1+json"))
            assertFalse(client.isOciImageIndex(null, "application/vnd.docker.distribution.manifest.list.v2+json"))
            assertTrue(client.isOciImageIndex(json("""{"manifests": []}"""), "text/plain"))
        }
    }

    @Test
    fun testIsOciManifest() {
        OciClient().use { client ->
            assertTrue(client.isOciImageManifest(json("""{"layers": []}""")))
            assertTrue(client.isOciImageManifest(json("""{"config": {}}""")))
            assertTrue(client.isOciImageManifest(json("""{"subject": {}}""")))
            assertTrue(client.isOciImageManifest(json("""{"fsLayers": {}}""")))
            assertFalse(client.isOciImageManifest(json("""{"layer": {}}""")))
            assertFalse(client.isOciImageManifest(json("""{"manifest": {}}""")))
        }
    }

    @Test
    fun fetchTagsFollowsPaginationLinkHeader() = runTest {
        val observedLastParams = mutableListOf<String?>()

        OciClient(mockClient { req ->
            observedLastParams += req.url.parameters["last"]
            when (req.url.parameters["last"]) {
                null -> respond(
                    content = """{"name":"library/alpine","tags":["1","2"]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(
                        HttpHeaders.ContentType to listOf("application/json"),
                        HttpHeaders.Link to listOf("</v2/library/alpine/tags/list?n=2&last=2>; rel=\"next\"")
                    )
                )

                "2" -> {
                    assertEquals("2", req.url.parameters["n"])
                    respond(
                        content = """{"name":"library/alpine","tags":["3"]}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }

                else -> error("Unexpected request: ${req.url}")
            }
        }).use { client ->
            val tags = client.fetchAllTags("registry.example.com", "library/alpine")
            assertEquals(listOf("1", "2", "3"), tags)
            assertEquals(listOf(null, "2"), observedLastParams)
        }
    }

    @Test
    fun apiReturnsBody_whenServerFiltered() = runTest {
        val responseJson = """{"schemaVersion":2,"manifests":[{"mediaType":"application/vnd.oci.image.manifest.v1+json","digest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","size":1,"artifactType":"application/sig"}]}"""
        val expectedJson = """{"schemaVersion":2,"mediaType":"application/vnd.oci.image.index.v1+json","manifests":[{"mediaType":"application/vnd.oci.image.manifest.v1+json","digest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","size":1,"artifactType":"application/sig"}]}"""

        OciClient(mockClient { req ->
            assertTrue(req.url.encodedPath.endsWith("/v2/library/alpine/referrers/$SUBJECT_DIGEST"))
            assertEquals("application/sig", req.url.parameters["artifactType"])
            respond(
                content = responseJson,
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType to listOf(OCI_IMAGE_CONTENT_TYPE),
                    "OCI-Filters-Applied" to listOf("artifactType")
                )
            )
        }).use { client ->
            val result = client.fetchReferrers(DIGEST_IMAGE, "application/sig")
            assertEquals(Json.parseToJsonElement(expectedJson).jsonObject, result)
            assertValidImageIndex(result, "apiReturnsBody_whenServerFiltered")
        }
    }

    @Test
    fun apiResponseFilteredLocally_whenHeaderMissing() = runTest {
        val responseJson = """{"schemaVersion":2,"manifests":[
            {"mediaType":"application/vnd.oci.image.manifest.v1+json","digest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","size":1,"artifactType":"application/sig"},
            {"mediaType":"application/vnd.oci.image.manifest.v1+json","digest":"sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","size":2,"artifactType":"application/sbom"}
        ]}"""

        OciClient(mockClient { _ ->
            respond(
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, OCI_IMAGE_CONTENT_TYPE),
                content = responseJson
            )
        }).use { client ->
            val parsed = client.fetchReferrers(DIGEST_IMAGE, "application/sig")
            val manifests = parsed["manifests"]!!.jsonArray
            assertEquals(1, manifests.size)
            assertEquals("application/sig", manifests[0].jsonObject["artifactType"]?.jsonPrimitive?.content)
            assertValidImageIndex(parsed, "apiResponseFilteredLocally_whenHeaderMissing")
        }
    }

    @Test
    fun referrersApiPaginatesMergesManifests() = runTest {
        val page1 = """{"schemaVersion":2,"manifests":[{"mediaType":"application/vnd.oci.image.manifest.v1+json","digest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","size":1,"artifactType":"application/sig"}]}"""
        val page2 = """{"schemaVersion":2,"manifests":[{"mediaType":"application/vnd.oci.image.manifest.v1+json","digest":"sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","size":2,"artifactType":"application/sbom"}]}"""

        OciClient(mockClient { req ->
            val path = req.url.encodedPath
            val query = req.url.encodedQuery
            when {
                path.contains("/referrers/") && query.isEmpty() -> respond(
                    content = page1,
                    status = HttpStatusCode.OK,
                    headers = headersOf(
                        HttpHeaders.ContentType to listOf(OCI_IMAGE_CONTENT_TYPE),
                        HttpHeaders.Link to listOf("</v2/library/alpine/referrers/$SUBJECT_DIGEST?n=1&next=token>; rel=\"next\"")
                    )
                )
                path.contains("/referrers/") && query.contains("next=token") -> respond(
                    content = page2,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, OCI_IMAGE_CONTENT_TYPE)
                )
                else -> error("Unexpected request: ${req.url}")
            }
        }).use { client ->
            val result = client.fetchReferrers(DIGEST_IMAGE)
            val manifests = result["manifests"]!!.jsonArray
            assertEquals(2, manifests.size)
            assertEquals("sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", manifests[0].jsonObject["digest"]?.jsonPrimitive?.content)
            assertEquals("sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", manifests[1].jsonObject["digest"]?.jsonPrimitive?.content)
            assertValidImageIndex(result, "referrersApiPaginatesMergesManifests")
        }
    }

    @Test
    fun referrersApiPaginationDetectsLoop() = runTest {
        val page = """{"schemaVersion":2,"manifests":[{"digest":"sha256:a","artifactType":"application/sig"}]}"""
        val selfLinkUrl = "/v2/library/alpine/referrers/$SUBJECT_DIGEST"

        OciClient(mockClient { _ ->
            respond(
                content = page,
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType to listOf(OCI_IMAGE_CONTENT_TYPE),
                    HttpHeaders.Link to listOf("<$selfLinkUrl>; rel=\"next\"")
                )
            )
        }).use { client ->
            assertFails {
                client.fetchReferrers(DIGEST_IMAGE)
            }
        }
    }

    @Test
    fun api404FallsBackToTagSchema() = runTest {
        val tagBody = """{"schemaVersion":2,"manifests":[{"mediaType":"application/vnd.oci.image.manifest.v1+json","digest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","size":1}]}"""

        OciClient(mockClient { req ->
            when {
                req.url.encodedPath.contains("/referrers/") -> respond("", HttpStatusCode.NotFound)
                req.url.encodedPath.endsWith("/manifests/$TAG_FALLBACK") -> respond(
                    content = tagBody,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, OCI_IMAGE_CONTENT_TYPE)
                )
                req.url.encodedPath.endsWith(".sig") ||
                        req.url.encodedPath.endsWith(".att") ||
                        req.url.encodedPath.endsWith(".sbom") -> respond("", HttpStatusCode.NotFound)
                else -> error("Unexpected request: ${req.url}")
            }
        }).use { client ->
            val parsed = client.fetchReferrers(DIGEST_IMAGE)
            val manifests = parsed["manifests"]!!.jsonArray
            assertEquals(1, manifests.size)
            assertEquals("sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", manifests[0].jsonObject["digest"]?.jsonPrimitive?.content)
            assertValidImageIndex(parsed, "api404FallsBackToTagSchema")
        }
    }

    @Test
    fun api404AndTag404ReturnsEmptyIndex() = runTest {
        OciClient(mockClient { _ -> respond("", HttpStatusCode.NotFound) }).use { client ->
            val parsed = client.fetchReferrers(DIGEST_IMAGE)
            assertEquals(0, parsed["manifests"]!!.jsonArray.size)
            assertValidImageIndex(parsed, "api404AndTag404ReturnsEmptyIndex")
        }
    }

    @Test
    fun api500Throws() = runTest {
        OciClient(mockClient { _ -> respond("oops", HttpStatusCode.InternalServerError) }).use { client ->
            assertFails {
                client.fetchReferrers(DIGEST_IMAGE)
            }
        }
    }

    @Test
    fun tagSchemaAuthFailureThrows() = runTest {
        OciClient(mockClient { req ->
            when {
                req.url.encodedPath.contains("/referrers/") -> respond("", HttpStatusCode.NotFound)
                req.url.encodedPath.endsWith("/manifests/$TAG_FALLBACK") -> respond("forbidden", HttpStatusCode.Forbidden)
                req.url.encodedPath.endsWith(".sig") ||
                        req.url.encodedPath.endsWith(".att") ||
                        req.url.encodedPath.endsWith(".sbom") -> respond("", HttpStatusCode.NotFound)
                else -> error("Unexpected request: ${req.url}")
            }
        }).use { client ->
            assertFails {
                client.fetchReferrers(DIGEST_IMAGE)
            }
        }
    }

    @Test
    fun scrapeFiltersByArtifactType() = runTest {
        val tagsListJson = """{"name":"library/alpine","tags":["a.sig","b.sig"]}"""
        val sigManifest = """{"schemaVersion":2,"mediaType":"application/vnd.oci.image.manifest.v1+json","artifactType":"application/sig","subject":{"digest":"$SUBJECT_DIGEST"},"config":{"digest":"sha256:c"},"layers":[]}"""
        val sbomManifest = """{"schemaVersion":2,"mediaType":"application/vnd.oci.image.manifest.v1+json","artifactType":"application/sbom","subject":{"digest":"$SUBJECT_DIGEST"},"config":{"digest":"sha256:c"},"layers":[]}"""

        OciClient(mockClient { req ->
            when {
                req.url.encodedPath.endsWith("/tags/list") -> respond(
                    content = tagsListJson,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
                req.url.encodedPath.endsWith("/manifests/a.sig") -> respond(
                    content = sigManifest,
                    status = HttpStatusCode.OK,
                    headers = headersOf(
                        HttpHeaders.ContentType to listOf("application/vnd.oci.image.manifest.v1+json"),
                        "Docker-Content-Digest" to listOf("sha256:sig")
                    )
                )
                req.url.encodedPath.endsWith("/manifests/b.sig") -> respond(
                    content = sbomManifest,
                    status = HttpStatusCode.OK,
                    headers = headersOf(
                        HttpHeaders.ContentType to listOf("application/vnd.oci.image.manifest.v1+json"),
                        "Docker-Content-Digest" to listOf("sha256:sbom")
                    )
                )
                else -> error("Unexpected request: ${req.url}")
            }
        }).use { client ->
            val parsed = client.scrapeReferrers(DIGEST_IMAGE, Regex(".*\\.sig$"), Regex("application/sig"))
            val manifests = parsed["manifests"]!!.jsonArray
            assertEquals(1, manifests.size)
            assertEquals("sha256:sig", manifests[0].jsonObject["digest"]?.jsonPrimitive?.content)
            assertEquals("application/sig", manifests[0].jsonObject["artifactType"]?.jsonPrimitive?.content)
            assertValidImageIndex(parsed, "scrapeFiltersByArtifactType")
        }
    }

    @Test
    fun httpCacheSendsIfNoneMatchOnSubsequentRequests() = runTest {
        var requestCount = 0
        val client = OciClient(mockClientWithCache { req ->
            requestCount++
            when {
                req.url.encodedPath.endsWith("/v2/library/alpine/manifests/latest") -> {
                    val ifNoneMatch = req.headers[HttpHeaders.IfNoneMatch]
                    if (ifNoneMatch == "\"etag-123\"") {
                        respond(
                            content = "",
                            status = HttpStatusCode.NotModified,
                            headers = headersOf(
                                HttpHeaders.ETag to listOf("\"etag-123\""),
                                HttpHeaders.CacheControl to listOf("must-revalidate"),
                            ),
                        )
                    } else {
                        respond(
                            content = "{\"schemaVersion\":2,\"config\":{}}",
                            status = HttpStatusCode.OK,
                            headers = headersOf(
                                HttpHeaders.ContentType to listOf("application/vnd.oci.image.manifest.v1+json"),
                                HttpHeaders.ETag to listOf("\"etag-123\""),
                                HttpHeaders.CacheControl to listOf("must-revalidate"),
                            ),
                        )
                    }
                }
                else -> error("Unexpected request: ${req.url}")
            }
        })

        client.use {
            val first = it.requestManifest("registry.example.com", "library/alpine", "latest")
            assertEquals(HttpStatusCode.OK, first.status)

            val second = it.requestManifest("registry.example.com", "library/alpine", "latest")
            assertEquals(HttpStatusCode.OK, second.status)
            assertEquals("{\"schemaVersion\":2,\"config\":{}}", second.bodyAsText())
        }

        assertEquals(2, requestCount)
    }

    @Test
    fun httpCacheSendsIfModifiedSinceOnSubsequentRequests() = runTest {
        var requestCount = 0
        val lastModified = "Wed, 13 May 2026 00:00:00 GMT"
        val client = OciClient(mockClientWithCache { req ->
            requestCount++
            when {
                req.url.encodedPath.endsWith("/v2/library/alpine/manifests/latest") -> {
                    val ifModifiedSince = req.headers[HttpHeaders.IfModifiedSince]
                    if (ifModifiedSince != null) {
                        respond(
                            content = "",
                            status = HttpStatusCode.NotModified,
                            headers = headersOf(
                                HttpHeaders.LastModified to listOf(lastModified),
                                HttpHeaders.CacheControl to listOf("must-revalidate"),
                            ),
                        )
                    } else {
                        respond(
                            content = "{\"schemaVersion\":2,\"config\":{}}",
                            status = HttpStatusCode.OK,
                            headers = headersOf(
                                HttpHeaders.ContentType to listOf("application/vnd.oci.image.manifest.v1+json"),
                                HttpHeaders.LastModified to listOf(lastModified),
                                HttpHeaders.CacheControl to listOf("must-revalidate"),
                            ),
                        )
                    }
                }
                else -> error("Unexpected request: ${req.url}")
            }
        })

        client.use {
            val first = it.requestManifest("registry.example.com", "library/alpine", "latest")
            assertEquals(HttpStatusCode.OK, first.status)

            val second = it.requestManifest("registry.example.com", "library/alpine", "latest")
            assertEquals(HttpStatusCode.OK, second.status)
            assertEquals("{\"schemaVersion\":2,\"config\":{}}", second.bodyAsText())
        }

        assertEquals(2, requestCount)
    }

    @Test
    fun scrapeUsesSha256FallbackWhenDigestHeaderMissing() = runTest {
        val tagsListJson = """{"name":"library/alpine","tags":["only.sig"]}"""
        val manifest = """{"schemaVersion":2,"mediaType":"application/vnd.oci.image.manifest.v1+json","subject":{"digest":"$SUBJECT_DIGEST"},"config":{"digest":"sha256:c"},"layers":[]}"""
        val expectedDigest = "sha256:" + SHA256().digest(manifest.encodeToByteArray()).toHexString()

        OciClient(mockClient { req ->
            when {
                req.url.encodedPath.endsWith("/tags/list") -> respond(
                    content = tagsListJson,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
                req.url.encodedPath.endsWith("/manifests/only.sig") -> respond(
                    content = manifest,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/vnd.oci.image.manifest.v1+json")
                )
                else -> error("Unexpected request: ${req.url}")
            }
        }).use { client ->
            val parsed = client.scrapeReferrers(DIGEST_IMAGE, Regex(".*\\.sig$"))
            val manifests = parsed["manifests"]!!.jsonArray
            assertEquals(1, manifests.size)
            assertEquals(expectedDigest, manifests[0].jsonObject["digest"]?.jsonPrimitive?.content)
            assertValidImageIndex(parsed, "scrapeUsesSha256FallbackWhenDigestHeaderMissing")
        }
    }

    private fun mockClient(handler: MockRequestHandler): HttpClient = HttpClient(MockEngine(handler))

    private fun mockClientWithBearerAuth(handler: MockRequestHandler): HttpClient = HttpClient(MockEngine(handler)) {
        installOciBearerTokenAuth()
    }

    private fun mockClientWithCache(handler: MockRequestHandler): HttpClient = HttpClient(MockEngine(handler)) {
        installHttpCache()
    }

    private fun json(string: String): JsonObject = Json.parseToJsonElement(string).jsonObject

    private fun assertValidImageIndex(value: JsonObject, context: String) {
        val validation = OciJsonSchemas.imageIndex.validate(value)
        assertTrue(validation.valid, "Expected OCI image index schema validity in $context: ${validation.diagnostics}")
    }
}
