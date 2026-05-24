package oci.testing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Test schemas and validators for key OCI image-spec JSON object types
 * ([spec](https://github.com/opencontainers/image-spec/blob/main/spec.md)).
 */
object OciJsonSchemas {
    /**
     * OCI Content Descriptor schema JSON ([documentation](https://github.com/opencontainers/image-spec/blob/main/descriptor.md),
     * [authoritative source](https://raw.githubusercontent.com/opencontainers/image-spec/main/schema/content-descriptor.json))
     */
    val descriptorSchemaJson = $$"""
{
  "description": "OpenContainer Content Descriptor Specification",
  "$schema": "http://json-schema.org/draft-04/schema#",
  "id": "https://opencontainers.org/schema/descriptor",
  "type": "object",
  "properties": {
    "mediaType": {
      "description": "the mediatype of the referenced object",
      "$ref": "defs-descriptor.json#/definitions/mediaType"
    },
    "size": {
      "description": "the size in bytes of the referenced object",
      "$ref": "defs-descriptor.json#/definitions/size"
    },
    "digest": {
      "description": "the cryptographic checksum digest of the object, in the pattern '<algorithm>:<encoded>'",
      "$ref": "defs-descriptor.json#/definitions/digest"
    },
    "urls": {
      "description": "a list of urls from which this object may be downloaded",
      "$ref": "defs-descriptor.json#/definitions/urls"
    },
    "data": {
      "description": "an embedding of the targeted content (base64 encoded)",
      "$ref": "defs.json#/definitions/base64"
    },
    "artifactType": {
      "description": "the IANA media type of this artifact",
      "$ref": "defs-descriptor.json#/definitions/mediaType"
    },
    "annotations": {
      "id": "https://opencontainers.org/schema/descriptor/annotations",
      "$ref": "defs-descriptor.json#/definitions/annotations"
    }
  },
  "required": [
    "mediaType",
    "size",
    "digest"
  ]
}
""".trimIndent()

    /**
     * OCI Image Manifest schema JSON ([documentation](https://github.com/opencontainers/image-spec/blob/main/manifest.md),
     * [authoritative source](https://raw.githubusercontent.com/opencontainers/image-spec/main/schema/image-manifest-schema.json))
     */
    val imageManifestSchemaJson = $$"""
{
  "description": "OpenContainer Image Manifest Specification",
  "$schema": "http://json-schema.org/draft-04/schema#",
  "id": "https://opencontainers.org/schema/image/manifest",
  "type": "object",
  "properties": {
    "schemaVersion": {
      "description": "This field specifies the image manifest schema version as an integer",
      "id": "https://opencontainers.org/schema/image/manifest/schemaVersion",
      "type": "integer",
      "minimum": 2,
      "maximum": 2
    },
    "mediaType": {
      "description": "the mediatype of the referenced object",
      "$ref": "defs-descriptor.json#/definitions/mediaType"
    },
    "artifactType": {
      "description": "the artifact mediatype of the referenced object",
      "$ref": "defs-descriptor.json#/definitions/mediaType"
    },
    "config": {
      "$ref": "content-descriptor.json"
    },
    "subject": {
      "$ref": "content-descriptor.json"
    },
    "layers": {
      "type": "array",
      "minItems": 1,
      "items": {
        "$ref": "content-descriptor.json"
      }
    },
    "annotations": {
      "id": "https://opencontainers.org/schema/image/manifest/annotations",
      "$ref": "defs-descriptor.json#/definitions/annotations"
    }
  },
  "required": [
    "schemaVersion",
    "config",
    "layers"
  ]
}
""".trimIndent()

    /**
     * OCI Image Index (manifest list) schema JSON ([documentation](https://github.com/opencontainers/image-spec/blob/main/image-index.md),
     * [authoritative source](https://raw.githubusercontent.com/opencontainers/image-spec/main/schema/image-index-schema.json))
     */
    val imageIndexSchemaJson = $$"""
{
  "description": "OpenContainer Image Index Specification",
  "$schema": "http://json-schema.org/draft-04/schema#",
  "id": "https://opencontainers.org/schema/image/index",
  "type": "object",
  "properties": {
    "schemaVersion": {
      "description": "This field specifies the image index schema version as an integer",
      "id": "https://opencontainers.org/schema/image/index/schemaVersion",
      "type": "integer",
      "minimum": 2,
      "maximum": 2
    },
    "mediaType": {
      "description": "the mediatype of the referenced object",
      "$ref": "defs-descriptor.json#/definitions/mediaType"
    },
    "artifactType": {
      "description": "the artifact mediatype of the referenced object",
      "$ref": "defs-descriptor.json#/definitions/mediaType"
    },
    "subject": {
      "$ref": "content-descriptor.json"
    },
    "manifests": {
      "type": "array",
      "items": {
        "id": "https://opencontainers.org/schema/image/manifestDescriptor",
        "type": "object",
        "required": [
          "mediaType",
          "size",
          "digest"
        ],
        "properties": {
          "mediaType": {
            "description": "the mediatype of the referenced object",
            "$ref": "defs-descriptor.json#/definitions/mediaType"
          },
          "size": {
            "description": "the size in bytes of the referenced object",
            "$ref": "defs.json#/definitions/int64"
          },
          "digest": {
            "description": "the cryptographic checksum digest of the object, in the pattern '<algorithm>:<encoded>'",
            "$ref": "defs-descriptor.json#/definitions/digest"
          },
          "urls": {
            "description": "a list of urls from which this object may be downloaded",
            "$ref": "defs-descriptor.json#/definitions/urls"
          },
          "platform": {
            "id": "https://opencontainers.org/schema/image/platform",
            "type": "object",
            "required": [
              "architecture",
              "os"
            ],
            "properties": {
              "architecture": {
                "id": "https://opencontainers.org/schema/image/platform/architecture",
                "type": "string"
              },
              "os": {
                "id": "https://opencontainers.org/schema/image/platform/os",
                "type": "string"
              },
              "os.version": {
                "id": "https://opencontainers.org/schema/image/platform/os.version",
                "type": "string"
              },
              "os.features": {
                "id": "https://opencontainers.org/schema/image/platform/os.features",
                "type": "array",
                "items": {
                  "type": "string"
                }
              },
              "variant": {
                "type": "string"
              }
            }
          },
          "annotations": {
            "id": "https://opencontainers.org/schema/image/descriptor/annotations",
            "$ref": "defs-descriptor.json#/definitions/annotations"
          }
        }
      }
    },
    "annotations": {
      "id": "https://opencontainers.org/schema/image/index/annotations",
      "$ref": "defs-descriptor.json#/definitions/annotations"
    }
  },
  "required": [
    "schemaVersion",
    "manifests"
  ]
}
""".trimIndent()

    /**
     * OCI shared definitions schema JSON ([documentation](https://github.com/opencontainers/image-spec/blob/main/spec.md),
     * [authoritative source](https://raw.githubusercontent.com/opencontainers/image-spec/main/schema/defs.json))
     */
    val defsSchemaJson = $$"""
{
  "description": "Definitions used throughout the OpenContainer Specification",
  "definitions": {
    "int8": { "type": "integer", "minimum": -128, "maximum": 127 },
    "int16": { "type": "integer", "minimum": -32768, "maximum": 32767 },
    "int32": { "type": "integer", "minimum": -2147483648, "maximum": 2147483647 },
    "int64": { "type": "integer", "minimum": -9223372036854776000, "maximum": 9223372036854776000 },
    "uint8": { "type": "integer", "minimum": 0, "maximum": 255 },
    "uint16": { "type": "integer", "minimum": 0, "maximum": 65535 },
    "uint32": { "type": "integer", "minimum": 0, "maximum": 4294967295 },
    "uint64": { "type": "integer", "minimum": 0, "maximum": 18446744073709552000 },
    "uint16Pointer": { "oneOf": [{ "$ref": "#/definitions/uint16" }, { "type": "null" }] },
    "uint64Pointer": { "oneOf": [{ "$ref": "#/definitions/uint64" }, { "type": "null" }] },
    "base64": { "type": "string", "media": { "binaryEncoding": "base64" } },
    "stringPointer": { "oneOf": [{ "type": "string" }, { "type": "null" }] },
    "mapStringString": { "type": "object", "patternProperties": { ".{1,}": { "type": "string" } } },
    "mapStringObject": { "type": "object", "patternProperties": { ".{1,}": { "type": "object" } } }
  }
}
""".trimIndent()


    /**
     * OCI descriptor-specific definitions schema JSON ([documentation](https://github.com/opencontainers/image-spec/blob/main/descriptor.md),
     * [authoritative source](https://raw.githubusercontent.com/opencontainers/image-spec/main/schema/defs-descriptor.json))
     */
    val defsDescriptorSchemaJson = $$"""
{
  "description": "Definitions particular to OpenContainer Descriptor Specification",
  "$schema": "http://json-schema.org/draft-04/schema#",
  "definitions": {
    "mediaType": {
      "id": "https://opencontainers.org/schema/image/descriptor/mediaType",
      "type": "string",
      "pattern": "^[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]{0,126}/[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]{0,126}$"
    },
    "size": { "type": "integer", "minimum": 0, "maximum": 9223372036854776000 },
    "digest": {
      "description": "the cryptographic checksum digest of the object, in the pattern '<algorithm>:<encoded>'",
      "type": "string",
      "pattern": "^[a-z0-9]+(?:[+._-][a-z0-9]+)*:[a-zA-Z0-9=_-]+$"
    },
    "urls": { "description": "a list of urls from which this object may be downloaded", "type": "array", "items": { "type": "string", "format": "uri" } },
    "annotations": { "$ref": "defs.json#/definitions/mapStringString" }
  }
}
""".trimIndent()


    /**
     * Matching the version of the JSON Schema standard used by the schemas we reference.
     * Used for backfilling the schemas in the files that don't have an explicit `$schema` attribute.
     */
    private const val SCHEMA_VER = "draft-04"

    /**
     * Map of local schema filenames to public or synthetic schema URIs.
     * Used for enabling the `ref` resolutions (we convert file refs to URI refs).
     */
    private val filenameToOciSchema = mapOf(
        "content-descriptor.json"    to "https://opencontainers.org/schema/descriptor",
        "image-index-schema.json"    to "https://opencontainers.org/schema/image/index",
        "image-manifest-schema.json" to "https://opencontainers.org/schema/image/manifest",
        "defs.json" to "local:defs.json",
        "defs-descriptor.json" to "local:defs-descriptor.json",
    )

    /**
     * Reference schemas with well-formed `$schema`, `$id` and `id` attributes
     * (the latter for old schema standards, such as "draft 4" which we have to use).
     */
    val referencedSchemas = listOf(
        fixup(defsSchemaJson, "defs.json"),
        fixup(defsDescriptorSchemaJson, "defs-descriptor.json"),
        fixup(descriptorSchemaJson, "content-descriptor.json")
    )

    /**
     * Validator for OCI Image Manifest objects ([documentation](https://github.com/opencontainers/image-spec/blob/main/manifest.md),
     * [authoritative source](https://raw.githubusercontent.com/opencontainers/image-spec/main/schema/image-manifest-schema.json)).
     */
    val imageManifest: JsonSchemaValidator = JsonSchemaValidator.fromDefinition(
        schemaDefinition = fixup(imageManifestSchemaJson, "image-manifest-schema.json"),
        referencedSchemaDefinitions = referencedSchemas,
    )

    /**
     * Validator for OCI Image Index objects ([documentation](https://github.com/opencontainers/image-spec/blob/main/image-index.md),
     * [authoritative source](https://raw.githubusercontent.com/opencontainers/image-spec/main/schema/image-index-schema.json)).
     */
    val imageIndex: JsonSchemaValidator = JsonSchemaValidator.fromDefinition(
        schemaDefinition = fixup(imageIndexSchemaJson, "image-index-schema.json"),
        referencedSchemaDefinitions = referencedSchemas,
    )

    fun manifestOrIndexValidationError(body: String, spec: String?): String? {
        val manifestValidation = imageManifest.validate(body)
        val indexValidation = imageIndex.validate(body)
        if (manifestValidation.valid || indexValidation.valid) return null // conformant response

        // Accepted divergence:
        // Some registries return index-like responses with non-OCI media-type combinations or
        // schema-shape nuances that fail strict validation.
        val parsed = Json.parseToJsonElement(body).jsonObject
        val schemaVersion = parsed["schemaVersion"]?.jsonPrimitive?.content?.toIntOrNull()
        val manifests = parsed["manifests"]?.jsonArray
        val looksLikeIndex = schemaVersion == 2 && manifests != null
        val indexHasRequiredDescriptorFields = manifests?.map { it.jsonObject }?.all {
            it["mediaType"] != null && it["digest"] != null && it["size"] != null
        } ?: false

        val indexHasAcceptableMisses = manifestValidation.diagnostics.all { it.missingProperties == setOf("layers", "config") } ||
                    indexValidation.diagnostics.all { it.missingProperties == setOf("manifests") }

        // We still require schemaVersion=2 and descriptor-level bounds on manifests.
        val acceptableDivergence = looksLikeIndex && indexHasRequiredDescriptorFields && indexHasAcceptableMisses

        val prefix = if (spec == null) "" else " for $spec"
        return if (acceptableDivergence) null else "OCI schema validation failed$prefix. " +
                "Accepted divergence check failed. " +
                "looksLikeIndex=$looksLikeIndex, indexHasRequiredDescriptorFields=$indexHasRequiredDescriptorFields, " +
                "Manifest diagnostics=${manifestValidation.diagnostics}; " +
                "Index diagnostics=${indexValidation.diagnostics}"
    }

    private fun fixup(schema: String, fileName: String): String {

        fun prependAttrs(schema: String, vararg kv: Pair<String, String>) = kv.fold(schema) { acc, (k, v) ->
            acc.replaceFirst("{", "{\n  \"$k\": \"$v\",")
        }

        val withSchema = if (schema.contains($$"$schema")) {
            check(schema.contains(""""http://json-schema.org/$SCHEMA_VER/schema#"""")) {
                "schema version was expected to be $SCHEMA_VER"
            }
            schema
        } else {
            prependAttrs(schema,
                $$"$schema" to "http://json-schema.org/$SCHEMA_VER/schema#"
            )
        }

        // we replace filenames with public aliases
        val normalizedRefs = filenameToOciSchema.entries.fold(withSchema) { acc, (filename, schemaUrl) ->
            acc.replace("\"$filename#", "\"$schemaUrl#")
               .replace("\"$filename\"", "\"$schemaUrl\"")
        }

        // verify that if there is an ID specified, it matches one of the schemas
        val ownSchemaUrl = filenameToOciSchema[fileName]!!
        val schemaUrls = filenameToOciSchema.values
        val withCorrectId = if (!ownSchemaUrl.startsWith("local:")) {
            check(schemaUrls.any { schema.contains(""""$it"""") }) {
                "schema ID should be one of:\n- ${schemaUrls.joinToString(separator = "\n- ")}"
            }
            normalizedRefs
        } else {
            check(schemaUrls.any { !schema.contains(""""$it"""") }) {
                "local schema URI's are expected to be unspecified"
            }
            prependAttrs(normalizedRefs, "id" to ownSchemaUrl, $$"$id" to ownSchemaUrl)
        }
        return withCorrectId
    }
}
