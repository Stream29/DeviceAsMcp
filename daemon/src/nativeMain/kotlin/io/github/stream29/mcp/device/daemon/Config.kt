package io.github.stream29.mcp.device.daemon

import io.github.stream29.mcp.device.protocol.DaemonEnrollmentRequest
import io.github.stream29.mcp.device.protocol.DeviceCredential
import io.github.stream29.mcp.device.protocol.ProtocolJson
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okio.Path

@Serializable
internal data class DaemonConfig(
    val serverUrl: String,
    val credential: DeviceCredential,
    val deviceName: String,
    val platform: String,
)

internal class ConfigStore(private val path: Path = defaultConfigPath()) {
    fun load(): DaemonConfig? {
        if (!systemFileSystem.exists(path)) return null
        path.parent?.let { secureLocalPath(it, directory = true) }
        secureLocalPath(path, directory = false)
        return ProtocolJson.decodeFromString(systemFileSystem.read(path) { readUtf8() })
    }

    fun save(config: DaemonConfig) {
        path.parent?.let {
            systemFileSystem.createDirectories(it)
            secureLocalPath(it, directory = true)
        }
        val temporary = path.parent?.let { it / ".${path.name}.${randomId()}.tmp" }
            ?: error("Configuration path has no parent")
        try {
            systemFileSystem.write(temporary) { writeUtf8(ProtocolJson.encodeToString(config)) }
            secureLocalPath(temporary, directory = false)
            systemFileSystem.atomicMove(temporary, path)
            secureLocalPath(path, directory = false)
        } finally {
            if (systemFileSystem.exists(temporary)) runCatching { systemFileSystem.delete(temporary) }
        }
    }
}

internal suspend fun enroll(
    client: HttpClient,
    serverUrl: String,
    token: String,
    name: String,
): DaemonConfig {
    val normalizedServerUrl = normalizeServerUrl(serverUrl)
    val platform = platformName()
    val normalizedName = name.trim().take(MAX_DEVICE_NAME_LENGTH).ifBlank { platform }
    val response = client.post("$normalizedServerUrl/daemon/enroll") {
        contentType(ContentType.Application.Json)
        setBody(DaemonEnrollmentRequest(token, normalizedName, platform))
    }
    check(response.status.isSuccess()) { "Enrollment failed: HTTP ${response.status.value}" }
    return DaemonConfig(normalizedServerUrl, response.body(), normalizedName, platform)
}

@Serializable
private data class DaemonBrowserLoginStart(
    val requestId: String,
    val verificationUri: String,
    val expiresInSeconds: Long,
    val intervalSeconds: Long,
)

@Serializable
private data class DaemonBrowserLoginResult(
    val enrollment: io.github.stream29.mcp.device.protocol.DeviceEnrollmentToken,
)

internal suspend fun browserEnrollmentToken(
    client: HttpClient,
    serverUrl: String,
): String {
    val normalized = normalizeServerUrl(serverUrl)
    val started = client.post("$normalized/daemon/browser-login").body<DaemonBrowserLoginStart>()
    println("Open this URL in a browser and approve the daemon:")
    println(started.verificationUri)
    val deadline = kotlin.time.Clock.System.now().toEpochMilliseconds() + started.expiresInSeconds * 1_000
    while (kotlin.time.Clock.System.now().toEpochMilliseconds() < deadline) {
        kotlinx.coroutines.delay(started.intervalSeconds * 1_000)
        val response = client.get("$normalized/daemon/browser-login/${started.requestId}")
        when (response.status.value) {
            202 -> Unit
            in 200..299 -> return response.body<DaemonBrowserLoginResult>().enrollment.token
            else -> error("Browser enrollment failed: HTTP ${response.status.value}")
        }
    }
    error("Browser enrollment timed out")
}

private fun normalizeServerUrl(value: String): String {
    val parsed = runCatching { Url(value.trim()) }
        .getOrElse { throw IllegalArgumentException("The server URL is invalid") }
    val loopback = parsed.host.equals("localhost", ignoreCase = true) ||
        parsed.host == "127.0.0.1" ||
        parsed.host == "::1"
    require(parsed.protocol == URLProtocol.HTTPS || (parsed.protocol == URLProtocol.HTTP && loopback)) {
        "The server URL must use HTTPS except for loopback development"
    }
    require(parsed.fragment.isEmpty() && parsed.parameters.isEmpty()) {
        "The server URL cannot contain a query or fragment"
    }
    return value.trim().trimEnd('/')
}

private const val MAX_DEVICE_NAME_LENGTH = 100
