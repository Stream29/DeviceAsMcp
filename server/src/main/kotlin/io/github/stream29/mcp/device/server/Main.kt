package io.github.stream29.mcp.device.server

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer

fun main() {
    val config = ServerConfig.fromEnvironment()
    embeddedServer(CIO, host = config.host, port = config.port) {
        deviceAsMcpModule(createRuntime(config))
    }.start(wait = true)
}
