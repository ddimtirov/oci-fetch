package oci

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class ReferrersTest {
    private val image = ImageRef("registry.example.com", "library/alpine", "latest")
    private val subjectDigest = "sha256:abcdef"
    private val tagFallback = "sha256-abcdef"

    private fun mockClient(handler: MockRequestHandler): HttpClient =
        HttpClient(MockEngine(handler))

    private val jsonContentType = "application/vnd.oci.image.index.v1+json"

    @Test
    fun apiReturnsBody_whenServerFiltered() = runTest {
        val responseJson = """{"schemaVersion":2,"manifests":[{"digest":"sha256:s","artifactType":"application/sig"}]}"""
        OciClient(mockClient { req ->
            assertTrue(req.url.encodedPath.endsWith("/v2/library/alpine/referrers/$subjectDigest"))
            assertEquals("application/sig", req.url.parameters["artifactType"])
            respond(
                content = responseJson,
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType to listOf(jsonContentType),
                    "OCI-Filters-Applied" to listOf("artifactType")
                )
            )
        }).use { client ->
            val result = client.fetchReferrers(image, subjectDigest, "application/sig", null)
            assertEquals(responseJson, result)
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
                content = responseJson,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, jsonContentType)
            )
        }).use { client ->
            val result = client.fetchReferrers(image, subjectDigest, "application/sig", null)
            val parsed = Json.parseToJsonElement(result).jsonObject
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
                req.url.encodedPath.endsWith("/manifests/$tagFallback") -> respond(
                    content = tagBody,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, jsonContentType)
                )
                req.url.encodedPath.endsWith(".sig") ||
                req.url.encodedPath.endsWith(".att") ||
                req.url.encodedPath.endsWith(".sbom") -> respond("", HttpStatusCode.NotFound)
                else -> error("Unexpected request: ${req.url}")
            }
        }).use { client ->
            val result = client.fetchReferrers(image, subjectDigest, null, null)
            val parsed = Json.parseToJsonElement(result).jsonObject
            val manifests = parsed["manifests"]!!.jsonArray
            assertEquals(1, manifests.size)
            assertEquals("sha256:x", manifests[0].jsonObject["digest"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun api404AndTag404ReturnsEmptyIndex() = runTest {
        OciClient(mockClient { _ -> respond("", HttpStatusCode.NotFound) }).use { client ->
            val result = client.fetchReferrers(image, subjectDigest, null, null)
            val parsed = Json.parseToJsonElement(result).jsonObject
            assertEquals(0, parsed["manifests"]!!.jsonArray.size)
        }
    }

    @Test
    fun api500Throws() = runTest {
        OciClient(mockClient { _ -> respond("oops", HttpStatusCode.InternalServerError) }).use { client ->
            assertFails { client.fetchReferrers(image, subjectDigest, null, null) }
        }
    }

    @Test
    fun tagSchemaAuthFailureThrows() = runTest {
        OciClient(mockClient { req ->
            when {
                req.url.encodedPath.contains("/referrers/") -> respond("", HttpStatusCode.NotFound)
                req.url.encodedPath.endsWith("/manifests/$tagFallback") -> respond("forbidden", HttpStatusCode.Forbidden)
                req.url.encodedPath.endsWith(".sig") ||
                req.url.encodedPath.endsWith(".att") ||
                req.url.encodedPath.endsWith(".sbom") -> respond("", HttpStatusCode.NotFound)
                else -> error("Unexpected request: ${req.url}")
            }
        }).use { client ->
            assertFails { client.fetchReferrers(image, subjectDigest, null, null) }
        }
    }

    @Test
    fun scrapeMatchesOnlySubjectDigest() = runTest {
        val tagsListJson = """{"name":"library/alpine","tags":["v1.sig","v2.sig","v3.sig"]}"""
        val matchingManifest = """{"schemaVersion":2,"mediaType":"application/vnd.oci.image.manifest.v1+json","subject":{"digest":"$subjectDigest"},"config":{"digest":"sha256:c"},"layers":[]}"""
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
            val result = client.fetchReferrers(image, subjectDigest, null, ".*\\.sig$")
            val parsed = Json.parseToJsonElement(result).jsonObject
            val manifests = parsed["manifests"]!!.jsonArray
            assertEquals(2, manifests.size)
            assertTrue(manifests.all { it.jsonObject["digest"]?.jsonPrimitive?.content == "sha256:descriptor" })
        }
    }

    @Test
    fun scrapeFiltersByArtifactType() = runTest {
        val tagsListJson = """{"name":"library/alpine","tags":["a.sig","b.sig"]}"""
        val sigManifest = """{"schemaVersion":2,"mediaType":"application/vnd.oci.image.manifest.v1+json","artifactType":"application/sig","subject":{"digest":"$subjectDigest"},"config":{"digest":"sha256:c"},"layers":[]}"""
        val sbomManifest = """{"schemaVersion":2,"mediaType":"application/vnd.oci.image.manifest.v1+json","artifactType":"application/sbom","subject":{"digest":"$subjectDigest"},"config":{"digest":"sha256:c"},"layers":[]}"""

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
            val result = client.fetchReferrers(image, subjectDigest, "application/sig", ".*\\.sig$")
            val parsed = Json.parseToJsonElement(result).jsonObject
            val manifests = parsed["manifests"]!!.jsonArray
            assertEquals(1, manifests.size)
            assertEquals("sha256:sig", manifests[0].jsonObject["digest"]?.jsonPrimitive?.content)
            assertEquals("application/sig", manifests[0].jsonObject["artifactType"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun scrapeUsesSha256FallbackWhenDigestHeaderMissing() = runTest {
        val tagsListJson = """{"name":"library/alpine","tags":["only.sig"]}"""
        val manifest = """{"schemaVersion":2,"mediaType":"application/vnd.oci.image.manifest.v1+json","subject":{"digest":"$subjectDigest"},"config":{"digest":"sha256:c"},"layers":[]}"""
        val expectedDigest = "sha256:" + sha256Hex(manifest.encodeToByteArray())

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
            val result = client.fetchReferrers(image, subjectDigest, null, ".*\\.sig$")
            val parsed = Json.parseToJsonElement(result).jsonObject
            val manifests = parsed["manifests"]!!.jsonArray
            assertEquals(1, manifests.size)
            assertEquals(expectedDigest, manifests[0].jsonObject["digest"]?.jsonPrimitive?.content)
        }
    }
}
