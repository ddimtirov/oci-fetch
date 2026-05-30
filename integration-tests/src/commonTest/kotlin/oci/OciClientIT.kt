package oci

import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import oci.testing.OciJsonSchemas.manifestOrIndexValidationError
import kotlin.test.Test
import kotlin.jvm.JvmStatic
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val MIME_NOTARY_SIG = "application/vnd.cncf.notary.signature"
private const val MIME_SPDX_SBOM = "text/spdx+json"
private const val MIME_DSSE_ENVELOPE = "application/vnd.dsse.envelope.v1+json"
private const val MIME_COSIGN_SIMPLE = "application/vnd.dev.cosign.simplesigning.v1+json"
private val SELECT_AMD64 = PlatformSelector(architecture = "amd64")

class OciClientIT {

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
        //
        // NOTE ON PLATFORM LIFECYCLE:
        // While class-level hooks (@BeforeClass/@AfterClass) are only supported on the JVM,
        // this is compensated by our double-mechanism design:
        // 1. The lazy-initialization fallback in getClient() ensures functional correctness and initializes
        //    the client on non-JVM platforms where the class hooks are ignored.
        // 2. The JVM-specific class hooks ensure optimal socket/connection reuse on the JVM.
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

    @Test
    fun ubiTagListIsLarge() = runTest {
        val client = getClient()
        val tags = client.fetchAllTags(OciRef.parse("registry.access.redhat.com/ubi9/ubi"))
        assertTrue(tags.size > 100, "Expected Red Hat UBI tag list to contain more than 100 tags, but got ${tags.size}")
    }

    @Test
    fun fetchManifest_alpine() = runTest {
        val client = getClient()
        val ref = OciRef.parse("registry-1.docker.io/library/alpine:latest")
        val response = client.requestManifest(ref)
        val body = response.bodyAsText()

        skipIfDockerHubRateLimited(ref, response.status, body)

        assertTrue(response.status.isSuccess(), "Expected successful response")

        // Parse and validate manifest structure
        assertNotNull(body)
        manifestOrIndexValidationError(body, null)?.let<String, Nothing> { throw AssertionError(it) }
    }

    @Test
    fun fetch_manifests_for_multiple_images_without_mocking() = runTest {
        val client = getClient()
        val images = listOf(
            "registry.access.redhat.com/ubi8/ubi-minimal",
            "registry-1.docker.io/library/alpine",
            "registry-1.docker.io/library/nginx"
        )
        for (spec in images) {
            val ref = OciRef.parse(spec)
            val resp = client.requestManifest(ref)
            val body = runCatching { resp.bodyAsText() }.getOrNull()

            if (body != null) {
                skipIfDockerHubRateLimited(ref, resp.status, body)
            }

            if (!resp.status.isSuccess()) {
                val headers =
                    resp.headers.entries().joinToString("\n") { entry -> "${entry.key}: ${entry.value.joinToString()}" }
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
            manifestOrIndexValidationError(body, spec)?.let { throw AssertionError(it) }
        }
    }

    @Test
    fun referrersOciApi() = runTest {
        val client = getClient()
        val image = OciRef.parse("mcr.microsoft.com/dotnet/sdk:8.0-jammy")
        val resolved = if (image.isDigest) image else client.resolveToImageManifest(image, SELECT_AMD64)

        val signatures = client.fetchReferrers(resolved, MIME_NOTARY_SIG)
        manifestOrIndexValidationError(signatures.toString(), image.toString())?.let<String, Nothing> { throw AssertionError(it) }

        assertEquals("application/vnd.oci.image.index.v1+json", signatures["mediaType"]?.jsonPrimitive?.content, "The result is an OCI Index")

        val referrerTypes = signatures["manifests"]?.jsonArray?.mapNotNull { it.jsonObject["artifactType"]?.jsonPrimitive?.content }
        assertNotNull(referrerTypes, "Attached artifact manifests array should be non-empty: ${signatures["manifests"]?.jsonArray}")
        assertContains(referrerTypes, MIME_NOTARY_SIG, "The result should contain Notary signatures")
    }

    @Test
    fun cosignTagSchema(): TestResult {
        return runTest {
            val client = getClient()
            val image = OciRef.parse("registry.access.redhat.com/ubi9/ubi:latest")
            val resolved = client.resolveToImageManifest(image, SELECT_AMD64)

            val referrersIndex = client.fetchReferrers(resolved)
            manifestOrIndexValidationError(referrersIndex.toString(), image.toString())?.let<String, Nothing> { throw AssertionError(it) }
            val referrerManifests = referrersIndex["manifests"]?.jsonArray
            assertNotNull(referrerManifests, "Attached artifact manifests array should be non-empty: $referrersIndex")

            val referrerTypes = referrerManifests.mapNotNull { it.jsonObject["artifactType"]?.jsonPrimitive?.content }
            assertNotNull(referrerTypes, "Attached artifact manifests array should be non-empty: $referrerManifests")
            assertEquals(referrerManifests.size, referrerTypes.size+1, "The number of attached artifact types should match the number of manifests plus one for the index itself")

            assertContains(referrerTypes, MIME_COSIGN_SIMPLE, "The result should contain Cosign signatures")
            assertContains(referrerTypes, MIME_DSSE_ENVELOPE, "The result should contain DSSE envelopes")
            assertContains(referrerTypes, MIME_SPDX_SBOM, "The result should contain SPDX SBOMs")
            assertFalse(referrerTypes.contains(MIME_NOTARY_SIG), "The result should not contain Notary signatures")
        }
    }

    @Test
    fun cosignTagSchemaScraping(): TestResult {
        return runTest {
            val client = getClient()
            val image = OciRef.parse("registry.access.redhat.com/ubi9/ubi:latest")
            val resolved = client.resolveToImageManifest(image, SELECT_AMD64)

            val digestTag = Regex.escape(resolved.reference.replace(":", "-"))
            val regex = Regex("^$digestTag(?:\\.(?:sig|att|sbom))?$")

            val referrersIndex = client.scrapeReferrers(resolved, regex)
            manifestOrIndexValidationError(referrersIndex.toString(), image.toString())?.let<String, Nothing> { throw AssertionError(it) }

            val manifests = referrersIndex["manifests"]?.jsonArray
            assertNotNull(manifests, "Attached artifact manifests array should be non-empty: $referrersIndex")

            val referrerTypes = manifests.mapNotNull { it.jsonObject["artifactType"]?.jsonPrimitive?.content }
            assertNotNull(referrerTypes, "Attached artifact manifests array should be non-empty: $manifests")
            assertEquals(manifests.size, referrerTypes.size + 1, "The number of attached artifact types should match the number of manifests plus one for the index itself")

            assertContains(referrerTypes, MIME_COSIGN_SIMPLE, "The result should contain Cosign signatures")
            assertContains(referrerTypes, MIME_DSSE_ENVELOPE, "The result should contain DSSE envelopes")
            assertContains(referrerTypes, MIME_SPDX_SBOM, "The result should contain SPDX SBOMs")
            assertFalse(referrerTypes.contains(MIME_NOTARY_SIG), "The result should not contain Notary signatures")
        }
    }
}
