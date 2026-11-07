package ru.tinsurance.server.openapi

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.json.*

internal fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))
internal fun str(value: String) = JsonPrimitive(value)
internal fun strings(vararg values: String) = JsonArray(values.map(::str))

@OptIn(ExperimentalSerializationApi::class)
class SchemaRegistry {
    val definitions = linkedMapOf<String, JsonElement>()

    fun schema(d: SerialDescriptor): JsonObject {
        val plain = when {
            d.serialName.removeSuffix("?") == "kotlinx.serialization.json.JsonObject" -> obj("type" to str("object"), "additionalProperties" to JsonPrimitive(true))
            d.serialName.startsWith("kotlinx.serialization.json.") -> obj()
            d.kind is PrimitiveKind -> primitive(d.kind)
            d.kind == SerialKind.ENUM -> obj("type" to str("string"), "enum" to JsonArray((0 until d.elementsCount).map { str(d.getElementName(it)) }))
            d.kind == StructureKind.LIST -> obj("type" to str("array"), "items" to schema(d.getElementDescriptor(0)))
            d.kind == StructureKind.MAP -> obj("type" to str("object"), "additionalProperties" to schema(d.getElementDescriptor(1)))
            d.kind == StructureKind.CLASS -> reference(d)
            else -> error("Unsupported OpenAPI descriptor: ${d.serialName}")
        }
        return if (d.isNullable) obj("anyOf" to JsonArray(listOf(plain, obj("type" to str("null"))))) else plain
    }

    private fun reference(d: SerialDescriptor): JsonObject {
        val name = d.serialName.removeSuffix("?").substringAfterLast('.')
        if (name !in definitions) {
            definitions[name] = obj()
            val properties = (0 until d.elementsCount).associate { d.getElementName(it) to schema(d.getElementDescriptor(it)) }
            val required = (0 until d.elementsCount).filter { !d.isElementOptional(it) }.map { str(d.getElementName(it)) }
            definitions[name] = obj("type" to str("object"), "properties" to JsonObject(properties), "required" to JsonArray(required))
        }
        return obj("\$ref" to str("#/components/schemas/$name"))
    }

    private fun primitive(kind: SerialKind): JsonObject = when (kind) {
        PrimitiveKind.BOOLEAN -> obj("type" to str("boolean"))
        PrimitiveKind.LONG -> obj("type" to str("integer"), "format" to str("int64"))
        PrimitiveKind.INT, PrimitiveKind.SHORT, PrimitiveKind.BYTE -> obj("type" to str("integer"), "format" to str("int32"))
        PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> obj("type" to str("number"))
        else -> obj("type" to str("string"))
    }
}
