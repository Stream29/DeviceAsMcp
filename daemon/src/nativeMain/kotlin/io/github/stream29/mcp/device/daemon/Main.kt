package io.github.stream29.mcp.device.daemon

import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "--help", "-h", "help" -> {
            println(USAGE)
            return
        }
        "enroll", "run", null -> Unit
        else -> error(USAGE)
    }

    runBlocking {
        val client = createPlatformHttpClient()
        val store = ConfigStore()
        try {
            val config = when (args.firstOrNull()) {
                "enroll" -> {
                    val server = option(args, "--server") ?: "http://localhost:8080"
                    val token = option(args, "--token") ?: browserEnrollmentToken(client, server)
                    val name = option(args, "--name") ?: defaultDeviceName()
                    enroll(client, server, token, name).also { enrolled ->
                        store.save(enrolled)
                        if ("--no-run" in args) {
                            println("enrolled ${enrolled.deviceName} (${enrolled.credential.deviceId.value})")
                            return@runBlocking
                        }
                    }
                }
                else -> store.load() ?: error("Not enrolled. Run: device-as-mcp enroll --token <token>")
            }
            println("connecting ${config.deviceName} (${config.credential.deviceId.value}) to ${config.serverUrl}")
            DeviceDaemon(config, client, this).run()
        } finally {
            client.close()
        }
    }
}

private const val USAGE =
    "Usage: device-as-mcp [enroll --server URL [--token TOKEN] [--name NAME] [--no-run] | run]"

private fun option(args: Array<String>, name: String): String? {
    val index = args.indexOf(name)
    return if (index >= 0) args.getOrNull(index + 1) else null
}
