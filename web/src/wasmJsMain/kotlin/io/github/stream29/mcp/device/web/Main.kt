@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.stream29.mcp.device.web

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
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
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
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
    ComposeViewport(document.getElementById(APP_ROOT_ID) as HTMLElement) {
        DeviceAsMcpTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
            ) {
                DevicePanel()
            }
        }
    }
    installComposeWebAccessibilityCompatibility()
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
        window.history.replaceState(
            null,
            document.title,
            window.location.pathname + window.location.search,
        )
    }
}

private fun decodeURIComponent(value: String): String =
    js("decodeURIComponent(value)")

@Composable
private fun DevicePanel() {
    var browserLocation by remember { mutableStateOf(currentBrowserLocation()) }
    DisposableEffect(Unit) {
        val listener: (Event) -> Unit = {
            browserLocation = currentBrowserLocation()
        }
        window.addEventListener("popstate", listener)
        onDispose {
            window.removeEventListener("popstate", listener)
        }
    }

    fun navigate(route: AppRoute, replace: Boolean = false) {
        if (
            normalizedPath(browserLocation.path) == route.path &&
            browserLocation.search.isEmpty()
        ) {
            return
        }
        if (replace) {
            window.history.replaceState(null, route.title, route.path)
        } else {
            window.history.pushState(null, route.title, route.path)
        }
        browserLocation = currentBrowserLocation()
    }

    var accessToken by remember { mutableStateOf(window.localStorage.getItem(TOKEN_KEY)) }
    val authorizeTarget = queryValue("authorize", browserLocation.search)
        ?: window.sessionStorage.getItem(AUTHORIZE_KEY)
    val route = appRouteForPath(browserLocation.path)
    var userName by remember { mutableStateOf("") }
    var devices by remember { mutableStateOf(emptyList<DeviceSummary>()) }
    var authKeys by remember { mutableStateOf(emptyList<AuthKeySummary>()) }
    var createdKey by remember { mutableStateOf<CreatedAuthKey?>(null) }
    var generatedCommand by remember { mutableStateOf<GeneratedInstallCommand?>(null) }
    var selectedInstallPlatform by remember { mutableStateOf(InstallPlatform.LINUX_X64) }
    var loading by remember { mutableStateOf(false) }
    var authenticating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    fun showMessage(message: String) {
        launchUi {
            snackbarHostState.showSnackbar(
                message = message,
                withDismissAction = true,
            )
        }
    }

    fun forgetSession() {
        window.localStorage.removeItem(TOKEN_KEY)
        window.sessionStorage.removeItem(AUTHORIZE_KEY)
        accessToken = null
        userName = ""
        devices = emptyList()
        authKeys = emptyList()
        createdKey = null
        generatedCommand = null
    }

    suspend fun refresh(token: String, targetRoute: AppRoute) {
        loading = true
        error = null
        runCatching {
            userName = apiGet<AuthenticatedUser>("/api/me", token).username
            when (targetRoute) {
                AppRoute.DEVICES -> devices = apiGet("/api/devices", token)
                AppRoute.AUTH_KEYS -> authKeys = apiGet("/api/auth-keys", token)
                AppRoute.LOGIN -> Unit
            }
        }.onFailure {
            if (it is UnauthorizedException) {
                forgetSession()
            }
            error = it.message
        }
        loading = false
    }

    LaunchedEffect(accessToken, browserLocation, authorizeTarget) {
        val token = accessToken
        if (
            token != null &&
            authorizeTarget != null &&
            authorizeTarget.startsWith("${serverUrl()}/oauth/authorize?")
        ) {
            window.sessionStorage.removeItem(AUTHORIZE_KEY)
            window.location.href = authorizeTarget
            return@LaunchedEffect
        }

        val canonicalRoute = canonicalAppRoute(
            path = browserLocation.path,
            authenticated = token != null,
        )
        if (
            canonicalRoute != null &&
            (
                canonicalRoute != route ||
                    normalizedPath(browserLocation.path) != canonicalRoute.path
                )
        ) {
            navigate(canonicalRoute, replace = true)
            return@LaunchedEffect
        }

        document.title = route?.title ?: NOT_FOUND_TITLE
        if (token != null) {
            route
                ?.takeIf(AppRoute::requiresAuthentication)
                ?.let { refresh(token, it) }
        }
    }

    when {
        route == null && browserLocation.path != "/" -> {
            NotFoundScreen(
                onHome = {
                    navigate(
                        if (accessToken == null) AppRoute.LOGIN else AppRoute.DEVICES,
                        replace = true,
                    )
                },
            )
        }

        accessToken == null -> {
            LoginScreen(
                authorizeTarget = authorizeTarget,
                busy = authenticating,
                error = error,
                onSubmit = { mode, username, password ->
                    authenticating = true
                    error = null
                    launchUi {
                        runCatching {
                            when (mode) {
                                AuthMode.SIGN_IN -> publicPost<AuthSession>(
                                    "/api/auth/login",
                                    PasswordLoginRequest(username, password),
                                )

                                AuthMode.REGISTER -> publicPost<AuthSession>(
                                    "/api/auth/register",
                                    RegisterRequest(username, password),
                                )
                            }
                        }.onSuccess { session ->
                            window.localStorage.setItem(TOKEN_KEY, session.accessToken)
                            userName = session.user.username
                            accessToken = session.accessToken
                        }.onFailure {
                            error = it.message ?: if (mode == AuthMode.SIGN_IN) {
                                "Sign in failed"
                            } else {
                                "Account creation failed"
                            }
                        }
                        authenticating = false
                    }
                },
                onGitHub = {
                    authorizeTarget?.let {
                        window.sessionStorage.setItem(AUTHORIZE_KEY, it)
                    }
                    window.location.href = "${serverUrl()}/api/auth/github/start"
                },
                onClearError = { error = null },
            )
        }

        route?.requiresAuthentication == true -> {
            ManagementShell(
                route = route,
                userName = userName,
                loading = loading,
                error = error,
                snackbarHostState = snackbarHostState,
                onNavigate = { target ->
                    error = null
                    if (target != AppRoute.AUTH_KEYS) createdKey = null
                    navigate(target)
                },
                onSignOut = {
                    val token = accessToken ?: return@ManagementShell
                    loading = true
                    error = null
                    launchUi {
                        runCatching { apiPostNoContent("/api/auth/logout", token) }
                            .onSuccess {
                                forgetSession()
                                navigate(AppRoute.LOGIN, replace = true)
                            }
                            .onFailure { error = it.message ?: "Sign out failed" }
                        loading = false
                    }
                },
                onDismissError = { error = null },
            ) { displayedRoute, modifier ->
                when (displayedRoute) {
                    AppRoute.DEVICES -> {
                        DevicesScreen(
                            devices = devices,
                            selectedPlatform = selectedInstallPlatform,
                            generatedCommand = generatedCommand,
                            busy = loading,
                            onPlatformSelected = { platform ->
                                selectedInstallPlatform = platform
                                generatedCommand = null
                            },
                            onGenerateCommand = {
                                val token = accessToken ?: return@DevicesScreen
                                val platform = selectedInstallPlatform
                                loading = true
                                error = null
                                launchUi {
                                    runCatching {
                                        val enrollment = apiPost<DeviceEnrollmentToken>(
                                            "/api/enrollment-token",
                                            token,
                                            Unit,
                                        )
                                        GeneratedInstallCommand(
                                            command = platform.installCommand(
                                                serverUrl(),
                                                enrollment.token,
                                            ),
                                            expiresAtEpochMillis = enrollment.expiresAtEpochMillis,
                                        )
                                    }.onSuccess { command ->
                                        generatedCommand = command
                                        showMessage("Install command ready")
                                    }.onFailure {
                                        error = it.message ?: "Command generation failed"
                                    }
                                    loading = false
                                }
                            },
                            onRename = { device, name ->
                                val token = accessToken ?: return@DevicesScreen
                                loading = true
                                error = null
                                launchUi {
                                    runCatching {
                                        apiPutNoContent(
                                            "/api/devices/${device.id.value}",
                                            token,
                                            RenameDeviceRequest(name),
                                        )
                                        refresh(token, AppRoute.DEVICES)
                                    }.onSuccess {
                                        showMessage("Device renamed")
                                    }.onFailure {
                                        error = it.message ?: "Device rename failed"
                                    }
                                    loading = false
                                }
                            },
                            onRevoke = { device ->
                                val token = accessToken ?: return@DevicesScreen
                                loading = true
                                error = null
                                launchUi {
                                    runCatching {
                                        apiDelete("/api/devices/${device.id.value}", token)
                                        refresh(token, AppRoute.DEVICES)
                                    }.onSuccess {
                                        showMessage("Device revoked")
                                    }.onFailure {
                                        error = it.message ?: "Device revocation failed"
                                    }
                                    loading = false
                                }
                            },
                            onCopyCommand = { command ->
                                copyText(command)
                                showMessage("Install command copied")
                            },
                            modifier = modifier,
                        )
                    }

                    AppRoute.AUTH_KEYS -> {
                        ConnectScreen(
                            endpoint = "${serverUrl()}/mcp",
                            authKeys = authKeys,
                            createdKey = createdKey,
                            busy = loading,
                            onCreateKey = { name ->
                                val token = accessToken ?: return@ConnectScreen
                                loading = true
                                error = null
                                launchUi {
                                    runCatching {
                                        apiPost<CreatedAuthKey>(
                                            "/api/auth-keys",
                                            token,
                                            CreateAuthKeyRequest(name),
                                        )
                                    }.onSuccess { key ->
                                        createdKey = key
                                        refresh(token, AppRoute.AUTH_KEYS)
                                        showMessage("Access key created")
                                    }.onFailure {
                                        error = it.message ?: "Access key creation failed"
                                    }
                                    loading = false
                                }
                            },
                            onRevokeKey = { key ->
                                val token = accessToken ?: return@ConnectScreen
                                loading = true
                                error = null
                                launchUi {
                                    runCatching {
                                        apiDelete("/api/auth-keys/${key.id}", token)
                                        if (createdKey?.id == key.id) createdKey = null
                                        refresh(token, AppRoute.AUTH_KEYS)
                                    }.onSuccess {
                                        showMessage("Access key revoked")
                                    }.onFailure {
                                        error = it.message ?: "Access key revocation failed"
                                    }
                                    loading = false
                                }
                            },
                            onCopyEndpoint = {
                                copyText("${serverUrl()}/mcp")
                                showMessage("MCP endpoint copied")
                            },
                            onCopyKey = { key ->
                                copyText(key)
                                showMessage("Access key copied")
                            },
                            onCopyCodexConfig = { key ->
                                copyText(
                                    codexMcpConfiguration(
                                        endpoint = "${serverUrl()}/mcp",
                                        token = key.token,
                                    ),
                                )
                                showMessage("Codex configuration copied")
                            },
                            modifier = modifier,
                        )
                    }

                    AppRoute.LOGIN -> Unit
                }
            }
        }

        else -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
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

private fun queryValue(name: String, search: String): String? = search
    .removePrefix("?")
    .split('&')
    .mapNotNull { part ->
        val separator = part.indexOf('=')
        if (separator < 0) null else part.take(separator) to part.drop(separator + 1)
    }
    .firstOrNull { it.first == name }
    ?.second
    ?.let(::decodeURIComponent)

private fun currentBrowserLocation(): BrowserLocation = BrowserLocation(
    path = window.location.pathname,
    search = window.location.search,
)

private fun normalizedPath(path: String): String = when {
    path.isBlank() -> "/"
    path == "/" -> path
    else -> path.trimEnd('/')
}

private fun launchUi(block: suspend () -> Unit) {
    uiScope.launch { block() }
}

private fun copyText(value: String) {
    window.navigator.clipboard.writeText(value)
}

internal data class GeneratedInstallCommand(
    val command: String,
    val expiresAtEpochMillis: Long,
)

internal enum class InstallPlatform(
    val selectorLabel: String,
    val releasePlatform: String,
    val windows: Boolean = false,
) {
    LINUX_X64("Linux x64", "linux-x64"),
    LINUX_ARM64("Linux ARM", "linux-arm64"),
    MACOS_ARM64("macOS", "macos-arm64"),
    WINDOWS_X64("Windows", "windows-x64", windows = true),
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

private fun codexMcpConfiguration(endpoint: String, token: String): String {
    fun tomlString(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

    return """
        [mcp_servers.device_as_mcp]
        url = "${tomlString(endpoint)}"
        enabled = true

        [mcp_servers.device_as_mcp.http_headers]
        Authorization = "Bearer ${tomlString(token)}"
    """.trimIndent()
}

private class UnauthorizedException : IllegalStateException("Session expired")

private data class BrowserLocation(
    val path: String,
    val search: String,
)

private const val RELEASE_DOWNLOAD_BASE =
    "https://github.com/Stream29/DeviceAsMcp/releases/latest/download"
private const val POSIX_INSTALLER_URL = "$RELEASE_DOWNLOAD_BASE/install-device-as-mcp.sh"
private const val WINDOWS_INSTALLER_URL = "$RELEASE_DOWNLOAD_BASE/install-device-as-mcp.ps1"
private const val TOKEN_KEY = "device-as-mcp.session"
private const val AUTHORIZE_KEY = "device-as-mcp.authorize"
private const val APP_ROOT_ID = "app"
private const val MAX_ERROR_DETAIL_LENGTH = 512
internal const val MAX_DEVICE_NAME_LENGTH = 100
internal const val MAX_ACCESS_KEY_NAME_LENGTH = 100
private const val NOT_FOUND_TITLE = "Page not found · DeviceAsMcp"
