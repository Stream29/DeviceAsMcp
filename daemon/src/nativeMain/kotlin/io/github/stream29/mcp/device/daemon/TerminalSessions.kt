package io.github.stream29.mcp.device.daemon

import io.github.stream29.mcp.device.protocol.OperationResultPayload
import io.github.stream29.mcp.device.protocol.TERMINAL_FAST_PATH_MILLIS
import io.github.stream29.mcp.device.protocol.TERMINAL_RETENTION_MILLIS
import io.github.stream29.mcp.device.protocol.TerminalOutputBuffer
import io.github.stream29.mcp.device.protocol.TerminalLaunchStatus
import io.github.stream29.mcp.device.protocol.TerminalSessionId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock

internal class TerminalSessions(private val scope: CoroutineScope) {
    private data class Session(
        val process: NativeProcess,
        val output: TerminalOutputBuffer,
        val completed: CompletableDeferred<Int>,
        var exitCode: Int? = null,
        var endedAtMillis: Long? = null,
    )

    private val mutex = Mutex()
    private val sessions = mutableMapOf<TerminalSessionId, Session>()

    init {
        scope.launch {
            while (isActive) {
                delay(PRUNE_INTERVAL_MILLIS)
                prune()
            }
        }
    }

    suspend fun launch(script: String, tty: Boolean): OperationResultPayload.TerminalLaunch {
        prune()
        val sessionId = TerminalSessionId(randomId())
        val output = TerminalOutputBuffer()
        val completed = CompletableDeferred<Int>()
        val process = runCatching {
            NativeProcess(
                shellCommand(script),
                tty,
                stdout = { text -> mutex.withLock { output.appendStdout(text) } },
                stderr = { text -> mutex.withLock { output.appendStderr(text) } },
            )
        }.getOrElse { failure ->
            throw TerminalException("Failed to start process: ${failure.message ?: "unknown error"}")
        }
        val created = Session(process, output, completed)
        mutex.withLock { sessions[sessionId] = created }
        scope.launch {
            val exit = runCatching { process.waitFor() }.getOrElse { failure ->
                mutex.withLock {
                    output.appendStderr(
                        "Failed to monitor process: ${failure.message ?: "unknown error"}\n",
                    )
                }
                -1
            }
            mutex.withLock {
                created.exitCode = exit
                created.endedAtMillis = Clock.System.now().toEpochMilliseconds()
            }
            completed.complete(exit)
        }
        withTimeoutOrNull(TERMINAL_FAST_PATH_MILLIS) { completed.await() }
        return mutex.withLock {
            if (created.exitCode == null) {
                OperationResultPayload.TerminalLaunch(
                    status = TerminalLaunchStatus.RUNNING,
                    sessionId = sessionId,
                )
            } else {
                val buffered = created.output.consume()
                val result = OperationResultPayload.TerminalLaunch(
                    status = TerminalLaunchStatus.COMPLETED,
                    stdout = buffered.stdout,
                    stderr = buffered.stderr,
                    exitCode = created.exitCode,
                    truncated = buffered.truncated,
                    discardedBytes = buffered.discardedBytes,
                )
                sessions.remove(sessionId)
                process.close()
                result
            }
        }
    }

    suspend fun input(
        sessionId: TerminalSessionId,
        stdin: String,
        eof: Boolean,
    ): OperationResultPayload.TerminalInput {
        val session = mutex.withLock {
            sessions[sessionId]?.takeIf { it.exitCode == null }
        } ?: return OperationResultPayload.TerminalInput(false)
        return runCatching {
            if (stdin.isNotEmpty()) {
                check(session.process.write(stdin.encodeToByteArray())) { "Process input is unavailable" }
            }
            if (eof) session.process.closeInput()
        }.fold(
            onSuccess = { OperationResultPayload.TerminalInput(true) },
            onFailure = { OperationResultPayload.TerminalInput(false) },
        )
    }

    suspend fun output(sessionId: TerminalSessionId): OperationResultPayload.TerminalOutput {
        prune()
        return mutex.withLock {
            val session = sessions[sessionId] ?: error("Terminal session not found")
            val buffered = session.output.consume()
            val result = OperationResultPayload.TerminalOutput(
                running = session.exitCode == null,
                stdout = buffered.stdout,
                stderr = buffered.stderr,
                exitCode = session.exitCode,
                truncated = buffered.truncated,
                discardedBytes = buffered.discardedBytes,
            )
            result
        }
    }

    private suspend fun prune() {
        val now = Clock.System.now().toEpochMilliseconds()
        val expired = mutex.withLock {
            sessions.filterValues { session ->
                session.endedAtMillis?.let { now - it >= TERMINAL_RETENTION_MILLIS } == true
            }.also { values -> values.keys.forEach(sessions::remove) }
        }
        expired.values.forEach { it.process.close() }
    }

    companion object {
        private const val PRUNE_INTERVAL_MILLIS = 60_000L
    }
}

internal class TerminalException(message: String) : IllegalStateException(message)
