@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
)

package io.github.stream29.mcp.device.daemon

import io.github.stream29.mcp.device.daemon.posix.damcp_close_fd_value
import io.github.stream29.mcp.device.daemon.posix.damcp_poll_posix
import io.github.stream29.mcp.device.daemon.posix.damcp_posix_process
import io.github.stream29.mcp.device.daemon.posix.damcp_spawn_posix
import io.github.stream29.mcp.device.daemon.posix.damcp_terminate_posix
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.posix.EAGAIN
import platform.posix.EINTR
import platform.posix.EIO
import platform.posix.EWOULDBLOCK
import platform.posix.errno
import platform.posix.read
import platform.posix.strerror
import platform.posix.write
import kotlin.concurrent.atomics.AtomicBoolean

internal actual class NativeProcess actual constructor(
    command: List<String>,
    private val tty: Boolean,
    private val stdout: suspend (String) -> Unit,
    private val stderr: suspend (String) -> Unit,
) {
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val completed = CompletableDeferred<Int>()
    private val inputMutex = Mutex()
    private val resourcesClosed = AtomicBoolean(false)
    private val inputClosed = AtomicBoolean(false)
    private val processExited = AtomicBoolean(false)
    private val started = startPosixProcess(command, tty)

    init {
        processScope.launch {
            val stdoutDecoder = Utf8StreamDecoder(stdout)
            val stderrDecoder = Utf8StreamDecoder(stderr)
            try {
                val exitCode = monitorUntilExit(stdoutDecoder, stderrDecoder)
                processExited.store(true)
                drainAfterExit(stdoutDecoder, stderrDecoder)
                stdoutDecoder.finish()
                stderrDecoder.finish()
                completed.complete(exitCode)
            } catch (failure: Throwable) {
                completed.completeExceptionally(failure)
            } finally {
                releaseResources()
            }
        }
    }

    actual suspend fun waitFor(): Int = completed.await()

    actual suspend fun write(value: ByteArray): Boolean = inputMutex.withLock {
        if (inputClosed.load() || completed.isCompleted) return@withLock false
        writeBytes(value)
    }

    actual suspend fun closeInput() {
        inputMutex.withLock {
            if (!inputClosed.compareAndSet(expectedValue = false, newValue = true)) return@withLock
            if (tty) writeBytes(byteArrayOf(END_OF_TRANSMISSION))
            started.input.close()
        }
    }

    actual fun close() {
        releaseResources()
    }

    private suspend fun monitorUntilExit(
        stdoutDecoder: Utf8StreamDecoder,
        stderrDecoder: Utf8StreamDecoder,
    ): Int {
        while (true) {
            drain(started.output, stdoutDecoder)
            started.error?.let { drain(it, stderrDecoder) }
            exitCodeOrNull()?.let { return it }
            delay(POLL_INTERVAL_MILLIS)
        }
    }

    private suspend fun drainAfterExit(
        stdoutDecoder: Utf8StreamDecoder,
        stderrDecoder: Utf8StreamDecoder,
    ) {
        repeat(OUTPUT_DRAIN_POLLS) {
            drain(started.output, stdoutDecoder)
            started.error?.let { drain(it, stderrDecoder) }
            if (!started.output.isOpen && started.error?.isOpen != true) return
            delay(POLL_INTERVAL_MILLIS)
        }
        drain(started.output, stdoutDecoder)
        started.error?.let { drain(it, stderrDecoder) }
    }

    private suspend fun drain(handle: PosixHandle, decoder: Utf8StreamDecoder) {
        while (handle.isOpen) {
            val bytes = ByteArray(IO_CHUNK_SIZE)
            val count = bytes.usePinned { pinned ->
                read(handle.value, pinned.addressOf(0), bytes.size.convert())
            }
            when {
                count > 0 -> decoder.accept(bytes.copyOf(count.toInt()))
                count == 0L -> {
                    handle.close()
                    return
                }
                errno == EINTR -> Unit
                errno == EAGAIN || errno == EWOULDBLOCK -> return
                tty && errno == EIO -> {
                    handle.close()
                    return
                }
                else -> error("Failed to read process output: ${posixError(errno)}")
            }
        }
    }

    private suspend fun writeBytes(bytes: ByteArray): Boolean {
        var offset = 0
        while (offset < bytes.size && started.input.isOpen) {
            val count = bytes.usePinned { pinned ->
                write(
                    started.input.value,
                    pinned.addressOf(offset),
                    (bytes.size - offset).convert(),
                )
            }
            when {
                count > 0 -> offset += count.toInt()
                count == 0L -> return false
                errno == EINTR -> Unit
                errno == EAGAIN || errno == EWOULDBLOCK -> delay(POLL_INTERVAL_MILLIS)
                else -> return false
            }
        }
        return offset == bytes.size
    }

    private fun exitCodeOrNull(): Int? = memScoped {
        val exitCode = alloc<IntVar>()
        when (val result = damcp_poll_posix(started.pid, exitCode.ptr)) {
            0 -> null
            1 -> exitCode.value
            else -> error("Failed to observe process exit: ${posixError(-result)}")
        }
    }

    private fun releaseResources() {
        if (!resourcesClosed.compareAndSet(expectedValue = false, newValue = true)) return
        inputClosed.store(true)
        if (!processExited.load()) damcp_terminate_posix(started.pid, 0)
        started.input.close()
        started.output.close()
        started.error?.close()
    }

    internal data class StartedProcess(
        val pid: Int,
        val input: PosixHandle,
        val output: PosixHandle,
        val error: PosixHandle?,
    )

    internal class PosixHandle(val value: Int) {
        private val closed = AtomicBoolean(false)
        val isOpen: Boolean get() = !closed.load()

        fun close() {
            if (closed.compareAndSet(expectedValue = false, newValue = true)) {
                damcp_close_fd_value(value)
            }
        }
    }

    companion object {
        private const val IO_CHUNK_SIZE = 8 * 1024
        private const val POLL_INTERVAL_MILLIS = 10L
        private const val OUTPUT_DRAIN_POLLS = 200
        private const val END_OF_TRANSMISSION: Byte = 0x04
    }
}

private fun startPosixProcess(
    command: List<String>,
    tty: Boolean,
): NativeProcess.StartedProcess = memScoped {
    require(command.isNotEmpty()) { "Process command cannot be empty" }
    val arguments = allocArray<CPointerVar<ByteVar>>(command.size + 1)
    command.forEachIndexed { index, argument ->
        arguments[index] = argument.cstr.getPointer(this)
    }
    arguments[command.size] = null
    val process = alloc<damcp_posix_process>()
    val result = damcp_spawn_posix(arguments, if (tty) 1 else 0, process.ptr)
    if (result != 0) error("Failed to start process: ${posixError(result)}")
    NativeProcess.StartedProcess(
        pid = process.pid,
        input = NativeProcess.PosixHandle(process.input_fd),
        output = NativeProcess.PosixHandle(process.output_fd),
        error = process.error_fd.takeIf { it >= 0 }?.let { NativeProcess.PosixHandle(it) },
    )
}

private fun posixError(code: Int): String =
    strerror(code)?.toKString()?.let { "$it (errno $code)" } ?: "errno $code"
