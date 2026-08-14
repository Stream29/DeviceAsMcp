package io.github.stream29.mcp.device.daemon

import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl

internal actual fun createPlatformHttpClient(): HttpClient = configuredHttpClient(Curl)
