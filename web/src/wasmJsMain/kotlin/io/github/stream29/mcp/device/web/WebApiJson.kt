package io.github.stream29.mcp.device.web

import io.github.stream29.mcp.device.protocol.ProtocolJson
import kotlinx.serialization.json.Json

internal val WebApiJson = Json(ProtocolJson) {
    ignoreUnknownKeys = true
}
