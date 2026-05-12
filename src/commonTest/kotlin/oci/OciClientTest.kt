package oci

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.kotlincrypto.hash.sha2.SHA256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

private const val SUBJECT_DIGEST = "sha256:abcdef"
private const val TAG_FALLBACK = "sha256-abcdef"
private const val OCI_IMAGE_CONTENT_TYPE = "application/vnd.oci.image.index.v1+json"
private val DIGEST_IMAGE = OciRef("registry.example.com", "library/alpine", SUBJECT_DIGEST, true)

class OciClientTest {

    @Test
    fun testClientInstantiation() = runTest {
        val client = OciClient()
        client.use {
            assertEquals(client, it)
        }
        // can't assert, but the inner http client is closed
    }

    @Test
    fun testExternalHttpClientInstantiation() = runTest {
        val http = HttpClient(MockEngine { respond("ok") })
        val client = OciClient(http)
        assertTrue(http.isActive)
        client.use {
            assertTrue(http.isActive)
        }
        assertTrue(http.isActive)

        http.close()
        repeat(10) { // poor man's awaitility
            if (!http.isActive) return@repeat
            delay(100.milliseconds)
        }
        assertFalse(http.isActive)
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
            assertTrue(client.isOciImageManifest(json("""{"layer": {}}""")))
            assertTrue(client.isOciImageManifest(json("""{"config": {}}""")))
            assertTrue(client.isOciImageManifest(json("""{"subject": {}}""")))
            assertTrue(client.isOciImageManifest(json("""{"fsLayers": {}}""")))
            assertFalse(client.isOciImageManifest(json("""{"manifest": {}}""")))
        }
    }

    @Test
    fun apiReturnsBody_whenServerFiltered() = runTest {
        val responseJson = """{"schemaVersion":2,"manifests":[{"digest":"sha256:s","artifactType":"application/sig"}]}"""

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
            assertEquals(Json.parseToJsonElement(responseJson).jsonObject, result)
        }
    }

    @Test
    fun apiResponseFilteredLocally_whenHeaderMissing() = runTest {
        val responseJson = """{"schemaVersion":2,"manifests":[
            {"digest":"sha256:a","artifactType":"application/sig"},
            {"digest":"sha256:b","artifactType":"application/sbom"}
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
        }
    }

    @Test
    fun api404FallsBackToTagSchema() = runTest {
        val tagBody = """{"schemaVersion":2,"manifests":[{"digest":"sha256:x"}]}"""

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
            assertEquals("sha256:x", manifests[0].jsonObject["digest"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun api404AndTag404ReturnsEmptyIndex() = runTest {
        OciClient(mockClient { _ -> respond("", HttpStatusCode.NotFound) }).use { client ->
            val parsed = client.fetchReferrers(DIGEST_IMAGE)
            assertEquals(0, parsed["manifests"]!!.jsonArray.size)
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
    fun scrapeMatchesOnlySubjectDigest() = runTest {
        val tagsListJson = """{"name":"library/alpine","tags":["v1.sig","v2.sig","v3.sig"]}"""
        val matchingManifest = """{"schemaVersion":2,"mediaType":"application/vnd.oci.image.manifest.v1+json","subject":{"digest":"$SUBJECT_DIGEST"},"config":{"digest":"sha256:c"},"layers":[]}"""
        val nonMatchingManifest = """{"schemaVersion":2,"mediaType":"application/vnd.oci.image.manifest.v1+json","subject":{"digest":"sha256:other"},"config":{"digest":"sha256:c"},"layers":[]}"""

        OciClient(mockClient { req ->
            when {
                req.url.encodedPath.endsWith("/tags/list") -> respond(
                    content = tagsListJson,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
                req.url.encodedPath.endsWith("/manifests/v1.sig") ||
                        req.url.encodedPath.endsWith("/manifests/v3.sig") -> respond(
                    content = matchingManifest,
                    status = HttpStatusCode.OK,
                    headers = headersOf(
                        HttpHeaders.ContentType to listOf("application/vnd.oci.image.manifest.v1+json"),
                        "Docker-Content-Digest" to listOf("sha256:descriptor")
                    )
                )
                req.url.encodedPath.endsWith("/manifests/v2.sig") -> respond(
                    content = nonMatchingManifest,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/vnd.oci.image.manifest.v1+json")
                )
                else -> error("Unexpected request: ${req.url}")
            }
        }).use { client ->
            val parsed = client.scrapeReferrers(DIGEST_IMAGE, ".*\\.sig$")
            val manifests = parsed["manifests"]!!.jsonArray
            assertEquals(2, manifests.size)
            assertTrue(manifests.all { it.jsonObject["digest"]?.jsonPrimitive?.content == "sha256:descriptor" })
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
            val parsed = client.scrapeReferrers(DIGEST_IMAGE, ".*\\.sig$", "application/sig")
            val manifests = parsed["manifests"]!!.jsonArray
            assertEquals(1, manifests.size)
            assertEquals("sha256:sig", manifests[0].jsonObject["digest"]?.jsonPrimitive?.content)
            assertEquals("application/sig", manifests[0].jsonObject["artifactType"]?.jsonPrimitive?.content)
        }
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
            val parsed = client.scrapeReferrers(DIGEST_IMAGE, ".*\\.sig$")
            val manifests = parsed["manifests"]!!.jsonArray
            assertEquals(1, manifests.size)
            assertEquals(expectedDigest, manifests[0].jsonObject["digest"]?.jsonPrimitive?.content)
        }
    }

    private fun mockClient(handler: MockRequestHandler): HttpClient = HttpClient(MockEngine(handler))
    private fun json(string: String): JsonObject = Json.parseToJsonElement(string).jsonObject
}