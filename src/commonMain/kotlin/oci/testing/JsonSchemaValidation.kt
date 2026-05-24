package oci.testing

import io.github.optimumcode.json.schema.JsonSchema
import io.github.optimumcode.json.schema.JsonSchemaLoader
import io.github.optimumcode.json.schema.ValidationError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class JsonSchemaDiagnostic(
    val code: String,
    val message: String,
    val details: String? = null,
) {
    val missingProperties: Set<String>? get() = when {
        details == null -> null
        !details.startsWith("missing required properties: [layers, config]") -> null
        !details.endsWith("]") -> null
        else -> details
            .removePrefix("missing required properties: [").removeSuffix("]")
            .split(",")
            .map { it.trim() }
            .sorted()
            .toSet()
    }
}

data class JsonSchemaValidationResult(
    val valid: Boolean,
    val parsedJson: JsonElement? = null,
    val diagnostics: List<JsonSchemaDiagnostic> = emptyList(),
)

class JsonSchemaValidator private constructor(
    private val schema: JsonSchema,
) {
    fun validate(jsonString: String): JsonSchemaValidationResult {
        val parsed = runCatching { Json.parseToJsonElement(jsonString) }.getOrElse { parseError ->
            return JsonSchemaValidationResult(
                valid = false,
                diagnostics = listOf(
                    JsonSchemaDiagnostic(
                        code = "JSON_PARSE_ERROR",
                        message = "Input is not valid JSON",
                        details = parseError.message,
                    )
                )
            )
        }

        return validate(parsed)
    }

    fun validate(parsed: JsonElement): JsonSchemaValidationResult {
        val errors = mutableListOf<ValidationError>()
        val valid = schema.validate(parsed, errors::add)
        return if (valid) {
            JsonSchemaValidationResult(valid = true, parsedJson = parsed)
        } else {
            JsonSchemaValidationResult(
                valid = false,
                parsedJson = parsed,
                diagnostics = errors.map {
                    JsonSchemaDiagnostic(
                        code = "JSON_SCHEMA_VALIDATION_ERROR",
                        message = it.toString(),
                    )
                }
            )
        }
    }

    companion object {
        fun fromDefinition(schemaDefinition: String): JsonSchemaValidator {
            return JsonSchemaValidator(JsonSchema.fromDefinition(schemaDefinition))
        }

        fun fromDefinition(
            schemaDefinition: String,
            referencedSchemaDefinitions: List<String>,
        ): JsonSchemaValidator {
            val loader = JsonSchemaLoader.create()
            referencedSchemaDefinitions.forEach { loader.register(it) }
            return JsonSchemaValidator(loader.fromDefinition(schemaDefinition))
        }
    }
}

fun normalizeJson(jsonString: String): String {
    val element = Json.parseToJsonElement(jsonString)
    return normalizeJsonElement(element)
}

private fun normalizeJsonElement(element: JsonElement): String = when (element) {
    is JsonObject -> element.entries
        .sortedBy { it.key }
        .joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "\"$key\":${normalizeJsonElement(value)}"
        }
    is JsonArray -> element.joinToString(prefix = "[", postfix = "]") { normalizeJsonElement(it) }
    is JsonPrimitive -> element.toString()
}
