package oci

import oci.testing.OciJsonSchemas
import oci.testing.normalizeJson
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals

class OciSchemaParityIT {
    @Test
    fun descriptorSchema_matchesOfficial() = assertSchemaMatchesOfficial(
        officialUrl = "https://raw.githubusercontent.com/opencontainers/image-spec/main/schema/content-descriptor.json",
        localSchema = OciJsonSchemas.descriptorSchemaJson,
    )

    @Test
    fun imageManifestSchema_matchesOfficial() = assertSchemaMatchesOfficial(
        officialUrl = "https://raw.githubusercontent.com/opencontainers/image-spec/main/schema/image-manifest-schema.json",
        localSchema = OciJsonSchemas.imageManifestSchemaJson,
    )

    @Test
    fun imageIndexSchema_matchesOfficial() = assertSchemaMatchesOfficial(
        officialUrl = "https://raw.githubusercontent.com/opencontainers/image-spec/main/schema/image-index-schema.json",
        localSchema = OciJsonSchemas.imageIndexSchemaJson,
    )

    @Test
    fun defsSchema_matchesOfficial() = assertSchemaMatchesOfficial(
        officialUrl = "https://raw.githubusercontent.com/opencontainers/image-spec/main/schema/defs.json",
        localSchema = OciJsonSchemas.defsSchemaJson,
    )

    @Test
    fun defsDescriptorSchema_matchesOfficial() = assertSchemaMatchesOfficial(
        officialUrl = "https://raw.githubusercontent.com/opencontainers/image-spec/main/schema/defs-descriptor.json",
        localSchema = OciJsonSchemas.defsDescriptorSchemaJson,
    )

    private fun assertSchemaMatchesOfficial(officialUrl: String, localSchema: String) {
        val officialSchema = URI(officialUrl).toURL().readText()
        assertEquals(
            expected = normalizeJson(officialSchema),
            actual = normalizeJson(localSchema),
            message = "Local schema drift detected for $officialUrl",
        )
    }
}
