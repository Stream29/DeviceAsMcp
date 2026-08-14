package io.github.stream29.mcp.device.daemon

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.posix.getenv
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.CpuArchitecture
import kotlin.native.OsFamily
import kotlin.native.Platform
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal val systemFileSystem: FileSystem = FileSystem.SYSTEM

@OptIn(ExperimentalForeignApi::class)
internal fun environment(name: String): String? = getenv(name)?.toKString()

internal fun defaultConfigPath(): Path {
    val home = environment("HOME") ?: environment("USERPROFILE") ?: "."
    return "$home/.device-as-mcp/daemon.json".toPath(normalize = true)
}

@OptIn(ExperimentalNativeApi::class)
internal fun platformName(): String = when {
    Platform.osFamily == OsFamily.LINUX -> when (Platform.cpuArchitecture) {
        CpuArchitecture.ARM64 -> "linux-arm64"
        else -> "linux-x64"
    }
    Platform.osFamily == OsFamily.MACOSX -> "macos-arm64"
    Platform.osFamily == OsFamily.WINDOWS -> "windows-x64"
    else -> Platform.osFamily.name.lowercase()
}

@OptIn(ExperimentalNativeApi::class)
internal fun shellCommand(script: String): List<String> {
    return if (Platform.osFamily == OsFamily.WINDOWS) {
        listOf("cmd.exe", "/d", "/s", "/c", script)
    } else {
        listOf("/bin/sh", "-lc", script)
    }
}

internal expect class NativeProcess(
    command: List<String>,
    tty: Boolean,
    stdout: suspend (String) -> Unit,
    stderr: suspend (String) -> Unit,
) {
    suspend fun waitFor(): Int
    suspend fun write(value: ByteArray): Boolean
    suspend fun closeInput()
    fun close()
}

internal expect fun secureLocalPath(path: Path, directory: Boolean)

@OptIn(ExperimentalEncodingApi::class)
internal fun randomId(): String =
    Base64.UrlSafe.encode(kotlin.random.Random.nextBytes(24)).trimEnd('=')
