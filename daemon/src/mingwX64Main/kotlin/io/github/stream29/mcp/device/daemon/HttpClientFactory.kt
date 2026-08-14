package io.github.stream29.mcp.device.daemon

import io.ktor.client.HttpClient
import io.ktor.client.engine.winhttp.WinHttp

internal actual fun createPlatformHttpClient(): HttpClient = configuredHttpClient(WinHttp)
