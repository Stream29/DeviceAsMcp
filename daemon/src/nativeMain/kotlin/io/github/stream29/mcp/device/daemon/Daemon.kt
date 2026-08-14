package io.github.stream29.mcp.device.daemon

import io.github.stream29.mcp.device.protocol.OperationEnvelope
import io.github.stream29.mcp.device.protocol.OperationErrorCode
import io.github.stream29.mcp.device.protocol.OperationId
import io.github.stream29.mcp.device.protocol.OperationPayload
import io.github.stream29.mcp.device.protocol.OperationResult
import io.github.stream29.mcp.device.protocol.OperationResultEnvelope
import io.github.stream29.mcp.device.protocol.OperationResultPayload
import io.github.stream29.mcp.device.protocol.ProtocolJson
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.plugins.sse.serverSentEvents
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class DeviceDaemon(
    private val config: DaemonConfig,
    private val client: HttpClient,
    private val scope: CoroutineScope,
) {
    private val terminals = TerminalSessions(scope)
    private val files = FileTransfers(config, client, scope)
    private val results = mutableMapOf<OperationId, OperationResultEnvelope>()
    private val queued = mutableMapOf<OperationId, kotlinx.coroutines.Job>()
    private val submissions = mutableMapOf<OperationId, kotlinx.coroutines.Job>()
    private val stateMutex = Mutex()

    suspend fun run() {
        while (true) {
            runCatching {
                client.serverSentEvents(
                    "${config.serverUrl}/daemon/connect?deviceId=${config.credential.deviceId.value}",
                    request = { header("X-Device-Secret", config.credential.secret) },
                ) {
                    files.reportCoordinatorLosses()
                    incoming.collect { event ->
                        if (event.event != "operation" || event.data == null) return@collect
                        val operation = ProtocolJson.decodeFromString<OperationEnvelope>(event.data!!)
                        if (operation.version != io.github.stream29.mcp.device.protocol.OPERATION_PROTOCOL_VERSION) {
                            queueResult(
                                OperationResultEnvelope(
                                    operation.operationId,
                                    OperationResult.Failure(
                                        OperationErrorCode.UNSUPPORTED_VERSION,
                                        "Unsupported operation protocol version ${operation.version}",
                                    ),
                                ),
                            )
                            return@collect
                        }
                        if (operation.deviceId != config.credential.deviceId) return@collect
                        stateMutex.withLock { results[operation.operationId] }?.let {
                            queueResult(it)
                            return@collect
                        }
                        val cancellation = operation.payload as? OperationPayload.CancelOperation
                        if (cancellation != null) {
                            stateMutex.withLock { queued.remove(cancellation.targetOperationId) }?.cancel()
                            return@collect
                        }
                        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                            stateMutex.withLock { queued.remove(operation.operationId) }
                            val result = execute(operation)
                            stateMutex.withLock {
                                results[operation.operationId] = result
                                trimResults()
                            }
                            queueResult(result)
                        }
                        stateMutex.withLock { queued[operation.operationId] = job }
                        job.start()
                    }
                }
            }.onFailure { failure ->
                println("connection lost: ${failure.message}; reconnecting")
            }
            delay(RECONNECT_DELAY_MILLIS)
        }
    }

    private suspend fun execute(operation: OperationEnvelope): OperationResultEnvelope {
        val result = runCatching {
            when (val payload = operation.payload) {
                is OperationPayload.LaunchTerminalSession -> terminals.launch(payload.script, payload.tty)
                is OperationPayload.TerminalSessionInput ->
                    terminals.input(payload.sessionId, payload.stdin, payload.eof)
                is OperationPayload.TerminalSessionOutput -> terminals.output(payload.sessionId)
                is OperationPayload.PrepareFileSource -> {
                    files.prepareSource(
                        payload.transferId,
                        payload.sourcePath,
                        payload.relayInstanceId,
                    )
                    OperationResultPayload.FilePreflight(true)
                }
                is OperationPayload.PrepareFileDestination -> {
                    files.prepareDestination(
                        payload.transferId,
                        payload.destinationPath,
                        payload.relayInstanceId,
                    )
                    OperationResultPayload.FilePreflight(true)
                }
                is OperationPayload.StartFileSource -> {
                    files.startSource(payload.transferId, payload.relayInstanceId)
                    OperationResultPayload.Acknowledged()
                }
                is OperationPayload.StartFileDestination -> {
                    files.startDestination(payload.transferId, payload.relayInstanceId)
                    OperationResultPayload.Acknowledged()
                }
                is OperationPayload.CancelFileTransfer -> {
                    files.cancel(payload.transferId)
                    OperationResultPayload.Acknowledged()
                }
                is OperationPayload.CancelOperation -> OperationResultPayload.Acknowledged(false)
            }
        }.fold(
            onSuccess = { OperationResult.Success(it) },
            onFailure = { failure ->
                val fileFailure = failure as? FileTransferException
                val code = when {
                    fileFailure != null -> fileFailure.code
                    failure is TerminalException -> OperationErrorCode.PROCESS_START_FAILED
                    failure.message?.contains("Terminal session") == true -> OperationErrorCode.TERMINAL_NOT_FOUND
                    else -> OperationErrorCode.INTERNAL_ERROR
                }
                OperationResult.Failure(code, failure.message ?: "Operation failed")
            },
        )
        return OperationResultEnvelope(operation.operationId, result)
    }

    private suspend fun queueResult(result: OperationResultEnvelope) {
        lateinit var job: kotlinx.coroutines.Job
        job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                submitResult(result)
            } finally {
                stateMutex.withLock {
                    if (submissions[result.operationId] === job) submissions.remove(result.operationId)
                }
            }
        }
        val shouldStart = stateMutex.withLock {
            val existing = submissions[result.operationId]
            if (existing != null && !existing.isCompleted) {
                false
            } else {
                submissions[result.operationId] = job
                true
            }
        }
        if (shouldStart) job.start() else job.cancel()
    }

    private suspend fun submitResult(result: OperationResultEnvelope) {
        val deadline = kotlin.time.Clock.System.now().toEpochMilliseconds() + RESULT_RETRY_WINDOW_MILLIS
        while (kotlin.time.Clock.System.now().toEpochMilliseconds() < deadline) {
            val response = runCatching {
                client.post(
                    "${config.serverUrl}/daemon/result/${config.credential.deviceId.value}",
                ) {
                    header("X-Device-Secret", config.credential.secret)
                    contentType(ContentType.Application.Json)
                    setBody(result)
                }
            }.getOrNull()
            if (response?.status?.isSuccess() == true || response?.status == HttpStatusCode.Conflict) return
            response?.let { runCatching { it.bodyAsText() } }
            if (response?.status == HttpStatusCode.Unauthorized || response?.status == HttpStatusCode.Forbidden) {
                return
            }
            delay(RESULT_RETRY_DELAY_MILLIS)
        }
    }

    private fun trimResults() {
        while (results.size > RESULT_CACHE_SIZE) results.remove(results.keys.first())
    }

    companion object {
        private const val RECONNECT_DELAY_MILLIS = 2_000L
        private const val RESULT_RETRY_DELAY_MILLIS = 1_000L
        private const val RESULT_RETRY_WINDOW_MILLIS = 60_000L
        private const val RESULT_CACHE_SIZE = 512
    }
}
