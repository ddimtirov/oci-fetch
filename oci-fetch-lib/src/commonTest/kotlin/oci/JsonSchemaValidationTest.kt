package oci

import oci.testing.JsonSchemaValidator
import oci.testing.OciJsonSchemas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JsonSchemaValidationTest {
    @Test
    fun manifestSchema_acceptsValidManifest() {
        val validManifest = """
            {
              "schemaVersion": 2,
              "mediaType": "application/vnd.oci.image.manifest.v1+json",
              "config": {
                "mediaType": "application/vnd.oci.image.config.v1+json",
                "digest": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "size": 1234
              },
              "layers": [
                {
                  "mediaType": "application/vnd.oci.image.layer.v1.tar+gzip",
                  "digest": "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                  "size": 5678
                }
              ]
            }
        """.trimIndent()

        val result = OciJsonSchemas.imageManifest.validate(validManifest)

        assertTrue(result.valid, "Expected manifest to satisfy OCI schema: ${result.diagnostics}")
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun utility_returnsDetailedFailure_forInvalidJson() {
        val result = OciJsonSchemas.imageManifest.validate("{not-json")

        assertFalse(result.valid)
        assertEquals("JSON_PARSE_ERROR", result.diagnostics.firstOrNull()?.code)
        assertTrue(result.diagnostics.firstOrNull()?.details?.isNotBlank() == true)
    }

    @Test
    fun utility_returnsDetailedFailure_forSchemaMismatch() {
        val invalidManifest = """
            {
              "schemaVersion": 2,
              "config": {
                "mediaType": "application/vnd.oci.image.config.v1+json",
                "digest": "sha256:abc",
                "size": 100
              },
              "layers": []
            }
        """.trimIndent()

        val result = OciJsonSchemas.imageManifest.validate(invalidManifest)

        assertFalse(result.valid)
        assertEquals("JSON_SCHEMA_VALIDATION_ERROR", result.diagnostics.firstOrNull()?.code)
        assertTrue(result.diagnostics.isNotEmpty(), "Expected schema diagnostics for invalid manifest")
    }

    @Test
    fun customSchema_canBeCompiledAndValidated() {
        val schema = JsonSchemaValidator.fromDefinition(
            """
            {
              "${'$'}schema": "http://json-schema.org/draft-07/schema#",
              "type": "object",
              "required": ["name"],
              "properties": {
                "name": { "type": "string", "minLength": 1 }
              }
            }
            """.trimIndent()
        )

        val result = schema.validate("""{"name":"oci"}""")
        assertTrue(result.valid)
    }
}
