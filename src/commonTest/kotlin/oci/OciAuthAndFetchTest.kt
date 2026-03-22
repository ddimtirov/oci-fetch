package oci

import io.ktor.http.HttpStatusCode
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OciAuthAndFetchTest {

    private suspend fun doTest(spec: String) {
        if (isBrowser() || isNative()) return
        val client = OciClient()
        try {
            val ref = OciClient.parseRef(spec)
            val resp = client.fetchManifest(ref)
            val body = resp.bodyAsText()

            if (resp.status != HttpStatusCode.OK) {
                val headers = resp.headers.entries().joinToString("\n") { (k, v) -> "$k: ${v.joinToString()}" }
                val msg = buildString {
                    appendLine("Unexpected status ${resp.status} for $spec")
                    appendLine("URL: https://${ref.registry}/v2/${ref.repository}/manifests/${ref.reference}")
                    appendLine("Headers:\n$headers")
                    appendLine("Body:\n$body")
                }
                throw AssertionError(msg)
            }

            // Basic sanity: parse JSON and check schemaVersion exists and is 2 (typical)
            assertNotNull(body, "Empty body for $spec")
            val json = Json.parseToJsonElement(body).jsonObject
            val schemaVersion = json["schemaVersion"]?.jsonPrimitive?.content?.toIntOrNull()
            assertNotNull(schemaVersion, "schemaVersion missing in manifest for $spec")
            assertTrue(schemaVersion == 1 || schemaVersion == 2, "Unexpected schemaVersion=$schemaVersion for $spec")
        } finally {
            client.close()
        }
    }

    private fun imageTest(spec: String) = runTest { doTest(spec) }

    @Test fun ubi7() = imageTest("registry.access.redhat.com/ubi7/ubi")
    @Test fun ubi7minimal() = imageTest("registry.access.redhat.com/ubi7/ubi-minimal")
    @Test fun ubi7init() = imageTest("registry.access.redhat.com/ubi7/ubi-init")
    @Test fun ubi8() = imageTest("registry.access.redhat.com/ubi8/ubi")
    @Test fun ubi8minimal() = imageTest("registry.access.redhat.com/ubi8/ubi-minimal")
    @Test fun ubi8micro() = imageTest("registry.access.redhat.com/ubi8/ubi-micro")
    @Test fun ubi8init() = imageTest("registry.access.redhat.com/ubi8/ubi-init")
    @Test fun ubi9() = imageTest("registry.access.redhat.com/ubi9/ubi")
    @Test fun ubi9minimal() = imageTest("registry.access.redhat.com/ubi9/ubi-minimal")
    @Test fun ubi9micro() = imageTest("registry.access.redhat.com/ubi9/ubi-micro")
    @Test fun ubi9init() = imageTest("registry.access.redhat.com/ubi9/ubi-init")

    @Test fun memcached() = imageTest("registry-1.docker.io/library/memcached")
    @Test fun nginx() = imageTest("registry-1.docker.io/library/nginx")
    @Test fun busybox() = imageTest("registry-1.docker.io/library/busybox")
    @Test fun alpine() = imageTest("registry-1.docker.io/library/alpine")
    @Test fun ubuntu() = imageTest("registry-1.docker.io/library/ubuntu")
    @Test fun redis() = imageTest("registry-1.docker.io/library/redis")
    @Test fun postgres() = imageTest("registry-1.docker.io/library/postgres")
    @Test fun python() = imageTest("registry-1.docker.io/library/python")
    @Test fun node() = imageTest("registry-1.docker.io/library/node")
    @Test fun httpd() = imageTest("registry-1.docker.io/library/httpd")
}
