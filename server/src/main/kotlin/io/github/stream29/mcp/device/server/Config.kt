package io.github.stream29.mcp.device.server

import io.github.stream29.mcp.device.protocol.InstanceId
import java.net.URI
import java.util.UUID

internal enum class DeploymentMode {
    DEVELOPMENT,
    PRODUCTION,
}

internal data class ServerConfig(
    val host: String,
    val port: Int,
    val publicBaseUrl: String,
    val frontendBaseUrl: String,
    val postgresUrl: String?,
    val postgresUser: String,
    val postgresPassword: String,
    val redisUrl: String?,
    val rabbitMqUrl: String?,
    val githubClientId: String?,
    val githubClientSecret: String?,
    val oauthPreRegisteredClientsJson: String? = null,
    val deploymentMode: DeploymentMode = DeploymentMode.DEVELOPMENT,
    val instanceId: InstanceId = InstanceId(UUID.randomUUID().toString()),
) {
    init {
        require(host.isNotBlank()) { "DEVICE_AS_MCP_HOST cannot be blank" }
        require(port in 0..65_535) { "DEVICE_AS_MCP_PORT must be between 0 and 65535" }
        validateBaseUrl(publicBaseUrl, requireSecure = true, name = "DEVICE_AS_MCP_PUBLIC_URL")
        validateBaseUrl(frontendBaseUrl, requireSecure = false, name = "DEVICE_AS_MCP_FRONTEND_URL")
        require((githubClientId == null) == (githubClientSecret == null)) {
            "GITHUB_CLIENT_ID and GITHUB_CLIENT_SECRET must be configured together"
        }
        if (deploymentMode == DeploymentMode.PRODUCTION) {
            require(!postgresUrl.isNullOrBlank()) { "DATABASE_URL is required in production" }
            require(postgresUser.isNotBlank()) { "DATABASE_USER is required in production" }
            require(postgresPassword.isNotBlank()) { "DATABASE_PASSWORD is required in production" }
            require(!redisUrl.isNullOrBlank()) { "REDIS_URL is required in production" }
            require(!rabbitMqUrl.isNullOrBlank()) { "RABBITMQ_URL is required in production" }
            requireHttps(publicBaseUrl, "DEVICE_AS_MCP_PUBLIC_URL")
            requireHttps(frontendBaseUrl, "DEVICE_AS_MCP_FRONTEND_URL")
        }
    }

    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): ServerConfig {
            val deploymentMode = when (env["DEVICE_AS_MCP_ENVIRONMENT"]?.lowercase() ?: "development") {
                "development" -> DeploymentMode.DEVELOPMENT
                "production" -> DeploymentMode.PRODUCTION
                else -> throw IllegalArgumentException(
                    "DEVICE_AS_MCP_ENVIRONMENT must be development or production",
                )
            }
            return ServerConfig(
                host = env["DEVICE_AS_MCP_HOST"] ?: "0.0.0.0",
                port = env["DEVICE_AS_MCP_PORT"]?.toIntOrNull() ?: 8080,
                publicBaseUrl = env["DEVICE_AS_MCP_PUBLIC_URL"] ?: "http://localhost:8080",
                frontendBaseUrl = env["DEVICE_AS_MCP_FRONTEND_URL"] ?: "http://localhost:8081",
                postgresUrl = env["DATABASE_URL"],
                postgresUser = env["DATABASE_USER"] ?: "device_as_mcp",
                postgresPassword = env["DATABASE_PASSWORD"]
                    ?: if (deploymentMode == DeploymentMode.DEVELOPMENT) "device_as_mcp" else "",
                redisUrl = env["REDIS_URL"],
                rabbitMqUrl = env["RABBITMQ_URL"],
                githubClientId = env["GITHUB_CLIENT_ID"]?.takeIf(String::isNotBlank),
                githubClientSecret = env["GITHUB_CLIENT_SECRET"]?.takeIf(String::isNotBlank),
                oauthPreRegisteredClientsJson = env["OAUTH_PRE_REGISTERED_CLIENTS"]?.takeIf(String::isNotBlank),
                deploymentMode = deploymentMode,
            )
        }

        private fun validateBaseUrl(value: String, requireSecure: Boolean, name: String) {
            val uri = runCatching { URI(value) }
                .getOrElse { throw IllegalArgumentException("$name is invalid") }
            val loopback = uri.host.equals("localhost", ignoreCase = true) ||
                uri.host == "127.0.0.1" ||
                uri.host == "::1"
            require(
                uri.isAbsolute &&
                    !uri.host.isNullOrBlank() &&
                    uri.userInfo == null &&
                    uri.query == null &&
                    uri.fragment == null &&
                    (
                        uri.scheme.equals("https", ignoreCase = true) ||
                            (!requireSecure && uri.scheme.equals("http", ignoreCase = true)) ||
                            (loopback && uri.scheme.equals("http", ignoreCase = true))
                        )
            ) {
                "$name must be an HTTP(S) base URL; non-loopback public URLs require HTTPS"
            }
        }

        private fun requireHttps(value: String, name: String) {
            require(URI(value).scheme.equals("https", ignoreCase = true)) {
                "$name must use HTTPS in production"
            }
        }
    }
}
