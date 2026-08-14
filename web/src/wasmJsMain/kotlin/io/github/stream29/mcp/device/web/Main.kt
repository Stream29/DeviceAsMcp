@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.stream29.mcp.device.web

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import io.github.stream29.mcp.device.protocol.AuthKeySummary
import io.github.stream29.mcp.device.protocol.AuthSession
import io.github.stream29.mcp.device.protocol.AuthenticatedUser
import io.github.stream29.mcp.device.protocol.CreateAuthKeyRequest
import io.github.stream29.mcp.device.protocol.CreatedAuthKey
import io.github.stream29.mcp.device.protocol.DeviceEnrollmentToken
import io.github.stream29.mcp.device.protocol.DeviceSummary
import io.github.stream29.mcp.device.protocol.PasswordLoginRequest
import io.github.stream29.mcp.device.protocol.ProtocolJson
import io.github.stream29.mcp.device.protocol.RegisterRequest
import io.github.stream29.mcp.device.protocol.RenameDeviceRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlin.js.toJsString

private val client = HttpClient(Js) {
    engine {
        configureRequest {
            credentials = "include".toJsString()
        }
    }
    install(ContentNegotiation) { json(ProtocolJson) }
}
private val uiScope = kotlinx.coroutines.MainScope()

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    importOAuthSession()
    ComposeViewport(document.body!!) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                DevicePanel()
            }
        }
    }
}

private fun importOAuthSession() {
    val fragment = window.location.hash.removePrefix("#")
    val session = fragment
        .split('&')
        .mapNotNull { part ->
            val separator = part.indexOf('=')
            if (separator < 0) null else part.take(separator) to part.drop(separator + 1)
        }
        .firstOrNull { it.first == "session" }
        ?.second
        ?.let(::decodeURIComponent)
    if (!session.isNullOrBlank()) {
        window.localStorage.setItem(TOKEN_KEY, session)
        window.history.replaceState(null, document.title, window.location.pathname)
    }
}

private fun decodeURIComponent(value: String): String =
    js("decodeURIComponent(value)")

@Composable
private fun DevicePanel() {
    var accessToken by remember { mutableStateOf(window.localStorage.getItem(TOKEN_KEY)) }
    val authorizeTarget = remember {
        queryValue("authorize") ?: window.sessionStorage.getItem(AUTHORIZE_KEY)
    }
    var userName by remember { mutableStateOf("") }
    var devices by remember { mutableStateOf(emptyList<DeviceSummary>()) }
    var authKeys by remember { mutableStateOf(emptyList<AuthKeySummary>()) }
    var createdKey by remember { mutableStateOf<String?>(null) }
    var installCommand by remember { mutableStateOf<String?>(null) }
    var selectedInstallPlatform by remember { mutableStateOf(InstallPlatform.LINUX_X64) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun refresh(token: String) {
        loading = true
        error = null
        runCatching {
            userName = apiGet<AuthenticatedUser>("/api/me", token).username
            devices = apiGet("/api/devices", token)
            authKeys = apiGet("/api/auth-keys", token)
        }.onFailure {
            if (it is UnauthorizedException) {
                window.localStorage.removeItem(TOKEN_KEY)
                accessToken = null
            }
            error = it.message
        }
        loading = false
    }

    LaunchedEffect(accessToken) {
        accessToken?.let {
            if (
                authorizeTarget != null &&
                authorizeTarget.startsWith("${serverUrl()}/oauth/authorize?")
            ) {
                window.sessionStorage.removeItem(AUTHORIZE_KEY)
                window.location.href = authorizeTarget
            } else {
                refresh(it)
            }
        }
    }

    Box(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        if (accessToken == null) {
            LoginCard(
                authorizeTarget = authorizeTarget,
                onAuthenticated = { session ->
                    window.localStorage.setItem(TOKEN_KEY, session.accessToken)
                    error = null
                    accessToken = session.accessToken
                },
                onError = { error = it },
            )
        } else {
            Column(
                Modifier.fillMaxWidth().widthIn(max = 960.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("DeviceAsMcp", style = MaterialTheme.typography.headlineMedium)
                        Text("Signed in as $userName", color = Color.LightGray)
                    }
                    if (loading) CircularProgressIndicator(Modifier.width(32.dp))
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(enabled = !loading, onClick = {
                        val token = accessToken ?: return@OutlinedButton
                        loading = true
                        launchUi {
                            runCatching { apiPostNoContent("/api/auth/logout", token) }
                                .onSuccess {
                                    window.localStorage.removeItem(TOKEN_KEY)
                                    accessToken = null
                                    error = null
                                }
                                .onFailure { error = it.message ?: "Sign out failed" }
                            loading = false
                        }
                    }) { Text("Sign out") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                DashboardCard("Devices") {
                    if (devices.isEmpty()) Text("No enrolled devices")
                    devices.forEach { device ->
                        DeviceItem(
                            device = device,
                            enabled = !loading,
                            onRename = { name ->
                                val token = accessToken ?: return@DeviceItem
                                loading = true
                                launchUi {
                                    runCatching {
                                        apiPutNoContent(
                                            "/api/devices/${device.id.value}",
                                            token,
                                            RenameDeviceRequest(name),
                                        )
                                        refresh(token)
                                    }.onFailure {
                                        error = it.message ?: "Device rename failed"
                                    }
                                    loading = false
                                }
                            },
                        )
                    }
                    Text(
                        "The command uses this panel's server automatically. " +
                            "The device chooses its initial name; rename it here after enrollment.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("Install platform")
                    InstallPlatform.entries.forEach { platform ->
                        if (platform == selectedInstallPlatform) {
                            Button(
                                onClick = {},
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(platform.displayName)
                            }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    selectedInstallPlatform = platform
                                    installCommand = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(platform.displayName)
                            }
                        }
                    }
                    Button(onClick = {
                        val token = accessToken ?: return@Button
                        val platform = selectedInstallPlatform
                        launchUi {
                            runCatching {
                                val enrollment = apiPost<DeviceEnrollmentToken>(
                                    "/api/enrollment-token",
                                    token,
                                    Unit,
                                )
                                installCommand = platform.installCommand(
                                    serverUrl(),
                                    enrollment.token,
                                )
                            }.onSuccess {
                                error = null
                            }.onFailure { error = it.message }
                        }
                    }) {
                        Text("Generate ${selectedInstallPlatform.displayName} command")
                    }
                    installCommand?.let {
                        Text(
                            "This command contains a single-use token that expires in 10 minutes. " +
                                "It downloads and verifies the native daemon from GitHub.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(it, style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { copyText(it) }) { Text("Copy") }
                    }
                }
                DashboardCard("MCP auth keys") {
                    authKeys.forEach { key ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(key.name)
                            OutlinedButton(onClick = {
                                val token = accessToken ?: return@OutlinedButton
                                launchUi {
                                    runCatching {
                                        apiDelete("/api/auth-keys/${key.id}", token)
                                        refresh(token)
                                    }.onFailure { error = it.message ?: "Key revocation failed" }
                                }
                            }) { Text("Revoke") }
                        }
                    }
                    Button(onClick = {
                        val token = accessToken ?: return@Button
                        launchUi {
                            runCatching {
                                val key = apiPost<CreatedAuthKey>(
                                    "/api/auth-keys",
                                    token,
                                    CreateAuthKeyRequest("Remote MCP"),
                                )
                                createdKey = key.token
                                refresh(token)
                            }.onFailure { error = it.message }
                        }
                    }) { Text("Create auth key") }
                    createdKey?.let {
                        Text("Shown once: $it", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { copyText(it) }) { Text("Copy") }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceItem(
    device: DeviceSummary,
    enabled: Boolean,
    onRename: (String) -> Unit,
) {
    var name by remember(device.id, device.name) { mutableStateOf(device.name) }
    val normalized = name.trim()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(device.platform)
            Text(if (device.online) "online" else "offline")
        }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Device name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            enabled = enabled &&
                normalized.isNotEmpty() &&
                normalized.length <= MAX_DEVICE_NAME_LENGTH &&
                normalized != device.name,
            onClick = { onRename(normalized) },
        ) {
            Text("Rename")
        }
    }
}

@Composable
private fun LoginCard(
    authorizeTarget: String?,
    onAuthenticated: (AuthSession) -> Unit,
    onError: (String) -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xff172033)),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("DeviceAsMcp", style = MaterialTheme.typography.headlineMedium)
            Text("Manage devices and remote MCP access.")
            OutlinedTextField(username, { username = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                password,
                { password = it },
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(enabled = !busy, onClick = {
                    busy = true
                    launchUi {
                        runCatching {
                            publicPost<AuthSession>("/api/auth/login", PasswordLoginRequest(username, password))
                        }.onSuccess(onAuthenticated).onFailure { onError(it.message ?: "Login failed") }
                        busy = false
                    }
                }) { Text("Sign in") }
                OutlinedButton(enabled = !busy, onClick = {
                    busy = true
                    launchUi {
                        runCatching {
                            publicPost<AuthSession>("/api/auth/register", RegisterRequest(username, password))
                        }.onSuccess(onAuthenticated).onFailure { onError(it.message ?: "Registration failed") }
                        busy = false
                    }
                }) { Text("Register") }
                OutlinedButton(onClick = {
                    authorizeTarget?.let {
                        window.sessionStorage.setItem(AUTHORIZE_KEY, it)
                    }
                    window.location.href = "${serverUrl()}/api/auth/github/start"
                }) { Text("GitHub") }
            }
        }
    }
}

@Composable
private fun DashboardCard(
    title: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

private suspend inline fun <reified T> publicPost(path: String, body: Any): T {
    val response = client.post("${serverUrl()}$path") {
        contentType(ContentType.Application.Json)
        when (body) {
            is PasswordLoginRequest -> setBody(ProtocolJson.encodeToString(body))
            is RegisterRequest -> setBody(ProtocolJson.encodeToString(body))
            else -> error("Unsupported public request")
        }
    }
    response.requireApiSuccess()
    return response.body()
}

private suspend inline fun <reified T> apiGet(path: String, token: String): T {
    val response = client.get("${serverUrl()}$path") { bearerAuth(token) }
    response.requireApiSuccess(authenticated = true)
    return response.body()
}

private suspend inline fun <reified T> apiPost(path: String, token: String, body: Any): T {
    val response = client.post("${serverUrl()}$path") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        when (body) {
            is Unit -> Unit
            is CreateAuthKeyRequest -> setBody(ProtocolJson.encodeToString(body))
            else -> error("Unsupported API request")
        }
    }
    response.requireApiSuccess(authenticated = true)
    return response.body()
}

private suspend fun apiPostNoContent(path: String, token: String) {
    val response = client.post("${serverUrl()}$path") {
        bearerAuth(token)
    }
    response.requireApiSuccess(authenticated = true)
}

private suspend fun apiPutNoContent(
    path: String,
    token: String,
    body: RenameDeviceRequest,
) {
    val response = client.put("${serverUrl()}$path") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(ProtocolJson.encodeToString(body))
    }
    response.requireApiSuccess(authenticated = true)
}

private suspend fun apiDelete(path: String, token: String) {
    val response = client.delete("${serverUrl()}$path") { bearerAuth(token) }
    response.requireApiSuccess(authenticated = true)
}

private suspend fun HttpResponse.requireApiSuccess(authenticated: Boolean = false) {
    if (authenticated && status.value == 401) throw UnauthorizedException()
    if (status.isSuccess()) return
    val detail = runCatching { bodyAsText().trim().take(MAX_ERROR_DETAIL_LENGTH) }
        .getOrDefault("")
    error(
        buildString {
            append("HTTP ")
            append(status.value)
            if (detail.isNotBlank()) {
                append(": ")
                append(detail)
            }
        },
    )
}

private fun serverUrl(): String =
    document.querySelector("meta[name='device-as-mcp-server']")
        ?.getAttribute("content")
        ?.trim()
        ?.trimEnd('/')
        ?.takeIf(String::isNotBlank)
    ?: defaultServerUrl()

private fun defaultServerUrl(): String {
    val location = window.location
    val loopback = location.hostname == "localhost" ||
        location.hostname == "127.0.0.1" ||
        location.hostname == "::1" ||
        location.hostname == "[::1]"
    return if (loopback && location.port in setOf("8081", "3000")) {
        "${location.protocol}//${location.hostname}:8080"
    } else {
        location.origin
    }
}

private fun queryValue(name: String): String? = window.location.search
    .removePrefix("?")
    .split('&')
    .mapNotNull { part ->
        val separator = part.indexOf('=')
        if (separator < 0) null else part.take(separator) to part.drop(separator + 1)
    }
    .firstOrNull { it.first == name }
    ?.second
    ?.let(::decodeURIComponent)

private fun launchUi(block: suspend () -> Unit) {
    uiScope.launch { block() }
}

private fun copyText(value: String) {
    window.navigator.clipboard.writeText(value)
}

private enum class InstallPlatform(
    val displayName: String,
    val releasePlatform: String,
    val windows: Boolean = false,
) {
    LINUX_X64("Linux x64", "linux-x64"),
    LINUX_ARM64("Linux ARM64", "linux-arm64"),
    MACOS_ARM64("macOS Apple Silicon", "macos-arm64"),
    WINDOWS_X64("Windows x64 (PowerShell)", "windows-x64", windows = true),
    ;

    fun installCommand(serverUrl: String, token: String): String {
        return if (windows) {
            windowsInstallCommand(serverUrl, token, releasePlatform)
        } else {
            unixInstallCommand(serverUrl, token, releasePlatform)
        }
    }
}

private fun unixInstallCommand(
    serverUrl: String,
    token: String,
    platform: String,
): String =
    "curl --proto '=https' --tlsv1.2 -fsSL ${shellQuote(POSIX_INSTALLER_URL)} | " +
        "sh -s -- --server ${shellQuote(serverUrl)} --token ${shellQuote(token)} " +
        "--platform ${shellQuote(platform)}"

private fun windowsInstallCommand(
    serverUrl: String,
    token: String,
    platform: String,
): String {
    val variable = "${'$'}p"
    return "$variable=Join-Path ${'$'}env:TEMP 'install-device-as-mcp.ps1'; " +
        "Invoke-WebRequest -UseBasicParsing ${powerShellQuote(WINDOWS_INSTALLER_URL)} -OutFile $variable; " +
        "try { & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $variable " +
        "-ServerUrl ${powerShellQuote(serverUrl)} -Token ${powerShellQuote(token)} " +
        "-Platform ${powerShellQuote(platform)} } " +
        "finally { Remove-Item $variable -Force -ErrorAction SilentlyContinue }"
}

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

private fun powerShellQuote(value: String): String = "'" + value.replace("'", "''") + "'"

private class UnauthorizedException : IllegalStateException("Session expired")

private const val RELEASE_DOWNLOAD_BASE =
    "https://github.com/Stream29/DeviceAsMcp/releases/latest/download"
private const val POSIX_INSTALLER_URL = "$RELEASE_DOWNLOAD_BASE/install-device-as-mcp.sh"
private const val WINDOWS_INSTALLER_URL = "$RELEASE_DOWNLOAD_BASE/install-device-as-mcp.ps1"
private const val TOKEN_KEY = "device-as-mcp.session"
private const val AUTHORIZE_KEY = "device-as-mcp.authorize"
private const val MAX_ERROR_DETAIL_LENGTH = 512
private const val MAX_DEVICE_NAME_LENGTH = 100
