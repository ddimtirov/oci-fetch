package oci

import io.ktor.http.HttpStatusCode
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.test.runTest
import oci.testing.OciJsonSchemas.manifestOrIndexValidationError
import kotlin.test.Test
import kotlin.jvm.JvmStatic
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class OciAuthAndFetchIT {

    companion object {
        private var sharedClient: OciClient? = null

        private fun getClient(): OciClient {
            var client = sharedClient
            if (client == null) {
                client = OciClient()
                sharedClient = client
            }
            return client
        }

        // CONSCIOUS DESIGN CHOICE:
        // We use Class-level (@BeforeClass / @AfterClass) test fixture setup rather than Suite-level
        // to balance proper test isolation between different test classes (avoiding state leakage and side effects)
        // while still gaining connection pooling and socket reuse efficiency across tests in the same class.
        @BeforeClass
        @JvmStatic
        fun setUpClass() {
            sharedClient = OciClient()
        }

        @AfterClass
        @JvmStatic
        fun tearDownClass() {
            sharedClient?.close()
            sharedClient = null
        }
    }

    private fun imageTest(spec: String) = runTest {
        val client = getClient()
        val ref = OciRef.parse(spec)
        val resp = client.requestManifest(ref)
        val body = resp.bodyAsText()

        val requestDescription = """
            URL: https://${ref.registry}/v2/${ref.repository}/manifests/${ref.reference}
            Headers:
            ${resp.headers.entries().joinToString("\n") { entry -> "\t${entry.key}: ${entry.value.joinToString()}" }}
            Body:
            $body               
        """.trimIndent()

        if (isDockerHubRateLimited(ref.registry, resp.status, body)) {
            return@runTest
        }

        assertEquals(HttpStatusCode.OK, resp.status, "Unexpected HTTP status for $spec\n$requestDescription")
        assertNotNull(body, "Empty body for $spec\n$requestDescription")

        manifestOrIndexValidationError(body, spec)?.let { validationError ->
            throw AssertionError("$validationError\n$requestDescription")
        }
    }

    private fun isDockerHubRateLimited(registry: String, status: HttpStatusCode, body: String): Boolean {
        return registry == "registry-1.docker.io" &&
            status == HttpStatusCode.TooManyRequests &&
            body.contains("\"TOOMANYREQUESTS\"")
    }

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
