package io.github.stream29.mcp.device.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ConfigTest {
    @Test
    fun developmentDefaultsAllowLocalInMemoryRuntime() {
        val config = ServerConfig.fromEnvironment(emptyMap())

        assertEquals(DeploymentMode.DEVELOPMENT, config.deploymentMode)
        assertEquals("http://localhost:8080", config.publicBaseUrl)
        assertNull(config.postgresUrl)
        assertNull(config.redisUrl)
        assertNull(config.rabbitMqUrl)
    }

    @Test
    fun productionRequiresExternalMiddlewareAndHttps() {
        val complete = productionEnvironment()
        val config = ServerConfig.fromEnvironment(complete)
        assertEquals(DeploymentMode.PRODUCTION, config.deploymentMode)
        assertNull(config.githubClientId)
        assertNull(config.oauthPreRegisteredClientsJson)

        listOf("DATABASE_URL", "DATABASE_PASSWORD", "REDIS_URL", "RABBITMQ_URL").forEach { missing ->
            assertFailsWith<IllegalArgumentException>(missing) {
                ServerConfig.fromEnvironment(complete - missing)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            ServerConfig.fromEnvironment(
                complete + ("DEVICE_AS_MCP_PUBLIC_URL" to "http://localhost:8080"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ServerConfig.fromEnvironment(
                complete + ("DEVICE_AS_MCP_FRONTEND_URL" to "http://localhost:8081"),
            )
        }
    }

    @Test
    fun rejectsUnknownDeploymentMode() {
        assertFailsWith<IllegalArgumentException> {
            ServerConfig.fromEnvironment(mapOf("DEVICE_AS_MCP_ENVIRONMENT" to "staging"))
        }
    }

    private fun productionEnvironment(): Map<String, String> = mapOf(
        "DEVICE_AS_MCP_ENVIRONMENT" to "production",
        "DEVICE_AS_MCP_PUBLIC_URL" to "https://device.example",
        "DEVICE_AS_MCP_FRONTEND_URL" to "https://device.example",
        "DATABASE_URL" to "jdbc:postgresql://postgres/device_as_mcp",
        "DATABASE_USER" to "device_as_mcp",
        "DATABASE_PASSWORD" to "secret",
        "REDIS_URL" to "redis://redis:6379",
        "RABBITMQ_URL" to "amqp://rabbitmq:5672",
        "GITHUB_CLIENT_ID" to "",
        "GITHUB_CLIENT_SECRET" to "",
        "OAUTH_PRE_REGISTERED_CLIENTS" to "",
    )
}
