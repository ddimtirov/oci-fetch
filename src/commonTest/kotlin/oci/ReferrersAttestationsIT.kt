package oci

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

private const val MIME_NOTARY_SIG = "application/vnd.cncf.notary.signature"
private const val MIME_SPDX_SBOM = "text/spdx+json"
private const val MIME_DSSE_ENVELOPE = "application/vnd.dsse.envelope.v1+json"
private const val MIME_COSIGN_SIMPLE = "application/vnd.dev.cosign.simplesigning.v1+json"
private val SELECT_AMD64 = PlatformSelector(architecture = "amd64")

class ReferrersAttestationsIT {

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
    fun testCosignTagSchema(): TestResult {
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
}
