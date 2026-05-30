package oci

import kotlin.test.Test
import kotlin.test.assertEquals

class FormattersTest {
    @Test
    fun testFormatTsvReferrers() {
        val body = """
            {
              "schemaVersion": 2,
              "mediaType": "application/vnd.oci.image.index.v1+json",
              "manifests": [
                {
                  "digest": "sha256:123",
                  "mediaType": "application/vnd.oci.image.manifest.v1+json",
                  "artifactType": "application/vnd.dev.cosign.artifact.sig.v1+json",
                  "size": 456,
                  "annotations": {
                    "dev.cosign.artifact.hash": "sha256:abc"
                  }
                }
              ]
            }
        """.trimIndent()

        val expected = "digest\tartifactType\tmediaType\tsize\tannotations\n" +
                "sha256:123\tapplication/vnd.dev.cosign.artifact.sig.v1+json\tapplication/vnd.oci.image.manifest.v1+json\t456\tdev.cosign.artifact.hash=sha256:abc"

        val actual = formatTsvReferrers(body)
        assertEquals(expected, actual)
    }

    @Test
    fun testFormatTsvReferrers_emptyManifests() {
        val body = """
            {
              "schemaVersion": 2,
              "mediaType": "application/vnd.oci.image.index.v1+json",
              "manifests": []
            }
        """.trimIndent()

        val actual = formatTsvReferrers(body)
        assertEquals("digest\tartifactType\tmediaType\tsize\tannotations", actual)
    }

    @Test
    fun testFormatTsvReferrers_missingFields() {
        val body = """
            {
              "schemaVersion": 2,
              "mediaType": "application/vnd.oci.image.index.v1+json",
              "manifests": [
                { "digest": "sha256:abc" }
              ]
            }
        """.trimIndent()

        // formatTsvReferrers calls trimEnd() on the buffer, so trailing empty cells (tabs) are stripped.
        val expected = "digest\tartifactType\tmediaType\tsize\tannotations\nsha256:abc"

        val actual = formatTsvReferrers(body)
        assertEquals(expected, actual)
    }

    @Test
    fun testFormatTsvReferrers_multipleAnnotations() {
        val body = """
            {
              "schemaVersion": 2,
              "mediaType": "application/vnd.oci.image.index.v1+json",
              "manifests": [
                {
                  "digest": "sha256:1",
                  "mediaType": "application/vnd.oci.image.manifest.v1+json",
                  "size": 10,
                  "annotations": {
                    "k1": "v1",
                    "k2": "v2"
                  }
                }
              ]
            }
        """.trimIndent()

        val actual = formatTsvReferrers(body)
        // Annotations join with "; " and field order follows JSON insertion order.
        val expected = "digest\tartifactType\tmediaType\tsize\tannotations\n" +
                "sha256:1\t\tapplication/vnd.oci.image.manifest.v1+json\t10\tk1=v1; k2=v2"
        assertEquals(expected, actual)
    }

    @Test
    fun testFormatPrettyJson_object() {
        val json = """{"a":1,"b":"hello"}"""
        val pretty = formatPrettyJson(json)
        assertEquals("{\n    \"a\": 1,\n    \"b\": \"hello\"\n}", pretty)
    }

    @Test
    fun testFormatPrettyJson_array() {
        val json = """[1,2,3]"""
        val pretty = formatPrettyJson(json)
        assertEquals("[\n    1,\n    2,\n    3\n]", pretty)
    }
}
