package io.github.stream29.mcp.device.protocol

import kotlinx.serialization.json.Json

val ProtocolJson: Json = Json {
    classDiscriminator = "type"
    encodeDefaults = true
    ignoreUnknownKeys = false
    explicitNulls = false
}
