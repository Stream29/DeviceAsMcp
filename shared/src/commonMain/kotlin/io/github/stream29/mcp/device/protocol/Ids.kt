package io.github.stream29.mcp.device.protocol

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

@Serializable
@JvmInline
value class UserId(val value: String) {
    init { requireWireId(value) }
}

@Serializable
@JvmInline
value class DeviceId(val value: String) {
    init { requireWireId(value) }
}

@Serializable
@JvmInline
value class OperationId(val value: String) {
    init { requireWireId(value) }
}

@Serializable
@JvmInline
value class ConnectionId(val value: String) {
    init { requireWireId(value) }
}

@Serializable
@JvmInline
value class InstanceId(val value: String) {
    init { requireWireId(value) }
}

@Serializable
@JvmInline
value class TerminalSessionId(val value: String) {
    init { requireWireId(value) }
}

@Serializable
@JvmInline
value class TransferId(val value: String) {
    init { requireWireId(value) }
}

private fun requireWireId(value: String) {
    require(
        value.length in 1..128 &&
            value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._~-" },
    ) {
        "Wire IDs must contain 1 to 128 URL-safe characters"
    }
}
