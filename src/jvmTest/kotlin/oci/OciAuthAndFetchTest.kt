package oci

import io.ktor.http.HttpStatusCode
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class OciAuthAndFetchTest {

    private val images = listOf(
        // Red Hat UBI images
        "registry.access.redhat.com/ubi7/ubi",
        "registry.access.redhat.com/ubi7/ubi-minimal",
        "registry.access.redhat.com/ubi7/ubi-init",
        "registry.access.redhat.com/ubi8/ubi",
        "registry.access.redhat.com/ubi8/ubi-minimal",
        "registry.access.redhat.com/ubi8/ubi-micro",
        "registry.access.redhat.com/ubi8/ubi-init",
        "registry.access.redhat.com/ubi9/ubi",
        "registry.access.redhat.com/ubi9/ubi-minimal",
        "registry.access.redhat.com/ubi9/ubi-micro",
        "registry.access.redhat.com/ubi9/ubi-init",

        // Docker Hub library images
        "registry-1.docker.io/library/memcached",
        "registry-1.docker.io/library/nginx",
        "registry-1.docker.io/library/busybox",
        "registry-1.docker.io/library/alpine",
        "registry-1.docker.io/library/ubuntu",
        "registry-1.docker.io/library/redis",
        "registry-1.docker.io/library/postgres",
        "registry-1.docker.io/library/python",
        "registry-1.docker.io/library/node",
        "registry-1.docker.io/library/httpd",
    )

    @TestFactory
    fun fetch_manifests_for_multiple_images_without_mocking(): List<DynamicTest> {
        return images.map { spec ->
            DynamicTest.dynamicTest("fetch manifest for $spec:latest") {
                runBlocking {
                    val client = OciClient()
                    try {
                        val ref = OciClient.parseRef(spec)
                        val resp = client.fetchManifest(ref)
                        val body = runCatching { resp.bodyAsText() }.getOrNull()

                        if (resp.status != HttpStatusCode.OK) {
                            val headers = resp.headers.entries().joinToString("\n") { (k, v) -> "$k: ${v.joinToString()}" }
                            val msg = buildString {
                                appendLine("Unexpected status ${resp.status}")
                                appendLine("URL: https://${ref.registry}/v2/${ref.repository}/manifests/${ref.reference}")
                                appendLine("Headers:\n$headers")
                                appendLine("Body:\n$body")
                            }
                            throw AssertionError(msg)
                        }

                        // Basic sanity: parse JSON and check schemaVersion exists and is 2 (typical)
                        assertNotNull(body, "Empty body")
                        val json = Json.parseToJsonElement(body!!).jsonObject
                        val schemaVersion = json["schemaVersion"]?.jsonPrimitive?.content?.toIntOrNull()
                        assertNotNull(schemaVersion, "schemaVersion missing in manifest")
                        assertTrue(schemaVersion == 1 || schemaVersion == 2, "Unexpected schemaVersion=$schemaVersion")
                    } finally {
                        client.close()
                    }
                }
            }
        }
    }
}
