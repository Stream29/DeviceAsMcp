package io.github.stream29.mcp.device.protocol

import kotlinx.serialization.Serializable

const val TERMINAL_FAST_PATH_MILLIS: Long = 2_000
const val TERMINAL_OUTPUT_LIMIT_BYTES: Int = 256 * 1024
const val TERMINAL_RETENTION_MILLIS: Long = 30 * 60 * 1_000

@Serializable
data class LaunchTerminalSessionRequest(
    val deviceId: DeviceId,
    val script: String,
    val tty: Boolean = false,
)

@Serializable
data class TerminalSessionInputRequest(
    val sessionId: TerminalSessionId,
    val stdin: String = "",
    val eof: Boolean = false,
)

@Serializable
data class TerminalSessionOutputRequest(val sessionId: TerminalSessionId)
