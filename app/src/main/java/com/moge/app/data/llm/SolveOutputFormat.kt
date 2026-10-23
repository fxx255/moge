package com.moge.app.data.llm

import com.moge.app.data.credential.AiApiProtocol
import com.moge.app.domain.ConversationMode
import kotlinx.serialization.json.*

/** Complements the solving prompt with transport constraints, using the same policy as lixing. */
internal fun solveOutputFormatFields(
    mode: ConversationMode,
    capability: ModelCapability?,
    protocol: AiApiProtocol,
): JsonObject = buildJsonObject {
    if (mode != ConversationMode.SOLVE) return@buildJsonObject
    val format = when {
        capability?.supportsJsonSchema == true -> solveJsonSchema(protocol)
        capability?.supportsJsonObject == false -> return@buildJsonObject
        else -> buildJsonObject { put("type", "json_object") }
    }
    if (protocol == AiApiProtocol.RESPONSES) {
        put("text", buildJsonObject { put("format", format) })
    } else put("response_format", format)
}

private fun solveJsonSchema(protocol: AiApiProtocol): JsonObject {
    val definition = buildJsonObject {
        put("name", "solve_reply")
        put("strict", false)
        put("schema", buildJsonObject {
            put("type", "object")
            put("required", buildJsonArray { add("conversation_title"); add("reply") })
            put("properties", buildJsonObject {
                for (field in listOf("conversation_title", "answer", "reply")) {
                    put(field, buildJsonObject { put("type", "string") })
                }
                for (field in listOf("plots", "diagrams")) {
                    put(field, buildJsonObject {
                        put("type", "array")
                        // Existing figure parsers own the extensible plot/diagram contracts.
                        put("items", buildJsonObject {
                            put("type", "object"); put("additionalProperties", true)
                        })
                    })
                }
            })
            put("additionalProperties", true)
        })
    }
    return buildJsonObject {
        put("type", "json_schema")
        if (protocol == AiApiProtocol.RESPONSES) definition.forEach { (key, value) -> put(key, value) }
        else put("json_schema", definition)
    }
}
