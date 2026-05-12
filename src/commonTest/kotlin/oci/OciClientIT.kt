package oci

import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

expect fun isBrowser(): Boolean
expect fun isNative(): Boolean

private const val MIME_NOTARY_SIG = "application/vnd.cncf.notary.signature"
private const val MIME_SPDX_SBOM = "text/spdx+json"
private const val MIME_DSSE_ENVELOPE = "application/vnd.dsse.envelope.v1+json"
private const val MIME_COSIGN_SIMPLE = "application/vnd.dev.cosign.simplesigning.v1+json"
private val SELECT_AMD64 = PlatformSelector(architecture = "amd64")

class OciClientIT {
    @Test
    fun testUbiTagListIsLarge() = runTest {
        if (isBrowser() || isNative()) return@runTest
        OciClient().use { client ->
            val tags = client.fetchTagsList(OciRef.parse("registry.access.redhat.com/ubi9/ubi"))

            assertTrue(tags.size > 100, "Expected Red Hat UBI tag list to contain more than 100 tags, but got ${tags.size}")
        }
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



    @Test
    fun testReferrersOciApi() = runTest {
        if (isBrowser() || isNative()) return@runTest
        OciClient().use { client ->
            // ghcr.io supports the OCI Referrers API.
            // stefanprodan/podinfo is a popular image often used for testing such features.
            val image = OciRef.parse("mcr.microsoft.com/dotnet/sdk:8.0-jammy")
            val resolved = if (image.isDigest) image else client.resolveToImageManifest(image, SELECT_AMD64)

            val signatures = client.fetchReferrers(resolved, MIME_NOTARY_SIG)

            assertEquals("application/vnd.oci.image.index.v1+json", signatures["mediaType"]?.jsonPrimitive?.content, "The result is an OCI Index")

            val referrerTypes = signatures["manifests"]?.jsonArray?.mapNotNull { it.jsonObject["artifactType"]?.jsonPrimitive?.content }
            assertNotNull(referrerTypes, "Attached artifact manifests array should be non-empty: ${signatures["manifests"]?.jsonArray}")
            assertContains(referrerTypes, MIME_NOTARY_SIG, "The result should contain Notary signatures")
        }
    }

    @Test
    fun cosignTagSchema(): TestResult {
        return runTest {
            if (isBrowser() || isNative()) return@runTest
            OciClient().use { client ->
                // UBI images are known to be signed using Cosign and have attestations, but the RH registry does not support OCI referrers and uses tag-schema instead.
                val image = OciRef.parse("registry.access.redhat.com/ubi9/ubi:latest")
                val resolved = client.resolveToImageManifest(image, SELECT_AMD64)

                val referrersIndex = client.fetchReferrers(resolved)
                val referrerManifests = referrersIndex["manifests"]?.jsonArray
                assertNotNull(referrerManifests, "Attached artifact manifests array should be non-empty: $referrersIndex")

                val referrerTypes = referrerManifests.mapNotNull { it.jsonObject["artifactType"]?.jsonPrimitive?.content }
                assertNotNull(referrerTypes, "Attached artifact manifests array should be non-empty: $referrerManifests")
                assertEquals(referrerManifests.size, referrerTypes.size+1, "The number of attached artifact types should match the number of manifests plus one for the index itself")

                // Red Hat UBI images usually have at least a signature (.sig) or attestation (.att)
                // discovered via the Cosign tag convention (digest-replaced-colon-with-dash.sig)
                assertContains(referrerTypes, MIME_COSIGN_SIMPLE, "The result should contain Cosign signatures")
                assertContains(referrerTypes, MIME_DSSE_ENVELOPE, "The result should contain DSSE envelopes")
                assertContains(referrerTypes, MIME_SPDX_SBOM, "The result should contain SPDX SBOMs")
                assertFalse(referrerTypes.contains(MIME_NOTARY_SIG), "The result should not contain Notary signatures")
            }
        }
    }

    @Test
    fun cosignTagSchemaScraping(): TestResult {
        return runTest {
            if (isBrowser() || isNative()) return@runTest
            OciClient().use { client ->
                // UBI images are known to be signed using Cosign and have attestations.
                val image = OciRef.parse("registry.access.redhat.com/ubi9/ubi:latest")
                val resolved = client.resolveToImageManifest(image, SELECT_AMD64)

                val digestTag = Regex.escape(resolved.reference.replace(":", "-"))
                val regex = Regex("^$digestTag(?:\\.(?:sig|att|sbom))?$")

                val referrersIndex = client.scrapeReferrers(resolved, regex)

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
}
