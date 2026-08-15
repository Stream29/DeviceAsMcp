package io.github.stream29.mcp.device.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object RemoteMcpTools {
    const val LIST_DEVICE = "list_device"
    const val UPDATE_DEVICE_DESCRIPTION = "update_device_description"
    const val LAUNCH_TERMINAL_SESSION = "launch_terminal_session"
    const val TERMINAL_SESSION_INPUT = "terminal_session_input"
    const val TERMINAL_SESSION_OUTPUT = "terminal_session_output"
    const val LAUNCH_FILE_TRANSFER = "launch_file_transfer"
    const val FILE_TRANSFER_STATUS = "file_transfer_status"
    const val CANCEL_FILE_TRANSFER = "cancel_file_transfer"

    val names: Set<String> = setOf(
        LIST_DEVICE,
        UPDATE_DEVICE_DESCRIPTION,
        LAUNCH_TERMINAL_SESSION,
        TERMINAL_SESSION_INPUT,
        TERMINAL_SESSION_OUTPUT,
        LAUNCH_FILE_TRANSFER,
        FILE_TRANSFER_STATUS,
        CANCEL_FILE_TRANSFER,
    )

    val inputSchemas: Map<String, JsonObject> = mapOf(
        LIST_DEVICE to objectSchema(),
        UPDATE_DEVICE_DESCRIPTION to objectSchema(
            required = listOf("deviceId", "description"),
            properties = mapOf(
                "deviceId" to stringSchema(),
                "description" to stringSchema(),
            ),
        ),
        LAUNCH_TERMINAL_SESSION to objectSchema(
            required = listOf("deviceId", "script"),
            properties = mapOf(
                "deviceId" to stringSchema(),
                "script" to stringSchema(),
                "tty" to booleanSchema(),
            ),
        ),
        TERMINAL_SESSION_INPUT to objectSchema(
            required = listOf("sessionId"),
            properties = mapOf(
                "sessionId" to stringSchema(),
                "stdin" to stringSchema(),
                "eof" to booleanSchema(),
            ),
        ),
        TERMINAL_SESSION_OUTPUT to objectSchema(
            required = listOf("sessionId"),
            properties = mapOf("sessionId" to stringSchema()),
        ),
        LAUNCH_FILE_TRANSFER to objectSchema(
            required = listOf("sourceDeviceId", "sourcePath", "destinationDeviceId", "destinationPath"),
            properties = mapOf(
                "sourceDeviceId" to stringSchema(),
                "sourcePath" to stringSchema(),
                "destinationDeviceId" to stringSchema(),
                "destinationPath" to stringSchema(),
            ),
        ),
        FILE_TRANSFER_STATUS to objectSchema(
            required = listOf("transferId"),
            properties = mapOf("transferId" to stringSchema()),
        ),
        CANCEL_FILE_TRANSFER to objectSchema(
            required = listOf("transferId"),
            properties = mapOf("transferId" to stringSchema()),
        ),
    )

    fun asJson(): String = Json.encodeToString(
        JsonArray.serializer(),
        JsonArray(inputSchemas.map { (name, schema) -> JsonObject(mapOf("name" to JsonPrimitive(name), "inputSchema" to schema)) }),
    )

    private fun objectSchema(
        required: List<String> = emptyList(),
        properties: Map<String, JsonObject> = emptyMap(),
    ): JsonObject = JsonObject(
        buildMap {
            put("type", JsonPrimitive("object"))
            put("properties", JsonObject(properties))
            if (required.isNotEmpty()) put("required", JsonArray(required.map(::JsonPrimitive)))
            put("additionalProperties", JsonPrimitive(false))
        },
    )

    private fun stringSchema() = JsonObject(mapOf("type" to JsonPrimitive("string")))
    private fun booleanSchema() = JsonObject(mapOf("type" to JsonPrimitive("boolean")))
}
