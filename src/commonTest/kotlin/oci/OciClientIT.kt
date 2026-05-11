package oci

import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

expect fun isBrowser(): Boolean
expect fun isNative(): Boolean

class OciClientTest {
    @Test
    fun testParseRef_simple() {
        val ref = OciRef.parse("registry.example.com/myapp")
        assertEquals("registry.example.com", ref.registry)
        assertEquals("myapp", ref.repository)
        assertEquals("latest", ref.reference)
    }

    @Test
    fun testParseRef_withTag() {
        val ref = OciRef.parse("registry.example.com/myapp:v1.0")
        assertEquals("registry.example.com", ref.registry)
        assertEquals("myapp", ref.repository)
        assertEquals("v1.0", ref.reference)
    }

    @Test
    fun testParseRef_withDigest() {
        val ref = OciRef.parse("registry.example.com/myapp@sha256:abc123")
        assertEquals("registry.example.com", ref.registry)
        assertEquals("myapp", ref.repository)
        assertEquals("sha256:abc123", ref.reference)
    }

    @Test
    fun testParseRef_withPath() {
        val ref = OciRef.parse("registry-1.docker.io/library/alpine:latest")
        assertEquals("registry-1.docker.io", ref.registry)
        assertEquals("library/alpine", ref.repository)
        assertEquals("latest", ref.reference)
    }

    @Test
    fun testFetchManifest_alpine() = runTest {
        if (isBrowser() || isNative()) return@runTest
        OciClient().use { client ->
            val ref = OciRef.parse("registry-1.docker.io/library/alpine:latest")
            val response = client.requestManifest(ref)

            assertTrue(response.status.isSuccess(), "Expected successful response")

            // Parse and validate manifest structure
            val body = response.bodyAsText()
            assertNotNull(body)
            val json = Json.parseToJsonElement(body).jsonObject
            val schemaVersion = json["schemaVersion"]?.jsonPrimitive?.content?.toIntOrNull()
            assertNotNull(schemaVersion)
            assertTrue(schemaVersion == 1 || schemaVersion == 2)
        }
    }
    @Test
    fun testUrlEncode() {
        val encoded = urlEncode("hello world")
        assertEquals("hello%20world", encoded)
    }

    @Test
    fun testUrlEncode_specialChars() {
        val encoded = urlEncode("a/b")
        assertEquals("a%2fb", encoded.lowercase())
    }

    @Test
    fun testClientInstantiation() = runTest {
        val client = OciClient()
        client.close()
    }

    @Test
    fun testIsOciIndex() {
        OciClient().use { client ->
            assertTrue(client.isOciImageIndex(null, "application/vnd.oci.image.index.v1+json"))
            assertTrue(client.isOciImageIndex(null, "application/vnd.docker.distribution.manifest.list.v2+json"))

            val json = Json.parseToJsonElement("""{"manifests": []}""").jsonObject
            assertTrue(client.isOciImageIndex(json, "text/plain"))
        }
    }

    @Test
    fun fetch_manifests_for_multiple_images_without_mocking() = runTest {
        // Skip on platforms that are known to fail in this environment (CORS in Browser, CIO TLS on Native)
        // We still run instantiation and URL encoding tests on all platforms.
        if (isBrowser() || isNative()) return@runTest

        val images = listOf(
            "registry.access.redhat.com/ubi8/ubi-minimal",
            "registry-1.docker.io/library/alpine",
            "registry-1.docker.io/library/nginx"
        )
        for (spec in images) {
            OciClient().use { client ->
                val ref = OciRef.parse(spec)
                val resp = client.requestManifest(ref)
                val body = runCatching { resp.bodyAsText() }.getOrNull()

                if (!resp.status.isSuccess()) {
                    val headers = resp.headers.entries().joinToString("\n") { (k, v) -> "$k: ${v.joinToString()}" }
                    val msg = buildString {
                        appendLine("Failed for $spec")
                        appendLine("Unexpected status ${resp.status}")
                        appendLine("URL: https://${ref.registry}/v2/${ref.repository}/manifests/${ref.reference}")
                        appendLine("Headers:\n$headers")
                        appendLine("Body:\n$body")
                    }
                    throw AssertionError(msg)
                }

                assertNotNull(body, "Empty body for $spec")
                val json = Json.parseToJsonElement(body).jsonObject
                val schemaVersion = json["schemaVersion"]?.jsonPrimitive?.content?.toIntOrNull()
                assertNotNull(schemaVersion, "schemaVersion missing in manifest for $spec")
                assertTrue(
                    schemaVersion == 1 || schemaVersion == 2,
                    "Unexpected schemaVersion=$schemaVersion for $spec"
                )
            }
        }
    }
}
