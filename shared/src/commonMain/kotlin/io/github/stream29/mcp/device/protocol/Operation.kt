package io.github.stream29.mcp.device.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

const val OPERATION_PROTOCOL_VERSION: Int = 1

@Serializable
data class OperationEnvelope(
    val version: Int = OPERATION_PROTOCOL_VERSION,
    val operationId: OperationId,
    val deviceId: DeviceId,
    val payload: OperationPayload,
    val kind: OperationKind = payload.kind,
) {
    init {
        require(kind == payload.kind) { "Operation kind does not match its payload" }
    }
}

@Serializable
enum class OperationKind {
    @SerialName("launch_terminal_session")
    LAUNCH_TERMINAL_SESSION,

    @SerialName("terminal_session_input")
    TERMINAL_SESSION_INPUT,

    @SerialName("terminal_session_output")
    TERMINAL_SESSION_OUTPUT,

    @SerialName("prepare_file_source")
    PREPARE_FILE_SOURCE,

    @SerialName("prepare_file_destination")
    PREPARE_FILE_DESTINATION,

    @SerialName("start_file_source")
    START_FILE_SOURCE,

    @SerialName("start_file_destination")
    START_FILE_DESTINATION,

    @SerialName("cancel_file_transfer")
    CANCEL_FILE_TRANSFER,

    @SerialName("cancel_operation")
    CANCEL_OPERATION,
}

@Serializable
sealed interface OperationPayload {
    @Serializable
    @SerialName("launch_terminal_session")
    data class LaunchTerminalSession(
        val script: String,
        val tty: Boolean = false,
    ) : OperationPayload

    @Serializable
    @SerialName("terminal_session_input")
    data class TerminalSessionInput(
        val sessionId: TerminalSessionId,
        val stdin: String = "",
        val eof: Boolean = false,
    ) : OperationPayload

    @Serializable
    @SerialName("terminal_session_output")
    data class TerminalSessionOutput(
        val sessionId: TerminalSessionId,
    ) : OperationPayload

    @Serializable
    @SerialName("prepare_file_source")
    data class PrepareFileSource(
        val transferId: TransferId,
        val sourcePath: String,
        val relayInstanceId: InstanceId,
    ) : OperationPayload

    @Serializable
    @SerialName("prepare_file_destination")
    data class PrepareFileDestination(
        val transferId: TransferId,
        val destinationPath: String,
        val relayInstanceId: InstanceId,
    ) : OperationPayload

    @Serializable
    @SerialName("start_file_source")
    data class StartFileSource(
        val transferId: TransferId,
        val relayInstanceId: InstanceId,
    ) : OperationPayload

    @Serializable
    @SerialName("start_file_destination")
    data class StartFileDestination(
        val transferId: TransferId,
        val relayInstanceId: InstanceId,
    ) : OperationPayload

    @Serializable
    @SerialName("cancel_file_transfer")
    data class CancelFileTransfer(val transferId: TransferId) : OperationPayload

    @Serializable
    @SerialName("cancel_operation")
    data class CancelOperation(val targetOperationId: OperationId) : OperationPayload
}

val OperationPayload.kind: OperationKind
    get() = when (this) {
        is OperationPayload.LaunchTerminalSession -> OperationKind.LAUNCH_TERMINAL_SESSION
        is OperationPayload.TerminalSessionInput -> OperationKind.TERMINAL_SESSION_INPUT
        is OperationPayload.TerminalSessionOutput -> OperationKind.TERMINAL_SESSION_OUTPUT
        is OperationPayload.PrepareFileSource -> OperationKind.PREPARE_FILE_SOURCE
        is OperationPayload.PrepareFileDestination -> OperationKind.PREPARE_FILE_DESTINATION
        is OperationPayload.StartFileSource -> OperationKind.START_FILE_SOURCE
        is OperationPayload.StartFileDestination -> OperationKind.START_FILE_DESTINATION
        is OperationPayload.CancelFileTransfer -> OperationKind.CANCEL_FILE_TRANSFER
        is OperationPayload.CancelOperation -> OperationKind.CANCEL_OPERATION
    }

@Serializable
data class OperationResultEnvelope(
    val operationId: OperationId,
    val result: OperationResult,
)

@Serializable
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@JsonClassDiscriminator("status")
sealed interface OperationResult {
    @Serializable
    @SerialName("success")
    data class Success(
        @SerialName("result")
        val payload: OperationResultPayload,
    ) : OperationResult

    @Serializable
    @SerialName("failure")
    data class Failure(
        val errorCode: OperationErrorCode,
        val message: String,
        val details: Map<String, String> = emptyMap(),
    ) : OperationResult
}

@Serializable
enum class OperationErrorCode {
    INVALID_REQUEST,
    UNSUPPORTED_VERSION,
    DEVICE_OFFLINE,
    DEVICE_OWNER_STALE,
    OPERATION_TIMEOUT,
    OPERATION_CANCELLED,
    TERMINAL_NOT_FOUND,
    PROCESS_START_FAILED,
    PATH_NOT_FOUND,
    PATH_NOT_READABLE,
    DESTINATION_EXISTS,
    DESTINATION_NOT_WRITABLE,
    MANIFEST_TOO_LARGE,
    FILE_INTEGRITY_MISMATCH,
    SERVER_INSTANCE_LOST,
    INTERNAL_ERROR,
}

@Serializable
sealed interface OperationResultPayload {
    @Serializable
    @SerialName("terminal_launch")
    data class TerminalLaunch(
        val status: TerminalLaunchStatus,
        val sessionId: TerminalSessionId? = null,
        val stdout: String = "",
        val stderr: String = "",
        val exitCode: Int? = null,
        val truncated: Boolean = false,
        val discardedBytes: Long = 0,
    ) : OperationResultPayload

    @Serializable
    @SerialName("terminal_input")
    data class TerminalInput(val accepted: Boolean) : OperationResultPayload

    @Serializable
    @SerialName("terminal_output")
    data class TerminalOutput(
        val running: Boolean,
        val stdout: String = "",
        val stderr: String = "",
        val exitCode: Int? = null,
        val truncated: Boolean = false,
        val discardedBytes: Long = 0,
    ) : OperationResultPayload

    @Serializable
    @SerialName("file_preflight")
    data class FilePreflight(
        val accepted: Boolean,
    ) : OperationResultPayload

    @Serializable
    @SerialName("acknowledged")
    data class Acknowledged(val accepted: Boolean = true) : OperationResultPayload
}

@Serializable
enum class TerminalLaunchStatus { COMPLETED, RUNNING }
