package oci

import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OciClientTest {

    @Test
    fun testParseRef_simple() {
        val ref = OciClient.parseRef("registry.example.com/myapp")
        assertEquals("registry.example.com", ref.registry)
        assertEquals("myapp", ref.repository)
        assertEquals("latest", ref.reference)
    }

    @Test
    fun testParseRef_withTag() {
        val ref = OciClient.parseRef("registry.example.com/myapp:v1.0")
        assertEquals("registry.example.com", ref.registry)
        assertEquals("myapp", ref.repository)
        assertEquals("v1.0", ref.reference)
    }

    @Test
    fun testParseRef_withDigest() {
        val ref = OciClient.parseRef("registry.example.com/myapp@sha256:abc123")
        assertEquals("registry.example.com", ref.registry)
        assertEquals("myapp", ref.repository)
        assertEquals("sha256:abc123", ref.reference)
    }

    @Test
    fun testParseRef_withPath() {
        val ref = OciClient.parseRef("registry-1.docker.io/library/alpine:latest")
        assertEquals("registry-1.docker.io", ref.registry)
        assertEquals("library/alpine", ref.repository)
        assertEquals("latest", ref.reference)
    }

    @Test
    fun testFetchManifest_alpine() = runTest {
        val client = OciClient()
        try {
            val ref = OciClient.parseRef("registry-1.docker.io/library/alpine:latest")
            val response = client.fetchManifest(ref)
            
            assertTrue(response.status.value in 200..299, "Expected successful response")
            
            // Parse and validate manifest structure
            val body = response.bodyAsText()
            assertNotNull(body)
            val json = Json.parseToJsonElement(body).jsonObject
            val schemaVersion = json["schemaVersion"]?.jsonPrimitive?.content?.toIntOrNull()
            assertNotNull(schemaVersion)
            assertTrue(schemaVersion == 1 || schemaVersion == 2)
        } finally {
            client.close()
        }
    }
}
