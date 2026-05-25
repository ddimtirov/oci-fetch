package oci

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import oci.testing.OciJsonSchemas.manifestOrIndexValidationError
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.MountableFile.forClasspathResource
import java.nio.file.Files
import java.nio.file.Files.createTempDirectory
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertificateFactory
import kotlin.io.path.absolutePathString
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val ZOT_IMAGE = "ghcr.io/project-zot/zot:latest"
private const val REPO_APP = "system/app" // tags: latest, v1, busybox
private const val REPO_MULTI = "system/multi" // tags: index
private const val REPO_ATTESTED = "system/attested" // tags: sha256-HASH-sig, sha256-HASH-att

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OciSystemTest {
    private data class Fixture(
        val appRefLatest: OciRef,
        val appRefV1: OciRef,
        val multiIndexRef: OciRef,
        val attestedTagRef: OciRef,
        val attestedDigestRef: OciRef,
        val digestTagRegex: Regex,
    )

    private lateinit var zot: GenericContainer<*>
    private lateinit var client: OciClient
    private lateinit var registry: String
    private lateinit var runtime: ContainerRuntimeStrategy
    private lateinit var trustStorePath: Path
    private lateinit var fixture: Fixture
    private val trustStorePassword = "changeit".toCharArray()

    @BeforeAll
    fun setUp() {
        runtime = selectContainerRuntimeFromEnv()
        runtime.checkGripes().let {
            assumeTrue(it.isEmpty()) { it.toString() }
        }

        configureTempJvmTrustStore()
        zot = GenericContainer(ZOT_IMAGE)
            .withExposedPorts(5000)
            .withCopyFileToContainer(forClasspathResource("zot-config.json"), "/etc/zot/config.json")
            .withCopyFileToContainer(forClasspathResource("certs/server.crt"), "/etc/zot/certs/server.crt")
            .withCopyFileToContainer(forClasspathResource("certs/server.key"), "/etc/zot/certs/server.key")
            .withCommand("serve", "/etc/zot/config.json")
            .also { it.start() }
        client = OciClient()
        registry = "${zot.host}:${zot.getMappedPort(5000)}"
        waitForZotToStart(30.seconds, 500.milliseconds)

        val attestedTagRef = OciRef.parse("$registry/$REPO_ATTESTED:latest")
        val multiIndexTag = OciRef.parse("$registry/$REPO_MULTI:index")
        val appRefLatest = OciRef.parse("$registry/$REPO_APP")
        val appRefV1 = appRefLatest.withTag("v1")
        val busyboxTag = appRefLatest.withTag("busybox")

        val contextDir = createTempDirectory("oci-system-test-build").toAbsolutePath().also {
            it.resolve("Dockerfile").writeText(
                """
                    FROM alpine:3.20
                    COPY hello.txt /hello.txt
                """.trimIndent()
            )
            it.resolve("hello.txt").writeText("hello zot")
        }

        fixture = runtime.run {
            // get some external images
            val (alpine320, busybox136) = cmdPull(
                "registry-1.docker.io/library/alpine:3.20",
                "registry-1.docker.io/library/busybox:1.36"
            )

            // publish single image
            cmdPushAs(alpine320, appRefV1)

            // publish index
            cmdCreateAndPushIndex(
                multiIndexTag,
                cmdPushAs(alpine320, appRefLatest),
                cmdPushAs(busybox136, busyboxTag)
            )

            // publish SBOM attested
            val attestedDigestRef = cmdBuildAndPushAttested(attestedTagRef, contextDir).also {
                assert(it.isDigest)
            }

            // publish Cosine attested
            val digestedTagPrefix = attestedDigestRef.reference.replace(":", "-")
            runtime.cmdPushAs(appRefLatest, attestedTagRef.withTag("$digestedTagPrefix.sig"))
            runtime.cmdPushAs(appRefLatest, attestedTagRef.withTag("$digestedTagPrefix.att"))

            Fixture(
                appRefLatest = appRefLatest,
                appRefV1 = appRefV1,
                multiIndexRef = multiIndexTag,
                attestedTagRef = attestedTagRef,
                attestedDigestRef = attestedDigestRef,
                digestTagRegex = Regex("^$digestedTagPrefix(?:\\.(?:sig|att|sbom))?$")
            )
        }

        client = OciClient()
    }

    private fun waitForZotToStart(timeout: Duration, sleep: Duration) = runBlocking {
        var lastError = "not ran"
        try {
            withTimeout(timeout) {
                while (true) {
                    lastError = try {
                        val response = client.requestRepositoriesDocker(registry)
                        if (response.status.isSuccess()) return@withTimeout
                        "HTTP ${response.status}: ${response.bodyAsText()}"
                    } catch (e: Exception) {
                        "${e::class.simpleName}: ${e.message}"
                    }
                    delay(sleep)
                }
            }
        } catch (e: TimeoutCancellationException) {
            fail(lastError, e)
        }
    }

    @AfterAll
    fun tearDown() {
        runCatching { client.close() }
        runCatching { zot.stop() }
        runCatching { Files.deleteIfExists(trustStorePath) }
        System.clearProperty("javax.net.ssl.trustStore")
        System.clearProperty("javax.net.ssl.trustStorePassword")
    }

    @Test
    fun requestManifest_requestBlob_andFetchAllMetadata() = runTest {
        val manifestResponse = client.requestManifest(fixture.appRefLatest)
        assertEquals(HttpStatusCode.OK, manifestResponse.status)

        val manifestBody = manifestResponse.bodyAsText()
        manifestOrIndexValidationError(manifestBody, fixture.appRefLatest.toString())?.let { throw AssertionError(it) }

        val manifestJson = Json.parseToJsonElement(manifestBody).jsonObject
        val configDigest = manifestJson["config"]?.jsonObject?.get("digest")?.jsonPrimitive?.content
        assertNotNull(configDigest, "Expected image manifest config digest")

        val blobResponse = client.requestBlob(fixture.appRefLatest.withDigest(configDigest))
        assertTrue(blobResponse.status.isSuccess(), "Config blob must be readable")

        val metadata = client.fetchAllMetadata(fixture.multiIndexRef)
        assertEquals(fixture.multiIndexRef, metadata.ref)
        assertNotNull(metadata.index, "Expected index metadata for multi-arch reference")
        assertTrue(metadata.images.isNotEmpty(), "Expected resolved images from index")
        assertTrue(metadata.images.all { it.config != null }, "Each resolved image should have config")
    }

    @Test
    fun requestTags_andFetchAllTags() = runTest {
        val tagsResponse = client.requestTags(fixture.appRefLatest)
        assertEquals(HttpStatusCode.OK, tagsResponse.status)

        val tags = client.fetchAllTags(fixture.appRefLatest)
        assertContains(tags, "latest")
        assertContains(tags, "v1")
    }

    @Test
    fun requestCatalog_andFetchAllRepositoriesDocker() = runTest {
        val catalogResponse = client.requestRepositoriesDocker(registry)
        assertEquals(HttpStatusCode.OK, catalogResponse.status)

        val repositories = client.fetchAllRepositoriesDocker(registry)
        assertContains(repositories, REPO_APP)
        assertContains(repositories, REPO_MULTI)
        assertContains(repositories, REPO_ATTESTED)
    }

    @Test
    fun resolveToImageManifest_andTypeDetectors() = runTest {
        val resolved = client.resolveToImageManifest(fixture.attestedTagRef, PlatformSelector(architecture = "amd64"))
        val manifestResp = client.requestManifest(resolved)
        val manifestBody = manifestResp.bodyAsText()
        val manifestJson = Json.parseToJsonElement(manifestBody).jsonObject
        assertTrue(manifestResp.status.isSuccess(), "Resolved manifest should be readable")
        assertTrue(client.isOciImageManifest(manifestJson))

        val indexResp = client.requestManifest(fixture.multiIndexRef)
        val indexBody = indexResp.bodyAsText()
        val indexJson = Json.parseToJsonElement(indexBody).jsonObject
        val contentType = indexResp.headers[HttpHeaders.ContentType] ?: ""
        assertTrue(client.isOciImageIndex(indexJson, contentType))
    }

    @Test
    fun resolveToImageManifest_indexWithoutConstraints_throwsAmbiguousPlatformSelection() = runTest {
        assertFailsWith<AmbiguousPlatformSelectionException> {
            client.resolveToImageManifest(fixture.multiIndexRef, PlatformSelector())
        }
    }

    @Test
    fun resolveToImageManifest_withMissingPlatform_throwsNoSuchPlatformSelection() = runTest {
        assertFailsWith<NoSuchPlatformSelectionException> {
            client.resolveToImageManifest(fixture.multiIndexRef, PlatformSelector(os = "plan9", architecture = "amd64"))
        }
    }

    @Test
    fun resolveToImageManifest_withAmbiguousSelector_throwsAmbiguousPlatformSelection() = runTest {
        assertFailsWith<AmbiguousPlatformSelectionException> {
            client.resolveToImageManifest(fixture.multiIndexRef, PlatformSelector(architecture = "amd64"))
        }
    }

    @Test
    fun resolveToImageManifest_withDigestReference_returnsSameDigest() = runTest {
        val digest = assertNotNull(
            client.requestManifest(fixture.attestedTagRef).headers["Docker-Content-Digest"],
            "Missing Docker-Content-Digest for ${fixture.attestedTagRef}"
        )
        val digestRef = fixture.attestedTagRef.withDigest(digest)
        val resolved = client.resolveToImageManifest(digestRef, PlatformSelector(architecture = "amd64"))
        assertEquals(digestRef, resolved)
    }

    @Test
    fun fetchReferrers_andScrapeReferrers() = runTest {
        val referrers = client.fetchReferrers(fixture.attestedDigestRef)
        manifestOrIndexValidationError(referrers.toString(), fixture.attestedDigestRef.toString())?.let { throw AssertionError(it) }

        val referrerManifests = referrers["manifests"]?.jsonArray
        assertNotNull(referrerManifests, "Expected manifests array in referrers index")
        assertTrue(referrerManifests.isNotEmpty(), "Expected at least one attestation/referrer")

        val scraped = client.scrapeReferrers(fixture.attestedDigestRef, fixture.digestTagRegex)
        manifestOrIndexValidationError(scraped.toString(), fixture.attestedDigestRef.toString())?.let { throw AssertionError(it) }

        val scrapedManifests = scraped["manifests"]?.jsonArray
        assertNotNull(scrapedManifests, "Expected manifests array in scraped referrers index")
        assertTrue(scrapedManifests.isNotEmpty(), "Expected scrape-based referrer discovery to find matches")
    }

    private fun configureTempJvmTrustStore() {
        val certFactory = CertificateFactory.getInstance("X.509")
        val certStream = javaClass.classLoader.getResourceAsStream("certs/server.crt")
            ?: error("Missing cert resource certs/server.crt")
        certStream.use {
            val cert = certFactory.generateCertificate(it)
            val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("zot", cert)
            }
            trustStorePath = Files.createTempFile("zot-truststore", ".jks")
            trustStorePath.toFile().outputStream().use { output ->
                trustStore.store(output, trustStorePassword)
            }
            System.setProperty("javax.net.ssl.trustStore", trustStorePath.absolutePathString())
            System.setProperty("javax.net.ssl.trustStorePassword", String(trustStorePassword))
        }
    }

    private fun selectContainerRuntimeFromEnv(): ContainerRuntimeStrategy {
        PodmanRuntimeStrategy.fromEnvironment()?.let { return it }
        DockerRuntimeStrategy.fromEnvironment()?.let { return it }
        assumeTrue(false, "Skipping system tests: neither Docker nor Podman CLI is available")
        error("unreachable")
    }

}
