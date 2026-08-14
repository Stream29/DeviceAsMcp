package io.github.stream29.mcp.device.daemon

import io.github.stream29.mcp.device.protocol.ProtocolJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) = runBlocking {
    val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(ProtocolJson) }
        install(SSE)
    }
    val store = ConfigStore()
    try {
        val config = when (args.firstOrNull()) {
            "--help", "-h", "help" -> {
                println(USAGE)
                return@runBlocking
            }
            "enroll" -> {
                val server = option(args, "--server") ?: "http://localhost:8080"
                val token = option(args, "--token") ?: browserEnrollmentToken(client, server)
                val name = option(args, "--name") ?: platformName()
                enroll(client, server, token, name).also(store::save)
            }
            "run", null -> store.load() ?: error("Not enrolled. Run: device-as-mcp enroll --token <token>")
            else -> error(USAGE)
        }
        println("connecting ${config.deviceName} (${config.credential.deviceId.value}) to ${config.serverUrl}")
        DeviceDaemon(config, client, this).run()
    } finally {
        client.close()
    }
}

private const val USAGE =
    "Usage: device-as-mcp [enroll --server URL [--token TOKEN] [--name NAME] | run]"

private fun option(args: Array<String>, name: String): String? {
    val index = args.indexOf(name)
    return if (index >= 0) args.getOrNull(index + 1) else null
}
