package io.github.stream29.mcp.device.server

import io.github.stream29.mcp.device.protocol.AuthSession
import io.github.stream29.mcp.device.protocol.CreateAuthKeyRequest
import io.github.stream29.mcp.device.protocol.CreatedAuthKey
import io.github.stream29.mcp.device.protocol.InstanceId
import io.github.stream29.mcp.device.protocol.ProtocolJson
import io.github.stream29.mcp.device.protocol.RegisterRequest
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModernMcpTest {
    @Test
    fun authKeyCanDiscoverAndListTools() = testApplication {
        application { deviceAsMcpModule(testRuntime()) }
        val jsonClient = createClient { install(ContentNegotiation) { json(ProtocolJson) } }
        val session = jsonClient.post("/api/auth/register") {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(RegisterRequest("mcp-user", "long enough password"))
        }.body<AuthSession>()
        val key = jsonClient.post("/api/auth-keys") {
            header(HttpHeaders.Authorization, "Bearer ${session.accessToken}")
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(CreateAuthKeyRequest("test"))
        }.body<CreatedAuthKey>()

        val response = jsonClient.post("/mcp") {
            header(HttpHeaders.Authorization, "Bearer ${key.token}")
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            header("MCP-Protocol-Version", MODERN_MCP_VERSION)
            header("Mcp-Method", "tools/list")
            setBody(
                """
                {
                  "jsonrpc":"2.0",
                  "id":1,
                  "method":"tools/list",
                  "params":{
                    "_meta":{
                      "io.modelcontextprotocol/protocolVersion":"$MODERN_MCP_VERSION",
                      "io.modelcontextprotocol/clientCapabilities":{}
                    }
                  }
                }
                """.trimIndent(),
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = ProtocolJson.parseToJsonElement(response.body<String>()).jsonObject
        assertTrue(body["result"].toString().contains("launch_terminal_session"))
        assertTrue(body["result"].toString().contains("update_device_description"))
    }

    @Test
    fun managementSessionIsNotAnMcpToken() = testApplication {
        application { deviceAsMcpModule(testRuntime()) }
        val jsonClient = createClient { install(ContentNegotiation) { json(ProtocolJson) } }
        val session = jsonClient.post("/api/auth/register") {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(RegisterRequest("panel-user", "long enough password"))
        }.body<AuthSession>()

        val response = jsonClient.post("/mcp") {
            header(HttpHeaders.Authorization, "Bearer ${session.accessToken}")
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody("{}")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.headers[HttpHeaders.WWWAuthenticate].orEmpty().contains("resource_metadata"))
    }

    @Test
    fun rejectsQueryTokensAndUntrustedOrigins() = testApplication {
        val runtime = testRuntime()
        application { deviceAsMcpModule(runtime) }
        val user = requireNotNull(runtime.accounts.register("origin-user", "long enough password"))
        val key = runtime.accounts.createAuthKey(
            user.id,
            "test",
            runtime.oauth.resourceUri,
            setOf(OAuthService.MCP_SCOPE),
        )

        assertEquals(
            HttpStatusCode.BadRequest,
            client.post("/mcp?access_token=${key.token}").status,
        )
        val untrusted = client.post("/mcp") {
            mcpHeaders(key.token, "tools/list")
            header(HttpHeaders.Origin, "https://attacker.example")
            setBody(mcpRequest("tools/list"))
        }
        assertEquals(HttpStatusCode.Forbidden, untrusted.status)
    }

    @Test
    fun validatesToolArgumentSchemaBeforeInvocation() = testApplication {
        val runtime = testRuntime()
        application { deviceAsMcpModule(runtime) }
        val user = requireNotNull(runtime.accounts.register("schema-user", "long enough password"))
        val key = runtime.accounts.createAuthKey(
            user.id,
            "test",
            runtime.oauth.resourceUri,
            setOf(OAuthService.MCP_SCOPE),
        )

        val response = client.post("/mcp") {
            mcpHeaders(key.token, "tools/call", "list_device")
            setBody(
                mcpRequest(
                    method = "tools/call",
                    extraParams = ""","name":"list_device","arguments":{"unexpected":true}""",
                ),
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = ProtocolJson.parseToJsonElement(response.body<String>())
            .jsonObject.getValue("error").jsonObject
        assertEquals(-32602, error.getValue("code").jsonPrimitive.content.toInt())
    }

    @Test
    fun agentCanUpdateAndListDeviceDescriptions() = testApplication {
        val runtime = testRuntime()
        application { deviceAsMcpModule(runtime) }
        val user = requireNotNull(
            runtime.accounts.register("description-agent", "long enough password"),
        )
        val device = runtime.accounts.enrollDevice(user.id, "workstation", "linux-x64")
        val key = runtime.accounts.createAuthKey(
            user.id,
            "test",
            runtime.oauth.resourceUri,
            setOf(OAuthService.MCP_SCOPE),
        )
        val description = "Primary build workstation"

        val updated = client.post("/mcp") {
            mcpHeaders(key.token, "tools/call", "update_device_description")
            setBody(
                mcpRequest(
                    method = "tools/call",
                    extraParams =
                        ""","name":"update_device_description","arguments":{"deviceId":"${device.deviceId.value}","description":"$description"}""",
                ),
            )
        }

        assertEquals(HttpStatusCode.OK, updated.status)
        val updatedDevice = ProtocolJson.parseToJsonElement(updated.body<String>())
            .jsonObject.getValue("result").jsonObject
            .getValue("structuredContent").jsonObject
        assertEquals(description, updatedDevice.getValue("description").jsonPrimitive.content)

        val listed = client.post("/mcp") {
            mcpHeaders(key.token, "tools/call", "list_device")
            setBody(
                mcpRequest(
                    method = "tools/call",
                    extraParams = ""","name":"list_device","arguments":{}""",
                ),
            )
        }

        assertEquals(HttpStatusCode.OK, listed.status)
        val listedDevice = ProtocolJson.parseToJsonElement(listed.body<String>())
            .jsonObject.getValue("result").jsonObject
            .getValue("structuredContent").jsonArray.single().jsonObject
        assertEquals(description, listedDevice.getValue("description").jsonPrimitive.content)
    }

    @Test
    fun enforcesMcpAudienceAndScope() = testApplication {
        val runtime = testRuntime()
        application { deviceAsMcpModule(runtime) }
        val user = requireNotNull(runtime.accounts.register("token-user", "long enough password"))
        val wrongAudience = runtime.accounts.createAuthKey(
            user.id,
            "wrong audience",
            "https://wrong.example/mcp",
            setOf(OAuthService.MCP_SCOPE),
        )
        val wrongScope = runtime.accounts.createAuthKey(
            user.id,
            "wrong scope",
            runtime.oauth.resourceUri,
            setOf("device:read"),
        )

        val audienceResponse = client.post("/mcp") {
            mcpHeaders(wrongAudience.token, "tools/list")
            setBody(mcpRequest("tools/list"))
        }
        val scopeResponse = client.post("/mcp") {
            mcpHeaders(wrongScope.token, "tools/list")
            setBody(mcpRequest("tools/list"))
        }

        assertEquals(HttpStatusCode.Unauthorized, audienceResponse.status)
        assertEquals(HttpStatusCode.Forbidden, scopeResponse.status)
        assertTrue(scopeResponse.headers[HttpHeaders.WWWAuthenticate].orEmpty().contains("insufficient_scope"))
    }

    @Test
    fun revokedKeysNotificationsAndOriginSyntaxAreHandled() = testApplication {
        val runtime = testRuntime()
        application { deviceAsMcpModule(runtime) }
        val user = requireNotNull(runtime.accounts.register("lifecycle-user", "long enough password"))
        val key = runtime.accounts.createAuthKey(
            user.id,
            "test",
            runtime.oauth.resourceUri,
            setOf(OAuthService.MCP_SCOPE),
        )

        val notification = client.post("/mcp") {
            mcpHeaders(key.token, "tools/list")
            setBody(mcpRequest("tools/list").replace("\"id\":1,", ""))
        }
        assertEquals(HttpStatusCode.Accepted, notification.status)
        assertTrue(notification.body<String>().isEmpty())

        val malformedOrigin = client.post("/mcp") {
            mcpHeaders(key.token, "tools/list")
            header(HttpHeaders.Origin, "http://localhost/not-an-origin")
            setBody(mcpRequest("tools/list"))
        }
        assertEquals(HttpStatusCode.Forbidden, malformedOrigin.status)

        assertTrue(runtime.accounts.revokeAuthKey(user.id, key.id))
        val revoked = client.post("/mcp") {
            mcpHeaders(key.token, "tools/list")
            setBody(mcpRequest("tools/list"))
        }
        assertEquals(HttpStatusCode.Unauthorized, revoked.status)
    }

    private fun testRuntime() = ServerRuntime(
        ServerConfig(
            host = "127.0.0.1",
            port = 0,
            publicBaseUrl = "http://localhost",
            frontendBaseUrl = "http://localhost:8081",
            postgresUrl = null,
            postgresUser = "",
            postgresPassword = "",
            redisUrl = null,
            rabbitMqUrl = null,
            githubClientId = null,
            githubClientSecret = null,
            instanceId = InstanceId("test-instance"),
        ),
        InMemoryAccountStore(),
        InMemoryRoutingStore(),
    )

    private fun HttpRequestBuilder.mcpHeaders(token: String, method: String, name: String? = null) {
        header(HttpHeaders.Authorization, "Bearer $token")
        header(HttpHeaders.Accept, "application/json, text/event-stream")
        header(HttpHeaders.ContentType, ContentType.Application.Json)
        header("MCP-Protocol-Version", MODERN_MCP_VERSION)
        header("Mcp-Method", method)
        name?.let { header("Mcp-Name", it) }
    }

    private fun mcpRequest(method: String, extraParams: String = ""): String =
        """
        {
          "jsonrpc":"2.0",
          "id":1,
          "method":"$method",
          "params":{
            "_meta":{
              "io.modelcontextprotocol/protocolVersion":"$MODERN_MCP_VERSION",
              "io.modelcontextprotocol/clientCapabilities":{}
            }$extraParams
          }
        }
        """.trimIndent()
}
