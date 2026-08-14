@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
)

package io.github.stream29.mcp.device.daemon

import io.github.stream29.mcp.device.daemon.conpty.damcp_close_windows_pseudo_console
import io.github.stream29.mcp.device.daemon.conpty.damcp_spawn_windows_pty
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
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
import platform.windows.CREATE_NO_WINDOW
import platform.windows.CloseHandle
import platform.windows.CreatePipe
import platform.windows.CreateProcessW
import platform.windows.ERROR_BROKEN_PIPE
import platform.windows.GetExitCodeProcess
import platform.windows.GetLastError
import platform.windows.HANDLE_FLAG_INHERIT
import platform.windows.PROCESS_INFORMATION
import platform.windows.PeekNamedPipe
import platform.windows.ReadFile
import platform.windows.ResumeThread
import platform.windows.SECURITY_ATTRIBUTES
import platform.windows.STARTF_USESTDHANDLES
import platform.windows.STARTUPINFOW
import platform.windows.SetHandleInformation
import platform.windows.TerminateProcess
import platform.windows.WAIT_OBJECT_0
import platform.windows.WAIT_TIMEOUT
import platform.windows.WaitForSingleObject
import platform.windows.WriteFile
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
    private val started = if (tty) startPseudoTerminal(command) else startPipeProcess(command)

    init {
        processScope.launch {
            val stdoutDecoder = Utf8StreamDecoder(stdout)
            val stderrDecoder = Utf8StreamDecoder(stderr)
            try {
                while (true) {
                    drain(started.output, stdoutDecoder)
                    started.error?.let { drain(it, stderrDecoder) }
                    val exitCode = exitCodeOrNull()
                    if (exitCode != null) {
                        drainAfterExit(stdoutDecoder, stderrDecoder)
                        stdoutDecoder.finish()
                        stderrDecoder.finish()
                        completed.complete(exitCode)
                        break
                    }
                    delay(10)
                }
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
        val bytes = if (tty) {
            value.decodeToString()
                .replace("\r\n", "\r")
                .replace("\n", "\r")
                .replace("\b", "\u007f")
                .encodeToByteArray()
        } else {
            value
        }
        var offset = 0
        while (offset < bytes.size) {
            val written = memScoped {
                val count = alloc<UIntVar>()
                val success = bytes.usePinned { pinned ->
                    WriteFile(
                        started.input.value,
                        pinned.addressOf(offset),
                        (bytes.size - offset).toUInt(),
                        count.ptr,
                        null,
                    )
                }
                if (success == 0) return@withLock false
                count.value.toInt()
            }
            if (written <= 0) return@withLock false
            offset += written
        }
        true
    }

    actual suspend fun closeInput() {
        inputMutex.withLock {
            if (inputClosed.compareAndSet(expectedValue = false, newValue = true)) {
                started.input.close()
            }
        }
    }

    actual fun close() {
        releaseResources()
    }

    private suspend fun drainAfterExit(
        stdoutDecoder: Utf8StreamDecoder,
        stderrDecoder: Utf8StreamDecoder,
    ) {
        var idlePolls = 0
        repeat(OUTPUT_DRAIN_POLLS) {
            val bytesRead = drain(started.output, stdoutDecoder) +
                (started.error?.let { drain(it, stderrDecoder) } ?: 0)
            if (bytesRead == 0) {
                idlePolls++
                if (idlePolls >= OUTPUT_DRAIN_IDLE_POLLS) return
            } else {
                idlePolls = 0
            }
            delay(POLL_INTERVAL_MILLIS)
        }
    }

    private suspend fun drain(handle: WindowsHandle, decoder: Utf8StreamDecoder): Int {
        var total = 0
        while (handle.isOpen) {
            val available = availableBytes(handle.value)
            if (available <= 0) return total
            val bytes = ByteArray(minOf(available, IO_CHUNK_SIZE))
            val count = memScoped {
                val read = alloc<UIntVar>()
                val success = bytes.usePinned { pinned ->
                    ReadFile(handle.value, pinned.addressOf(0), bytes.size.toUInt(), read.ptr, null)
                }
                if (success == 0 && GetLastError() == ERROR_BROKEN_PIPE.toUInt()) return total
                checkWindowsSuccess(success, "read process output")
                read.value.toInt()
            }
            if (count <= 0) return total
            decoder.accept(bytes.copyOf(count))
            total += count
        }
        return total
    }

    private fun availableBytes(handle: CPointer<out CPointed>): Int = memScoped {
        val available = alloc<UIntVar>()
        val success = PeekNamedPipe(handle, null, 0u, null, available.ptr, null)
        if (success == 0 && GetLastError() == ERROR_BROKEN_PIPE.toUInt()) return@memScoped 0
        checkWindowsSuccess(success, "inspect process output")
        available.value.toInt()
    }

    private fun exitCodeOrNull(): Int? = when (WaitForSingleObject(started.process.value, 0u)) {
        WAIT_TIMEOUT.toUInt() -> null
        WAIT_OBJECT_0 -> memScoped {
            val exitCode = alloc<UIntVar>()
            checkWindowsSuccess(
                GetExitCodeProcess(started.process.value, exitCode.ptr),
                "read process exit code",
            )
            exitCode.value.toInt()
        }
        else -> error("Failed to observe process exit: Windows error ${GetLastError()}")
    }

    private fun releaseResources() {
        if (!resourcesClosed.compareAndSet(expectedValue = false, newValue = true)) return
        inputClosed.store(true)
        started.input.close()
        started.pseudoConsole?.let(::damcp_close_windows_pseudo_console)
        started.output.close()
        started.error?.close()
        started.process.close()
    }

    internal data class StartedProcess(
        val process: WindowsHandle,
        val input: WindowsHandle,
        val output: WindowsHandle,
        val error: WindowsHandle?,
        val pseudoConsole: COpaquePointer?,
    )

    internal class WindowsHandle(val value: CPointer<out CPointed>) {
        private val closed = AtomicBoolean(false)
        val isOpen: Boolean get() = !closed.load()

        fun close() {
            if (closed.compareAndSet(expectedValue = false, newValue = true)) {
                CloseHandle(value)
            }
        }
    }

    companion object {
        private const val IO_CHUNK_SIZE = 8 * 1024
        private const val POLL_INTERVAL_MILLIS = 10L
        private const val OUTPUT_DRAIN_POLLS = 200
        private const val OUTPUT_DRAIN_IDLE_POLLS = 10
    }
}

private fun startPseudoTerminal(command: List<String>): NativeProcess.StartedProcess = memScoped {
    require(command.isNotEmpty()) { "Process command cannot be empty" }
    val process = alloc<COpaquePointerVar>()
    val thread = alloc<COpaquePointerVar>()
    val pseudoConsole = alloc<COpaquePointerVar>()
    val stdin = alloc<COpaquePointerVar>()
    val output = alloc<COpaquePointerVar>()
    val result = damcp_spawn_windows_pty(
        process_out = process.ptr,
        thread_out = thread.ptr,
        pseudo_console_out = pseudoConsole.ptr,
        stdin_write_out = stdin.ptr,
        output_read_out = output.ptr,
        command_line = windowsStringBuffer(command.windowsCommandLine()),
        columns = 120.toShort(),
        rows = 30.toShort(),
    )
    if (result != 0) error("Failed to start Windows pseudoterminal: error $result")
    val processHandle = requireNotNull(process.value)
    val threadHandle = requireNotNull(thread.value)
    val pseudoConsoleHandle = requireNotNull(pseudoConsole.value)
    val stdinHandle = requireNotNull(stdin.value)
    val outputHandle = requireNotNull(output.value)
    try {
        if (ResumeThread(threadHandle) == UInt.MAX_VALUE) {
            error("Failed to resume Windows pseudoterminal: error ${GetLastError()}")
        }
        NativeProcess.StartedProcess(
            process = NativeProcess.WindowsHandle(processHandle),
            input = NativeProcess.WindowsHandle(stdinHandle),
            output = NativeProcess.WindowsHandle(outputHandle),
            error = null,
            pseudoConsole = pseudoConsoleHandle,
        )
    } catch (failure: Throwable) {
        TerminateProcess(processHandle, 1u)
        damcp_close_windows_pseudo_console(pseudoConsoleHandle)
        CloseHandle(stdinHandle)
        CloseHandle(outputHandle)
        CloseHandle(processHandle)
        throw failure
    } finally {
        CloseHandle(threadHandle)
    }
}

private fun startPipeProcess(command: List<String>): NativeProcess.StartedProcess = memScoped {
    require(command.isNotEmpty()) { "Process command cannot be empty" }
    val security = alloc<SECURITY_ATTRIBUTES>().apply {
        nLength = sizeOf<SECURITY_ATTRIBUTES>().toUInt()
        lpSecurityDescriptor = null
        bInheritHandle = 1
    }
    val stdin = createWindowsPipe(security.ptr)
    val stdout = createWindowsPipe(security.ptr)
    val stderr = createWindowsPipe(security.ptr)
    try {
        checkWindowsSuccess(
            SetHandleInformation(stdin.write, HANDLE_FLAG_INHERIT.toUInt(), 0u),
            "make parent stdin handle non-inheritable",
        )
        checkWindowsSuccess(
            SetHandleInformation(stdout.read, HANDLE_FLAG_INHERIT.toUInt(), 0u),
            "make parent stdout handle non-inheritable",
        )
        checkWindowsSuccess(
            SetHandleInformation(stderr.read, HANDLE_FLAG_INHERIT.toUInt(), 0u),
            "make parent stderr handle non-inheritable",
        )
        val startup = alloc<STARTUPINFOW>().apply {
            cb = sizeOf<STARTUPINFOW>().toUInt()
            lpReserved = null
            lpDesktop = null
            lpTitle = null
            dwX = 0u
            dwY = 0u
            dwXSize = 0u
            dwYSize = 0u
            dwXCountChars = 0u
            dwYCountChars = 0u
            dwFillAttribute = 0u
            dwFlags = STARTF_USESTDHANDLES.toUInt()
            wShowWindow = 0u
            cbReserved2 = 0u
            lpReserved2 = null
            hStdInput = stdin.read
            hStdOutput = stdout.write
            hStdError = stderr.write
        }
        val processInfo = alloc<PROCESS_INFORMATION>().apply {
            hProcess = null
            hThread = null
            dwProcessId = 0u
            dwThreadId = 0u
        }
        checkWindowsSuccess(
            CreateProcessW(
                null,
                windowsStringBuffer(command.windowsCommandLine()),
                null,
                null,
                1,
                CREATE_NO_WINDOW.toUInt(),
                null,
                null,
                startup.ptr,
                processInfo.ptr,
            ),
            "start process",
        )
        val processHandle = requireNotNull(processInfo.hProcess)
        val threadHandle = requireNotNull(processInfo.hThread)
        CloseHandle(threadHandle)
        CloseHandle(stdin.read)
        CloseHandle(stdout.write)
        CloseHandle(stderr.write)
        NativeProcess.StartedProcess(
            process = NativeProcess.WindowsHandle(processHandle),
            input = NativeProcess.WindowsHandle(stdin.write),
            output = NativeProcess.WindowsHandle(stdout.read),
            error = NativeProcess.WindowsHandle(stderr.read),
            pseudoConsole = null,
        )
    } catch (failure: Throwable) {
        stdin.close()
        stdout.close()
        stderr.close()
        throw failure
    }
}

private data class WindowsPipe(
    val read: CPointer<out CPointed>,
    val write: CPointer<out CPointed>,
) {
    fun close() {
        CloseHandle(read)
        CloseHandle(write)
    }
}

private fun createWindowsPipe(security: CPointer<SECURITY_ATTRIBUTES>): WindowsPipe = memScoped {
    val read = alloc<COpaquePointerVar>()
    val write = alloc<COpaquePointerVar>()
    checkWindowsSuccess(CreatePipe(read.ptr, write.ptr, security, 0u), "create process pipe")
    WindowsPipe(requireNotNull(read.value), requireNotNull(write.value))
}

private fun List<String>.windowsCommandLine(): String =
    joinToString(" ") { it.quoteWindowsArgument() }

private fun String.quoteWindowsArgument(): String =
    if (isNotEmpty() && none { it.isWhitespace() || it == '"' }) {
        this
    } else {
        buildString(length + 2) {
            append('"')
            var backslashes = 0
            this@quoteWindowsArgument.forEach { character ->
                when (character) {
                    '\\' -> backslashes++
                    '"' -> {
                        repeat(backslashes * 2 + 1) { append('\\') }
                        append('"')
                        backslashes = 0
                    }
                    else -> {
                        repeat(backslashes) { append('\\') }
                        append(character)
                        backslashes = 0
                    }
                }
            }
            repeat(backslashes * 2) { append('\\') }
            append('"')
        }
    }

private fun kotlinx.cinterop.MemScope.windowsStringBuffer(value: String): CPointer<UShortVar> {
    val buffer = allocArray<UShortVar>(value.length + 1)
    value.forEachIndexed { index, character -> buffer[index] = character.code.toUShort() }
    buffer[value.length] = 0u
    return buffer
}

private fun checkWindowsSuccess(success: Int, operation: String) {
    if (success == 0) error("Failed to $operation: Windows error ${GetLastError()}")
}
