package io.github.stream29.mcp.device.daemon

import io.github.stream29.mcp.device.protocol.ProtocolJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.serialization.kotlinx.json.json

internal fun <T : HttpClientEngineConfig> configuredHttpClient(
    engine: HttpClientEngineFactory<T>,
): HttpClient = HttpClient(engine) {
    install(ContentNegotiation) { json(ProtocolJson) }
    install(SSE)
}

internal expect fun createPlatformHttpClient(): HttpClient
